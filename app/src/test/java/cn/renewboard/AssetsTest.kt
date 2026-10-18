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
    @Test fun rechargeKeepsManualAccountsAndDoesNotProjectPhoneOrApiPlans() {
        assertFalse(Assets.defaultIncluded(AssetCategory.PREPAID));assertTrue(Assets.defaultIncluded(AssetCategory.PREPAID,"饭卡"))
        val meal=account("meal","80",AssetCategory.PREPAID).copy(subtype="饭卡")
        val l=ledger(meal).copy(plans=listOf(api(),api().copy(id="phone",name="话费",usageBalance=null,balanceAccount=BalanceAccount(balance="50",asOf=date))))
        assertEquals(listOf(meal),Assets.accounts(l));money("80",Assets.totals(l).assets)
        assertEquals(l.assets,Assets.observe(ledger(meal),l).assets)
    }
    @Test fun legacyPlanReferencesStayInStorageAndBackupButNeverEnterAssetTotals() {
        val oldReference=account("plan:api","100",AssetCategory.PREPAID).copy(planId="api",eligibleAmount="40",eligibleBasisBalance="100",eligibleFromPlatform=true)
        val meal=account("meal","80",AssetCategory.PREPAID)
        val l=ledger(oldReference,meal).copy(plans=listOf(api()),payments=listOf(Payment(planId="api",planName="DeepSeek",amount="100",currency="CNY",date=date)))
        assertEquals(listOf(meal),Assets.accounts(l));money("80",Assets.totals(l).assets)
        money("80",Assets.totals(l,l.assets.accounts).assets);assertEquals(0,Assets.totals(l,l.assets.accounts).pending)
        assertEquals(l,Book.decode(Book.encode(l)).data)
        val refreshed=Assets.recordPlatformSnapshot(l,"api",DeepSeekBalanceSnapshot("CNY","100","60","40",Instant.parse("2024-03-01T08:00:00Z")))
        assertEquals(l,refreshed)
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
    @Test fun missingOrConvertedPlanReferenceStaysHiddenAndDoesNotCreatePendingBalance() {
        val reference=account("plan:api","100",AssetCategory.PREPAID).copy(planId="api")
        for(plans in listOf(emptyList(),listOf(api().copy(usageBalance=null)))) {
            val l=ledger(reference).copy(plans=plans)
            assertTrue(Assets.accounts(l).isEmpty());assertEquals(0,Assets.totals(l).pending)
            money("0",Assets.totals(l).net);assertEquals(listOf(reference),l.assets.accounts)
        }
    }
    @Test fun manualCurrencyRateIsExplicitAndUnrelatedRatesDoNotChangeNetWorth() {
        val foreign=account().copy(currency="USD",balance="100")
        val l=ledger(foreign).copy(settings=Settings(rates=mapOf("USD" to "7")))
        assertEquals(1,Assets.totals(l).pending)
        money("720",Assets.totals(Assets.put(l,foreign.copy(cnyRate="7.2"))).assets)
    }
    @Test fun hiddenBorrowingIsExcludedWithoutHidingCreditCardsDepositsOrOtherBalances() {
        val borrowed=account("borrowed","300",AssetCategory.DEBT).copy(subtype="借入款")
        val lent=account("lent","200",AssetCategory.RECEIVABLE).copy(subtype="借出款")
        val uncertain=borrowed.copy(id="uncertain",verified=false)
        val credit=borrowed.copy(id="credit",subtype="信用卡",balance="50")
        val deposit=lent.copy(id="deposit",subtype="可退押金",balance="100")
        val tuition=account("tuition","80",AssetCategory.TUITION)
        val l=ledger(account(),borrowed,lent,uncertain,credit,deposit,tuition)
        assertEquals(setOf("borrowed","lent","uncertain"),l.assets.accounts.filter(Assets::isBorrowing).map {it.id}.toSet())
        money("5300",Assets.totals(l).assets);money("430",Assets.totals(l).liabilities);assertEquals(1,Assets.totals(l).pending)
        val hidden=l.copy(assets=l.assets.copy(showBorrowing=false))
        money("5100",Assets.totals(hidden).assets);money("130",Assets.totals(hidden).liabilities)
        money("4970",Assets.totals(hidden).net);assertEquals(0,Assets.totals(hidden).pending)
        assertEquals(l.assets.accounts,hidden.assets.accounts)
        assertEquals(Assets.totals(hidden),Assets.totals(hidden,hidden.assets.accounts))
    }
    @Test fun borrowingVisibilityChangesScopeAndHiddenEditsDoNotCreateNetWorthRecords() {
        val borrowed=account("borrowed","300",AssetCategory.DEBT).copy(subtype="借入款")
        val lent=account("lent","200",AssetCategory.RECEIVABLE).copy(subtype="借出款")
        val excluded=lent.copy(id="excluded",balance="900",included=false)
        val l=Assets.observe(Ledger(),ledger(account(),borrowed,lent,excluded))
        val hidden=Assets.observe(l,l.copy(assets=l.assets.copy(showBorrowing=false)))
        assertEquals(l.assets.history,hidden.assets.history.dropLast(1));assertEquals("统计范围调整",hidden.assets.history.last().reason)
        val changed=Assets.observe(hidden,Assets.edit(hidden,borrowed,borrowed.copy(balance="350")))
        assertEquals(hidden.assets.history,changed.assets.history);money("5000",Assets.totals(changed).net)
        assertEquals("350",changed.assets.accounts.first {it.id==borrowed.id}.history.single().balance)
        val shown=Assets.observe(changed,changed.copy(assets=changed.assets.copy(showBorrowing=true)))
        assertEquals("统计范围调整",shown.assets.history.last().reason);assertEquals(changed.assets.history,shown.assets.history.dropLast(1))
        money("5200",Assets.totals(shown).assets);money("350",Assets.totals(shown).liabilities);money("4850",Assets.totals(shown).net)
        assertFalse(shown.assets.accounts.first {it.id==excluded.id}.included)
        val noBorrowing=ledger(account())
        assertTrue(Assets.observe(noBorrowing,noBorrowing.copy(assets=noBorrowing.assets.copy(showBorrowing=false))).assets.history.isEmpty())
    }
    @Test fun borrowingVisibilityPersistsAndOldBackupsDefaultToVisible() {
        val l=Ledger(assets=AssetBook(accounts=listOf(account()),showBorrowing=false))
        assertEquals(l,Book.decode(Book.encode(l)).data)
        val emptyHidden=Ledger(assets=AssetBook(showBorrowing=false))
        assertEquals(emptyHidden,Book.decode(Book.encode(emptyHidden)).data)
        val root=Book.json.parseToJsonElement(Book.encode(l)).jsonObject
        val data=root.getValue("data").jsonObject
        val assets=JsonObject(data.getValue("assets").jsonObject-"showBorrowing")
        val legacy=JsonObject(root+("data" to JsonObject(data+("assets" to assets))))
        assertEquals(l.copy(assets=l.assets.copy(showBorrowing=true)),Book.decode(legacy.toString()).data)
    }
    @Test fun legacyArchivedAccountsAreVisibleWithoutChangingStoredFieldsOrTotals() {
        val a=account();val b=account("other","100")
        val l=ledger(a,b);val saved=Assets.put(l,a.copy(archived=true))
        assertEquals(listOf("cash","other"),saved.assets.accounts.map {it.id});money("5100",Assets.totals(saved).net)
        assertEquals(saved,Assets.observe(l,saved))
        assertTrue(saved.assets.accounts.first().archived);assertFalse(Assets.accounts(saved).first().archived)
        assertEquals(listOf("cash","other"),Assets.accounts(saved).map {it.id})
    }
    @Test fun deletingAccountRemovesItsBalanceHistoryAndPreservesOtherDataAndNetWorthHistory() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="5000",date=date)))
        val other=account("other","100")
        val l=Assets.observe(Ledger(),ledger(a,other)).copy(plans=listOf(api()),payments=listOf(Payment(planId="api",planName="DeepSeek",amount="10",currency="CNY",date=date)))
        val removed=Assets.remove(l,a.id)
        assertEquals(listOf(other),removed.assets.accounts);assertEquals(l.assets.history,removed.assets.history)
        assertEquals(l.plans,removed.plans);assertEquals(l.payments,removed.payments)
        val observed=Assets.observe(l,removed)
        assertEquals(l.assets.history,observed.assets.history.dropLast(1));assertEquals("统计范围调整",observed.assets.history.last().reason)
        money("100",Assets.totals(observed).net);assertEquals(observed,Assets.remove(observed,a.id))
    }
    @Test fun deletingExcludedAccountDoesNotAddNetWorthHistoryOrRemoveHiddenPlanReferences() {
        val excluded=account().copy(included=false)
        val reference=account("plan:api","100",AssetCategory.PREPAID).copy(planId="api")
        val l=ledger(excluded,reference).copy(plans=listOf(api()))
        val removed=Assets.observe(l,Assets.remove(l,excluded.id))
        assertEquals(listOf(reference),removed.assets.accounts);assertEquals(l.assets.history,removed.assets.history)
        assertThrows(IllegalArgumentException::class.java) {Assets.remove(l,reference.id)}
    }
    @Test fun deletedTuitionStudentCanBeSelectedAgainOnTheNextSync() {
        val snapshot=snapshot(students=arrayOf(student(),student("s2",10000)))
        val l=Assets.importTuition(Ledger(),snapshot,setOf("s1","s2"),"2024-03-01T08:01:00Z")
        val removed=Assets.remove(l,l.assets.accounts.first {it.studentId=="s1"}.id)
        assertEquals(listOf("s1"),Assets.newTuitionStudents(removed,snapshot).map {it.id})
        assertEquals(l.assets.tuitionPublishedAt,removed.assets.tuitionPublishedAt);assertEquals(l.assets.tuitionFetchedAt,removed.assets.tuitionFetchedAt)
        val skipped=Assets.importTuition(removed,snapshot,emptySet(),"2024-03-01T08:02:00Z")
        assertEquals(listOf("s2"),skipped.assets.accounts.map {it.studentId})
        val restored=Assets.importTuition(skipped,snapshot,setOf("s1"),"2024-03-01T08:03:00Z")
        assertEquals(setOf("s1","s2"),restored.assets.accounts.map {it.studentId}.toSet())
        assertEquals(l.assets.accounts.first {it.studentId=="s2"},restored.assets.accounts.first {it.studentId=="s2"})
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
        val updated=Assets.observe(l,Assets.edit(l,a,a.copy(balance="5200",asOf=date)))
        assertEquals(2,updated.assets.history.size);assertEquals(2,updated.assets.accounts.single().history.size)
        val previous=updated.assets.accounts.single()
        val corrected=Assets.observe(updated,Assets.edit(updated,previous,previous.copy(balance="5100",asOf=date),correction=true))
        assertEquals(2,corrected.assets.accounts.single().history.size);assertEquals("余额校正",corrected.assets.history.last().reason)
        val excluded=Assets.put(corrected,corrected.assets.accounts.single().copy(included=false))
        val excludedAccount=excluded.assets.accounts.single()
        val changedExcluded=Assets.edit(excluded,excludedAccount,excludedAccount.copy(balance="9000",asOf=date))
        assertEquals(excluded.assets.history,Assets.observe(excluded,changedExcluded).assets.history)
    }
    @Test fun unifiedEditChangesBalanceAndScopeTogetherWithoutCreatingSpending() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="5000",date=date)))
        val l=ledger(a).copy(payments=listOf(Payment(planId="old",planName="历史支出",amount="30",currency="CNY",date=date)))
        val edited=Assets.observe(l,Assets.edit(l,a,a.copy(name="借款",category=AssetCategory.DEBT,subtype="借入款",balance="300",note="调整")))
        val saved=edited.assets.accounts.single()
        assertEquals("借款",saved.name);assertEquals("借入款",saved.subtype);assertEquals("调整",saved.note)
        assertEquals(2,saved.history.size);assertEquals("300",saved.history.last().balance)
        money("-300",Assets.totals(edited).net);assertEquals(1,edited.assets.history.size)
        assertEquals("统计范围调整",edited.assets.history.single().reason);assertEquals(l.payments,edited.payments)
    }
    @Test fun unchangedOrMetadataOnlyEditsDoNotAddBalanceHistory() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="5000",date=date)))
        val l=ledger(a)
        assertEquals(l,Assets.edit(l,a,a));assertEquals(l,Assets.edit(l,a,a.copy(balance="5000.00")))
        val changed=Assets.observe(l,Assets.edit(l,a,a.copy(name="我的钱包",note="备注")))
        assertEquals(a.history,changed.assets.accounts.single().history);assertEquals(l.assets.history,changed.assets.history)
        assertEquals("我的钱包",changed.assets.accounts.single().name);assertEquals("备注",changed.assets.accounts.single().note)
        val excluded=Assets.observe(l,Assets.edit(l,a,a.copy(included=false)))
        assertEquals(a.history,excluded.assets.accounts.single().history);money("0",Assets.totals(excluded).net)
    }
    @Test fun currencyEditFreezesLegacyHistoryCurrencyAndUsesTheNewRate() {
        val a=account(balance="100").copy(history=listOf(AssetBalanceRecord(balance="80",date="2024-02-29"),AssetBalanceRecord(balance="100",date=date)))
        val l=ledger(a)
        val edited=Assets.edit(l,a,a.copy(currency="USD",cnyRate="7.2"))
        val saved=edited.assets.accounts.single()
        assertEquals(listOf("CNY","CNY","USD"),saved.history.map {it.currency});money("720",Assets.totals(edited).net)
        val again=Assets.edit(edited,saved,saved.copy(currency="EUR",cnyRate="8"))
        assertEquals(listOf("CNY","CNY","USD","EUR"),again.assets.accounts.single().history.map {it.currency})
        assertEquals(again,Book.decode(Book.encode(again)).data)
    }
    @Test fun unifiedEditRecordsDateChangesAndSupportsCorrection() {
        val a=account().copy(history=listOf(AssetBalanceRecord(balance="4000",date="2024-02-28"),AssetBalanceRecord(balance="5000",date=date)))
        val l=ledger(a)
        val dated=Assets.edit(l,a,a.copy(asOf="2024-03-02"))
        assertEquals(3,dated.assets.accounts.single().history.size);assertEquals("2024-03-02",dated.assets.accounts.single().history.last().date)
        val corrected=Assets.edit(l,a,a.copy(balance="4500",asOf="2024-02-29"),correction=true)
        assertEquals(2,corrected.assets.accounts.single().history.size);assertEquals("余额校正",corrected.assets.accounts.single().history.last().kind)
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(l,a,a.copy(asOf="2024-02-27"),correction=true)}
    }
    @Test fun tuitionLocalEditPersistsUntilANewerSnapshotAndKeepsLocalMetadata() {
        val firstSnapshot=snapshot(students=arrayOf(student(verified=false)))
        val l=Assets.importTuition(Ledger(),firstSnapshot,setOf("s1"),"2024-03-01T08:01:00Z")
        val a=l.assets.accounts.single()
        val edited=Assets.edit(l,a,a.copy(name="小明",balance="1500",note="手动核对",included=false))
        val local=edited.assets.accounts.single()
        assertTrue(local.verified);assertFalse(local.sourceMissing);assertFalse(local.sourceUnverified)
        assertEquals(a.studentId,local.studentId);assertEquals(a.sourceName,local.sourceName)
        val repeated=Assets.importTuition(edited,firstSnapshot,emptySet(),"2024-03-01T08:02:00Z")
        assertEquals(local,repeated.assets.accounts.single())
        val newer=Assets.importTuition(repeated,snapshot("2024-03-02T08:00:00Z",student(balance=100000)),emptySet(),"2024-03-02T08:01:00Z").assets.accounts.single()
        assertEquals("1000.00",newer.balance);assertEquals("小明",newer.name);assertEquals("手动核对",newer.note);assertFalse(newer.included)
        assertEquals(local.history,newer.history.dropLast(1));assertEquals("学费同步",newer.history.last().kind)
    }
    @Test fun metadataEditMergesWithNewerSyncWithoutReplacingItsBalanceDateOrHistory() {
        val l=Assets.importTuition(Ledger(),snapshot(students=arrayOf(student())),setOf("s1"),"2024-03-01T08:01:00Z")
        val original=l.assets.accounts.single()
        val synced=Assets.importTuition(l,snapshot("2024-03-02T08:00:00Z",student(balance=100000)),emptySet(),"2024-03-02T08:01:00Z")
        val latest=synced.assets.accounts.single()
        val edited=Assets.edit(synced,original,original.copy(name="本地名字",note="编辑期间同步"))
        assertEquals(latest.copy(name="本地名字",note="编辑期间同步"),edited.assets.accounts.single())
        assertEquals(synced.assets.tuitionPublishedAt,edited.assets.tuitionPublishedAt)
        assertEquals(synced,Assets.edit(synced,original,original))
    }
    @Test fun tuitionMetadataDoesNotConfirmAnUnverifiedBalanceAndBalanceEditClearsWarnings() {
        val a=account(category=AssetCategory.TUITION).copy(studentId="s1",verified=false,sourceMissing=true,sourceUnverified=true)
        val l=ledger(a)
        val metadata=Assets.edit(l,a,a.copy(note="仍待确认")).assets.accounts.single()
        assertFalse(metadata.verified);assertTrue(metadata.sourceMissing);assertTrue(metadata.sourceUnverified)
        val changed=Assets.edit(l,a,a.copy(balance="100")).assets.accounts.single()
        assertTrue(changed.verified);assertFalse(changed.sourceMissing);assertFalse(changed.sourceUnverified)
    }
    @Test fun editRejectsDeletedAccountsSourceChangesAndInvalidTuitionCurrency() {
        val a=account();val l=ledger(a)
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(Assets.remove(l,a.id),a,a.copy(name="不可复活"))}
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(l,a,a.copy(studentId="changed"))}
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(l,a,a.copy(balance="NaN"))}
        val tuition=a.copy(category=AssetCategory.TUITION,studentId="s1")
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(ledger(tuition),tuition,tuition.copy(currency="USD"))}
        assertThrows(IllegalArgumentException::class.java) {Assets.edit(ledger(tuition),tuition,tuition.copy(category=AssetCategory.CASH))}
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
