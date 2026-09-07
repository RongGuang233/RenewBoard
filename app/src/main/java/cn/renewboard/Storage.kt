package cn.renewboard

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

// A personal ledger is committed as one small Room transaction so restoring related
// plans, benefits and receipts cannot expose a partially replaced book.
@Entity data class BookRow(@PrimaryKey val id: Int = 1, val payload: String)
@Entity data class ReminderRow(@PrimaryKey val event: String, val day: String)
@Dao interface BookDao {
    @Query("SELECT * FROM BookRow WHERE id=1") fun observe(): Flow<BookRow?>
    @Query("SELECT * FROM BookRow WHERE id=1") suspend fun read(): BookRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: BookRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claim(row: ReminderRow): Long
    @Query("DELETE FROM ReminderRow WHERE event=:event") suspend fun release(event: String)
    @Query("DELETE FROM ReminderRow WHERE day < :cutoff") suspend fun prune(cutoff: String)
}
@Database(entities = [BookRow::class, ReminderRow::class], version = 1, exportSchema = false)
abstract class BookDatabase: RoomDatabase() { abstract fun book(): BookDao }
class Repository(val db: BookDatabase, private val changed: () -> Unit = {}) {
    val flow: Flow<Ledger> = db.book().observe().map { parse(it) }
    private fun parse(row: BookRow?) = row?.let { Book.json.decodeFromString<Ledger>(it.payload) } ?: Ledger()
    suspend fun read() = parse(db.book().read())
    suspend fun update(transform: (Ledger) -> Ledger) {
        db.withTransaction {
            val next = transform(read()); Book.validate(next)
            db.book().put(BookRow(payload = Book.json.encodeToString(next)))
        }
        changed()
    }
    suspend fun recordMonthlyFees(today:java.time.LocalDate=java.time.LocalDate.now()) {
        val didChange=db.withTransaction {
            val old=read();val next=Prepaid.accrue(old,today)
            if(next==old) false else {
                Book.validate(next)
                db.book().put(BookRow(payload=Book.json.encodeToString(next)));true
            }
        }
        if(didChange) changed()
    }
    suspend fun restore(backup: Backup) { Book.validate(backup.data); update { backup.data } }
}
class RenewApp: Application() {
    lateinit var repository: Repository
    override fun onCreate() {
        super.onCreate()
        repository = Repository(Room.databaseBuilder(this, BookDatabase::class.java, "renewboard.db").build()) { Jobs.backup(this) }
        Jobs.schedule(this)
    }
}
fun Context.repository() = (applicationContext as RenewApp).repository
