package cn.renewboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

internal class PlatformBalanceService(
    private val repo: () -> Repository,
    private val store: PlatformConnectionStore,
    private val query: () -> PlatformBalanceQuery
) {
    private val operations = ConcurrentHashMap<String, Mutex>()

    suspend fun connect(planId: String, apiKey: String) = update(planId, apiKey.trim())
    suspend fun refresh(planId: String) = update(planId, null)

    suspend fun disconnect(planId: String) = locked(planId) {
        withContext(Dispatchers.IO) { store.disconnect(planId) }
    }

    private suspend fun <T> locked(planId: String, action: suspend () -> T): T {
        val mutex = operations.getOrPut(planId) { Mutex() }
        if(!mutex.tryLock()) throw PlatformBalanceException("正在查询余额，请稍候")
        try { return action() } finally { mutex.unlock() }
    }

    private suspend fun update(planId: String, replacementKey: String?) = locked(planId) {
        val repository = repo()
        val initial = repository.read().plans.singleOrNull { it.id == planId }
            ?: throw PlatformBalanceException("账户已删除")
        if(initial.archived || initial.usageBalance == null)
            throw PlatformBalanceException("请选择使用中的平台余额账户")
        val apiKey = replacementKey ?: withContext(Dispatchers.IO) { store.apiKey(planId) }
        val snapshot = query().query(apiKey, initial.currency)
        updatedPlatformPlan(initial, snapshot)
        // No network is held inside the short database transaction. Once committing, finish or restore.
        withContext(Dispatchers.IO + NonCancellable) {
            var committed = false
            try {
                store.saveWithLedgerUpdate(planId, apiKey, snapshot, update = {
                    repository.update(transform = { applyPlatformBalance(it, initial, snapshot) },
                        onCommitted = { committed = true })
                }, wasCommitted = { committed })
            } catch(e: Exception) {
                // Deleting or converting an account while its query runs must not revive its old credentials.
                val removed = runCatching {
                    repository.read().plans.singleOrNull { it.id == planId }?.usageBalance == null
                }.getOrDefault(false)
                if(removed) store.disconnect(planId)
                throw e
            }
        }
    }
}

internal fun updatedPlatformPlan(initial: Plan, snapshot: DeepSeekBalanceSnapshot): Plan {
    if(snapshot.currency != initial.currency) throw PlatformBalanceException("平台余额币种与账户不一致")
    val account = initial.usageBalance ?: throw PlatformBalanceException("请选择平台余额账户")
    val date = snapshot.queriedAt.atZone(ZoneId.systemDefault()).toLocalDate()
    try {
        listOf(snapshot.totalBalance, snapshot.grantedBalance, snapshot.toppedUpBalance).forEach {
            if(BigDecimal(it).signum() < 0) throw IllegalArgumentException()
        }
        if(snapshot.queriedAt > Instant.now() || date > LocalDate.now() || date < LocalDate.parse(account.asOf)) throw IllegalArgumentException()
    } catch(e: Exception) { throw PlatformBalanceException("平台余额响应异常，请稍后重试") }
    return initial.copy(usageBalance = account.copy(
        balance = snapshot.totalBalance,
        asOf = date.toString()))
}

internal fun applyPlatformBalance(ledger: Ledger, initial: Plan, snapshot: DeepSeekBalanceSnapshot): Ledger {
    val current = ledger.plans.singleOrNull { it.id == initial.id }
        ?: throw PlatformBalanceException("账户已删除，请返回服务管理")
    if(current.archived || current.currency != initial.currency || current.usageBalance != initial.usageBalance)
        throw PlatformBalanceException("账户已修改，请重新刷新余额")
    val updated = updatedPlatformPlan(current, snapshot)
    return Assets.recordPlatformSnapshot(ledger.copy(plans = ledger.plans.map { if(it.id == initial.id) updated else it }),initial.id,snapshot)
}
