package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

@Serializable enum class AssetCategory(val label:String) {
    CASH("资金账户"), INVESTMENT("投资理财"), RECEIVABLE("待收款"), DEBT("负债账户"), PREPAID("充值"), TUITION("学费")
}
@Serializable data class AssetBalanceRecord(val id:String=newId(),val balance:String,val date:String,val kind:String="余额更新")
@Serializable data class AssetAccount(
    val id:String=newId(),val name:String,val category:AssetCategory,val balance:String="0",val currency:String="CNY",
    val asOf:String=LocalDate.now().toString(),val included:Boolean=true,val archived:Boolean=false,val note:String="",
    val subtype:String="",val cnyRate:String?=null,val planId:String?=null,val studentId:String?=null,
    val verified:Boolean=true,val sourceMissing:Boolean=false,val sourceUnverified:Boolean=false,val sourceName:String?=null,
    val eligibleAmount:String?=null,val eligibleBasisBalance:String?=null,val eligibleFromPlatform:Boolean=false,
    val history:List<AssetBalanceRecord> = emptyList()
)
@Serializable data class NetWorthPoint(val at:String,val assets:String,val liabilities:String,val pending:Int,val reason:String)
@Serializable data class AssetBook(
    val accounts:List<AssetAccount> = emptyList(),val history:List<NetWorthPoint> = emptyList(),
    val tuitionPublishedAt:String?=null,val tuitionFetchedAt:String?=null
)
@Serializable data class TuitionStudent(val id:String,val name:String,val status:String,val balanceCents:Long,val verified:Boolean)
@Serializable data class TuitionSnapshot(val version:Int=1,val publishedAt:String,val students:List<TuitionStudent>)
data class AssetValue(val account:AssetAccount,val amount:BigDecimal,val cny:BigDecimal?,val liability:Boolean,val pendingReason:String?=null)
data class AssetTotals(val assets:BigDecimal,val liabilities:BigDecimal,val pending:Int) { val net:BigDecimal get()=assets-liabilities }

object Assets {
    fun defaultIncluded(category:AssetCategory,subtype:String="") = category!=AssetCategory.PREPAID || subtype=="饭卡"
    fun accounts(l:Ledger):List<AssetAccount> {
        val refs=l.assets.accounts.mapNotNull {it.planId}.toSet()
        val projected=l.plans.filter {it.balanceAccount!=null || it.usageBalance!=null}.filter {it.id !in refs}.map {
            AssetAccount(id="plan:${it.id}",name=it.name,category=AssetCategory.PREPAID,planId=it.id,currency=it.currency,
                included=false,archived=it.archived)
        }
        return (l.assets.accounts+projected).map { a ->
            val p=a.planId?.let {id->l.plans.find {it.id==id}} ?: return@map a
            if(p.balanceAccount==null && p.usageBalance==null) return@map a
            a.copy(name=a.name.ifBlank {p.name},balance=p.balanceAccount?.balance ?: p.usageBalance!!.balance,
                currency=p.currency,asOf=p.balanceAccount?.asOf ?: p.usageBalance!!.asOf,
                cnyRate=a.cnyRate.takeIf {a.currency==p.currency},eligibleAmount=a.eligibleAmount.takeIf {a.currency==p.currency},
                eligibleBasisBalance=a.eligibleBasisBalance.takeIf {a.currency==p.currency},eligibleFromPlatform=a.eligibleFromPlatform && a.currency==p.currency)
        }
    }
    fun value(l:Ledger,a:AssetAccount):AssetValue {
        val raw=BigDecimal(a.balance)
        val tuition=a.category==AssetCategory.TUITION
        val liability=if(tuition) raw.signum()>0 else a.category==AssetCategory.DEBT || raw.signum()<0
        val p=a.planId?.let {id->l.plans.find {it.id==id}}
        val missingPlan=a.planId!=null && (p==null || p.balanceAccount==null && p.usageBalance==null)
        val api=p?.usageBalance!=null
        val eligible=if(api) a.eligibleAmount?.takeIf {a.eligibleBasisBalance==a.balance}?.let(::BigDecimal) else raw.abs()
        val reason=when {
            a.sourceMissing -> "来源未包含此学生，保留上次余额"
            a.sourceUnverified -> "来源余额待核实，保留上次已确认值"
            !a.verified -> "余额待核实"
            missingPlan -> "来源账户已移除"
            api && eligible==null -> "请确认可计入的充值余额"
            a.currency!="CNY" && a.cnyRate==null -> "待折算"
            else -> null
        }
        // Missing-source accounts retain their last confirmed amount; an unverified amount never enters totals.
        val cny=if(!a.verified || missingPlan) null else eligible?.let {if(a.currency=="CNY") it else a.cnyRate?.let {rate->it*BigDecimal(rate)}}
        return AssetValue(a,raw.abs(),cny,liability,reason)
    }
    fun totals(l:Ledger,accounts:List<AssetAccount> = accounts(l)):AssetTotals {
        var assets=BigDecimal.ZERO;var debts=BigDecimal.ZERO;var pending=0
        accounts.filter {it.included}.forEach {a->val v=value(l,a);if(v.cny==null) pending++ else if(v.liability) debts+=v.cny else assets+=v.cny}
        return AssetTotals(assets,debts,pending)
    }
    fun put(l:Ledger,a:AssetAccount):Ledger {
        val old=accounts(l).find {it.id==a.id}
        require(old?.planId==a.planId && old?.studentId==a.studentId || old==null && a.planId==null && a.studentId==null) {"不能更改账户来源"}
        return l.copy(assets=l.assets.copy(accounts=if(l.assets.accounts.any {it.id==a.id}) l.assets.accounts.map {if(it.id==a.id) a else it} else l.assets.accounts+a)).also(::validate)
    }
    fun updateBalance(l:Ledger,id:String,balance:String,date:String,correction:Boolean=false):Ledger {
        val a=l.assets.accounts.single {it.id==id}
        require(a.planId==null && a.studentId==null) {"请在来源中更新余额"}
        val earliest=if(correction) a.history.dropLast(1).lastOrNull()?.date else a.asOf
        require(earliest==null || LocalDate.parse(date)>=LocalDate.parse(earliest)) {"余额日期不能早于前一条记录"}
        val record=AssetBalanceRecord(balance=balance,date=date,kind=if(correction) "余额校正" else "余额更新")
        return put(l,a.copy(balance=balance,asOf=date,history=if(correction && a.history.isNotEmpty()) a.history.dropLast(1)+record else a.history+record))
    }
    fun invalidatePlatformAmount(l:Ledger,planId:String):Ledger = l.copy(assets=l.assets.copy(accounts=l.assets.accounts.map {if(it.planId==planId) it.copy(eligibleAmount=null,eligibleBasisBalance=null,eligibleFromPlatform=false) else it}))
    internal fun recordPlatformSnapshot(l:Ledger,planId:String,snapshot:DeepSeekBalanceSnapshot):Ledger {
        val a=accounts(l).find {it.planId==planId} ?: return l
        val p=l.plans.find {it.id==planId} ?: return l
        if(p.currency!=snapshot.currency || p.usageBalance?.balance!=snapshot.totalBalance || p.usageBalance.asOf!=snapshot.queriedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) return l
        return put(l,a.copy(eligibleAmount=snapshot.toppedUpBalance,eligibleBasisBalance=snapshot.totalBalance,eligibleFromPlatform=true))
    }
    fun newTuitionStudents(l:Ledger,snapshot:TuitionSnapshot):List<TuitionStudent> {
        validateSnapshot(snapshot)
        val ids=l.assets.accounts.mapNotNull {it.studentId}.toSet()
        return snapshot.students.filter {it.id !in ids}
    }
    fun importTuition(l:Ledger,snapshot:TuitionSnapshot,selectedNewStudentIds:Set<String>,fetchedAt:String):Ledger {
        validateSnapshot(snapshot);Instant.parse(fetchedAt)
        val previous=l.assets.tuitionPublishedAt?.let(Instant::parse)
        val incoming=Instant.parse(snapshot.publishedAt)
        require(previous==null || incoming>=previous) {"这是较早发布的余额，已保留当前数据"}
        val byId=snapshot.students.associateBy {it.id}
        require(selectedNewStudentIds.all {it in byId}) {"所选学生不在本次数据中"}
        val existing=l.assets.accounts.mapNotNull {it.studentId}.toSet()
        val updated=l.assets.accounts.map {a->
            if(a.studentId==null || previous==incoming) a else {
                val s=byId[a.studentId]
                if(s==null) a.copy(sourceMissing=true)
                else if(!s.verified && a.verified) a.copy(sourceMissing=false,sourceUnverified=true,sourceName=s.name) // retain last confirmed value
                else {
                    val amount=BigDecimal.valueOf(s.balanceCents,2).toPlainString()
                    val history=if(a.balance==amount && a.verified==s.verified) a.history else a.history+AssetBalanceRecord(balance=amount,date=incoming.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),kind="学费同步")
                    a.copy(balance=amount,asOf=incoming.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),verified=s.verified,sourceMissing=false,sourceUnverified=false,sourceName=s.name,history=history)
                }
            }
        }
        val added=snapshot.students.filter {it.id in selectedNewStudentIds && it.id !in existing}.map {s->
            val amount=BigDecimal.valueOf(s.balanceCents,2).toPlainString();val date=incoming.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            AssetAccount(name=s.name,sourceName=s.name,category=AssetCategory.TUITION,studentId=s.id,balance=amount,asOf=date,verified=s.verified,
                history=listOf(AssetBalanceRecord(balance=amount,date=date,kind="首次导入")))
        }
        return l.copy(assets=l.assets.copy(accounts=updated+added,tuitionPublishedAt=snapshot.publishedAt,tuitionFetchedAt=fetchedAt)).also(::validate)
    }
    fun validateTuition(s:TuitionSnapshot) = validateSnapshot(s)
    fun validateSnapshot(s:TuitionSnapshot) {
        require(s.version==1) {"不支持的学费数据版本"};require(Instant.parse(s.publishedAt)<=Instant.now().plusSeconds(300)) {"发布时间晚于当前时间"}
        require(s.students.size<=10000 && s.students.map {it.id}.distinct().size==s.students.size) {"学生标识重复或数量超限"}
        s.students.forEach { require(it.id.isNotBlank() && it.id.length<=200 && it.name.isNotBlank() && it.name.length<=100 && it.status in setOf("active","paused","archived")) {"学生数据格式错误"};require(it.balanceCents in -99999999999999L..99999999999999L) {"学费余额超限"} }
    }
    fun observe(before:Ledger,after:Ledger):Ledger {
        fun signature(l:Ledger)=accounts(l).filter {it.included}.map {a->val v=value(l,a);listOf(a.id,v.cny?.stripTrailingZeros()?.toPlainString(),v.liability,a.verified)}.sortedBy {it[0].toString()}
        if(signature(before)==signature(after)) return after
        val old=accounts(before).filter {it.included};val next=accounts(after).filter {it.included}
        val scope=old.map {it.id to it.included}.toSet()!=next.map {it.id to it.included}.toSet() || next.any {a->old.find {it.id==a.id}?.let {it.category!=a.category || it.cnyRate!=a.cnyRate}==true}
        val correction=next.any {a->a.history.lastOrNull()?.kind=="余额校正" && a.history!=old.find {it.id==a.id}?.history}
        val t=totals(after)
        val point=NetWorthPoint(Instant.now().toString(),t.assets.toPlainString(),t.liabilities.toPlainString(),t.pending,if(scope) "统计范围调整" else if(correction) "余额校正" else "余额更新")
        return after.copy(assets=after.assets.copy(history=after.assets.history+point))
    }
    fun validate(l:Ledger) {
        val b=l.assets
        require(b.accounts.size<=20000 && b.history.size<=100000) {"资产记录数量超限"}
        require(b.accounts.map {it.id}.distinct().size==b.accounts.size) {"资产标识重复"}
        require(b.accounts.mapNotNull {it.planId}.let {it.distinct().size==it.size} && b.accounts.mapNotNull {it.studentId}.let {it.distinct().size==it.size}) {"来源账户重复"}
        fun money(s:String,signed:Boolean=false) {require(s.matches(Regex(if(signed) "-?[0-9]{1,12}(\\.[0-9]{1,4})?" else "[0-9]{1,12}(\\.[0-9]{1,4})?"))) {"资产金额格式错误（最多4位小数）"}}
        fun date(s:String) {require(LocalDate.parse(s).let {it.year in 1900..2200 && it<=LocalDate.now()}) {"余额日期不能晚于今天"}}
        b.accounts.forEach {a->
            require(a.id.isNotBlank() && a.name.isNotBlank() && a.name.length<=100 && a.note.length<=2000) {"请填写有效账户名称"}
            require(a.planId==null || a.studentId==null);require(a.studentId==null || a.category==AssetCategory.TUITION);require(a.planId==null || a.category==AssetCategory.PREPAID)
            money(a.balance,true);if(a.category!=AssetCategory.TUITION && a.category!=AssetCategory.CASH && a.category!=AssetCategory.PREPAID) require(BigDecimal(a.balance).signum()>=0) {"请输入非负余额"}
            Currency.getInstance(a.currency);date(a.asOf);a.cnyRate?.let {money(it);require(BigDecimal(it).signum()>0) {"折算汇率必须大于0"}}
            a.eligibleAmount?.let {money(it);require(BigDecimal(it)<=BigDecimal(a.eligibleBasisBalance ?: "0")) {"计入金额不能超过平台余额"}};a.eligibleBasisBalance?.let {money(it)}
            require(a.history.size<=100000 && a.history.map {it.id}.distinct().size==a.history.size)
            a.history.forEach {money(it.balance,true);date(it.date)}
        }
        b.tuitionPublishedAt?.let(Instant::parse);b.tuitionFetchedAt?.let(Instant::parse)
        b.history.forEach {Instant.parse(it.at);require(BigDecimal(it.assets).signum()>=0 && BigDecimal(it.liabilities).signum()>=0 && it.pending>=0)}
    }
}
