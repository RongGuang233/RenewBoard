package cn.renewboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class AssetsTest {
    private val date="2024-03-01"
    private fun account(id:String="cash",balance:String="5000",category:AssetCategory=AssetCategory.CASH)=AssetAccount(id=id,name=id,category=category,balance=balance,asOf=date)
    private fun ledger(vararg accounts:AssetAccount)=Ledger(assets=AssetBook(accounts=accounts.toList()))
    private fun snapshot(at:String="2024-03-01T08:00:00Z",vararg students:TuitionStudent)=TuitionSnapshot(publishedAt=at,students=students.toList())
    private fun student(id:String="s1",balance:Long=200000,verified:Boolean=true)=TuitionStudent(id,"学生 $id","active",balance,verified)
    private fun money(expected:String,actual:BigDecimal)=assertEquals(0,BigDecimal(expected).compareTo(actual))
    private fun api()=Plan(id="api",name="DeepSeek",amount="0",currency="CNY",autoRenew=false,billingAnchor=date,paidCycles=0,usageBalance=UsageBalance("100",date))

    @Test fun netWorthSubtractsUnearnedTuitionAndDoesNotDoubleCountLedgerSpending() {
        val start=Assets.importTuition(ledger(account()),snapshot(students=arrayOf(student())),setOf("s1"),"2024-03-01T08:01:00Z")
        money("5000",Assets.totals(start).assets);money("2000",Assets.totals(start).liabilities);money("3000",Assets.totals(start).net)
        val after=Assets.importTuition(start,snapshot("2024-03-02T08:00:00Z",student(balance=170000)),emptySet(),"2024-03-02T08:01:00Z")
        money("3300",Assets.totals(after).net);assertEquals("5000",after.assets.accounts.first().balance)
        val withSpending=after.copy(payments=listOf(Payment(planId="old",planName="历史支出",amount="100",currency="CNY",date=date)))
        money("3300",Assets.totals(withSpending).net)
    }
    @Test fun negativeTuitionBecomesReceivableAndUnverifiedIsPending() {
        val l=Assets.importTuition(Ledger(),snapshot(students=arrayOf(student(balance=-30000),student("s2",50000,false))),setOf("s1","s2"),"2024-03-01T08:01:00Z")
        money("300",Assets.totals(l).assets);money("0",Assets.totals(l).liabilities);assertEquals(1,Assets.totals(l).pending)
    }
    @Test fun prepaidDefaultsExcludePhoneAndApiButIncludeMealCard() {
        assertFalse(Assets.defaultIncluded(AssetCategory.PREPAID));assertTrue(Assets.defaultIncluded(AssetCategory.PREPAID,"饭卡"))
        val l=Ledger(plans=listOf(api()))
        assertFalse(Assets.accounts(l).single().included);assertEquals(AssetBook(),Assets.observe(Ledger(),l).assets)
        val manual=Assets.put(l,Assets.accounts(l).single().copy(included=true))
        assertEquals(1,Assets.accounts(manual).size);assertEquals(1,Assets.totals(manual).pending)
    }
    @Test fun platformOnlyIncludesPaidCreditAndInvalidatedCalibrationRequiresConfirmation() {
        var l=Ledger(plans=listOf(api()))
        val s=DeepSeekBalanceSnapshot("CNY","100","60","40",Instant.parse("2024-03-01T08:00:00Z"))
        l=Assets.recordPlatformSnapshot(l,"api",s)
        l=Assets.put(l,Assets.accounts(l).single().copy(included=true))
        money("40",Assets.totals(l).assets)
        val invalidated=Assets.invalidatePlatformAmount(l,"api")
        assertEquals(1,Assets.totals(invalidated).pending);money("0",Assets.totals(invalidated).assets)
        assertEquals(invalidated,Assets.recordPlatformSnapshot(invalidated,"api",s.copy(currency="USD")))
    }
    @Test fun excludedPlatformRefreshAndManualAccountDoNotCreateNetWorthObservations() {
        val l=Ledger(plans=listOf(api()))
        val refreshed=Assets.recordPlatformSnapshot(l,"api",DeepSeekBalanceSnapshot("CNY","100","60","40",Instant.parse("2024-03-01T08:00:00Z")))
        assertTrue(Assets.observe(l,refreshed).assets.history.isEmpty())
        val excluded=Assets.put(refreshed,account().copy(included=false))
        assertTrue(Assets.observe(refreshed,excluded).assets.history.isEmpty())
        val included=Assets.put(excluded,excluded.assets.accounts.last().copy(included=true))
        assertEquals("统计范围调整",Assets.observe(excluded,included).assets.history.single().reason)
    }
    @Test fun missingOrConvertedPlanRetainsReferenceWithoutCrashingOrCounting() {
        val l=Assets.put(Ledger(plans=listOf(api())),Assets.accounts(Ledger(plans=listOf(api()))).single().copy(included=true,eligibleAmount="40",eligibleBasisBalance="100"))
        val removed=l.copy(plans=emptyList())
        assertEquals(1,Assets.totals(removed).pending)
        val changed=l.copy(plans=listOf(api().copy(usageBalance=null)))
        assertEquals(1,Assets.accounts(changed).size);assertEquals(1,Assets.totals(changed).pending)
    }
    @Test fun manualCurrencyRateIsExplicitAndUnrelatedRatesDoNotChangeNetWorth() {
        val foreign=account().copy(currency="USD",balance="100")
        val l=ledger(foreign).copy(settings=Settings(rates=mapOf("USD" to "7")))
        assertEquals(1,Assets.totals(l).pending)
        money("720",Assets.totals(Assets.put(l,foreign.copy(cnyRate="7.2"))).assets)
    }
    @Test fun archivingAndUpdatingPreserveAccountOrderAndDoNotChangeInclusion() {
        val a=account();val b=account("other","100")
        val l=ledger(a,b);val saved=Assets.put(l,a.copy(archived=true))
        assertEquals(listOf("cash","other"),saved.assets.accounts.map {it.id});money("5100",Assets.totals(saved).net)
        assertEquals(saved,Assets.observe(l,saved))
    }
    @Test fun tuitionRepeatedSnapshotDoesNotDuplicateAndLaterOptInIsPossible() {
        val s=snapshot(students=arrayOf(student(),student("s2",10000)))
        val l=Assets.importTuition(Ledger(),s,setOf("s1"),"2024-03-01T08:01:00Z")
        val repeated=Assets.importTuition(l,s,setOf("s1"),"2024-03-01T08:02:00Z")
        assertEquals(l.assets.accounts,repeated.assets.accounts)
        assertEquals(2,Assets.importTuition(repeated,s,setOf("s2"),"2024-03-01T08:03:00Z").assets.accounts.size)
        assertThrows(IllegalArgumentException::class.java) {Assets.importTuition(l,s.copy(publishedAt="2024-02-29T08:00:00Z"),emptySet(),"2024-03-01T08:03:00Z")}
    }
    @Test fun tuitionMissingAndUnverifiedPreserveConfirmedAmountAliasAndSettings() {
        val first=Assets.importTuition(Ledger(),snapshot(students=arrayOf(student())),setOf("s1"),"2024-03-01T08:01:00Z")
        val a=first.assets.accounts.single().copy(name="我的称呼",included=false,archived=true)
        val l=Assets.put(first,a)
        val missing=Assets.importTuition(l,snapshot("2024-03-02T08:00:00Z"),emptySet(),"2024-03-02T08:01:00Z")
        assertTrue(missing.assets.accounts.single().sourceMissing);assertEquals(a.balance,missing.assets.accounts.single().balance)
        val uncertain=Assets.importTuition(missing,snapshot("2024-03-03T08:00:00Z",student(balance=10000,verified=false)),emptySet(),"2024-03-03T08:01:00Z")
        val kept=uncertain.assets.accounts.single();assertEquals(a.balance,kept.balance);assertEquals("我的称呼",kept.name);assertFalse(kept.included);assertTrue(kept.archived);assertTrue(kept.sourceUnverified);assertTrue(kept.verified)
    }
    @Test fun historyOnlyTracksRelevantChangesAndCorrectionIsDistinctFromNewObservation() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="5000",date=date,kind="初始余额")))
        val l=Assets.observe(Ledger(),ledger(a));assertEquals("统计范围调整",l.assets.history.single().reason)
        val updated=Assets.observe(l,Assets.updateBalance(l,a.id,"5200",date))
        assertEquals(2,updated.assets.history.size);assertEquals(2,updated.assets.accounts.single().history.size)
        val corrected=Assets.observe(updated,Assets.updateBalance(updated,a.id,"5100",date,true))
        assertEquals(2,corrected.assets.accounts.single().history.size);assertEquals("余额校正",corrected.assets.history.last().reason)
        val excluded=Assets.put(corrected,corrected.assets.accounts.single().copy(included=false))
        val changedExcluded=Assets.updateBalance(excluded,a.id,"9000",date)
        assertEquals(excluded.assets.history,Assets.observe(excluded,changedExcluded).assets.history)
    }
    @Test fun backupsRoundTripAssetsAndDoNotUpgradeEmptyOrLegacyBooks() {
        assertEquals(1,Book.decode(Book.encode(Ledger())).version)
        assertEquals(2,Book.decode(Book.encode(Ledger(plans=listOf(api())))).version)
        val l=ledger(account());val encoded=Book.encode(l)
        assertEquals(3,Book.decode(encoded).version);assertEquals(l,Book.decode(encoded).data)
        assertThrows(IllegalArgumentException::class.java) {Book.decode(encoded.replace("\"version\":3","\"version\":2"))}
    }
    @Test fun versionThreeRequiresAssetCollectionsAndCriticalAccountRecords() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="5000",date=date,kind="初始余额")))
        val l=Assets.observe(Ledger(),ledger(a))
        val root=Book.json.parseToJsonElement(Book.encode(l)).jsonObject
        val data=root.getValue("data").jsonObject
        val assets=data.getValue("assets").jsonObject
        fun decodeWith(assetData:JsonObject) = Book.decode(JsonObject(root+("data" to JsonObject(data+("assets" to assetData)))).toString())
        assertThrows(IllegalArgumentException::class.java) {Book.decode(JsonObject(root+("data" to JsonObject(data-"assets"))).toString())}
        for(field in listOf("accounts","history")) assertThrows(IllegalArgumentException::class.java) {decodeWith(JsonObject(assets-field))}
        val accountJson=(assets.getValue("accounts") as JsonArray).first().jsonObject
        for(field in listOf("id","name","balance","currency","asOf","included","archived","planId","studentId","history")) {
            assertThrows(IllegalArgumentException::class.java) {decodeWith(JsonObject(assets+("accounts" to JsonArray(listOf(JsonObject(accountJson-field))))))}
        }
        val record=(accountJson.getValue("history") as JsonArray).first().jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(assets+("accounts" to JsonArray(listOf(JsonObject(accountJson+("history" to JsonArray(listOf(JsonObject(record-"balance"))))))))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val point=(assets.getValue("history") as JsonArray).first().jsonObject
            decodeWith(JsonObject(assets+("history" to JsonArray(listOf(JsonObject(point-"assets"))))))
        }
    }

    @Test fun invalidAmountsDatesDuplicateSourcesAndSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {Book.validate(ledger(account(balance="NaN")))}
        assertThrows(IllegalArgumentException::class.java) {Book.validate(ledger(account().copy(cnyRate="0")))}
        assertThrows(IllegalArgumentException::class.java) {Book.validate(ledger(account().copy(asOf=LocalDate.now().plusDays(1).toString())))}
        assertThrows(IllegalArgumentException::class.java) {Assets.validateTuition(snapshot(students=arrayOf(student(),student())))}
        assertThrows(IllegalArgumentException::class.java) {Assets.validateTuition(snapshot(Instant.now().plusSeconds(600).toString(),student()))}
        assertThrows(IllegalArgumentException::class.java) {Assets.validateTuition(snapshot(students=arrayOf(student().copy(status="deleted"))))}
    }
}
