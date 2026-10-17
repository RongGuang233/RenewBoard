package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class UsageBalancesTest {
    private val day=LocalDate.of(2024,3,1)
    private fun plan(currency:String="CNY")=Plan(id="usage",name="DeepSeek 平台余额",amount="0",currency=currency,
        autoRenew=false,billingAnchor=day.toString(),paidCycles=0,usageBalance=UsageBalance("100",day.toString()))
    private fun ledger(currency:String="CNY")=Ledger(plans=listOf(plan(currency)))
    private fun money(expected:String,actual:BigDecimal?)=assertEquals(0,BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun topUpCountsCashOnceAndUsageCalibrationDoesNotCreateAnotherExpense() {
        val topped=UsageBalances.topUp(ledger(),"usage","50",day.plusDays(1))
        money("150",UsageBalances.balance(topped.plans.single()))
        money("50",Book.paidCny(topped))
        assertEquals("平台充值",topped.payments.single().note)
        val consumed=UsageBalances.calibrate(topped,"usage","120",day.plusDays(2))
        money("120",UsageBalances.balance(consumed.plans.single()))
        assertEquals(topped.payments,consumed.payments)
        money("50",Book.paidCny(consumed))
        assertEquals(consumed,Prepaid.accrue(consumed,day.plusMonths(12)))
    }

    @Test fun historicalTopUpDoesNotRecreditAndOlderCreditNeverRewindsBalanceDate() {
        val historical=UsageBalances.topUp(ledger(),"usage","50",day.minusDays(1),affectBalance=false)
        assertEquals(ledger().plans,historical.plans)
        money("50",Book.paidCny(historical))
        val credited=UsageBalances.topUp(historical,"usage","20",day.minusDays(2))
        money("120",UsageBalances.balance(credited.plans.single()))
        assertEquals(day.toString(),credited.plans.single().usageBalance!!.asOf)
        assertEquals(day.minusDays(2).toString(),credited.payments.last().date)
        money("70",Book.paidCny(credited))
    }

    @Test fun foreignTopUpFreezesActualCnyAndRefundOrCorrectionOnlyChangesLedger() {
        val topped=UsageBalances.topUp(ledger("USD"),"usage","20",day,"144.25")
        val payment=topped.payments.single()
        money("120",UsageBalances.balance(topped.plans.single()))
        val rates=topped.copy(settings=topped.settings.copy(rates=mapOf("USD" to "99")))
        money("144.25",Book.paidCny(rates))
        val refunded=Book.recordRefund(rates,payment.id,"5",day.plusDays(1),"35")
        money("109.25",Book.paidCny(refunded))
        assertEquals(topped.plans,refunded.plans)
        val edited=Book.editPayment(refunded,payment.id,"25",day,"平台充值","180")
        money("145",Book.paidCny(edited))
        assertEquals(topped.plans,edited.plans)
        val deleted=Book.deletePayments(edited,setOf(payment.id))
        money("0",Book.paidCny(deleted))
        assertEquals(topped.plans,deleted.plans)
        assertThrows(Exception::class.java) {Book.editPayment(topped,payment.id,"20",day,"普通付款","144.25")}
        assertThrows(Exception::class.java) {UsageBalances.topUp(ledger("USD"),"usage","20",day)}
    }

    @Test fun nonPeriodicBalancesHaveNoForecastExpiryOrCostComparison() {
        val p=plan();val l=ledger()
        Book.validate(l)
        assertTrue(Book.forecastCharges(l,day,day.plusMonths(12)).isEmpty())
        assertTrue(Book.due(l,day).isEmpty())
        assertTrue(ReminderPolicy.events(l).isEmpty())
        assertTrue(Prepaid.due(l,day).isEmpty())
        assertNull(SubscriptionList.comparisonCny(l,p,true,day))
        assertNull(SubscriptionList.comparisonCny(l,p,false,day))
        money("100",SubscriptionList.originalAmount(p,day))
        assertThrows(Exception::class.java) {Book.recordPayment(l,p.id,"50",day)}
        assertThrows(Exception::class.java) {Book.renew(l,p.id,"50",day,emptySet(),"")}
    }

    @Test fun oldBackupsStayVersionOneAndNewBalancesRoundTripWithVersionTwo() {
        val ordinary=Plan(id="ordinary",name="服务器",amount="20",billingAnchor=day.toString())
        val old=Ledger(plans=listOf(ordinary),benefits=listOf(Benefit(id="benefit",planId=ordinary.id,name="服务器",anchor=day.toString())))
        val legacy=Book.encode(old)
        assertFalse(legacy.contains("usageBalance"))
        assertEquals(1,Book.decode(legacy).version)
        assertEquals(old,Book.decode(legacy).data)
        val mixed=old.copy(plans=old.plans+plan("USD"))
        val modern=Book.encode(mixed)
        assertEquals(2,Book.decode(modern).version)
        assertEquals(mixed,Book.decode(modern).data)
        assertThrows(Exception::class.java) {Book.decode(modern.replace("\"version\":2","\"version\":1"))}
        assertThrows(Exception::class.java) {Book.decode(modern.replace("\"version\":2","\"version\":99"))}
    }

    @Test fun invalidBalancesDatesAndConflictingAccountTypesAreRejected() {
        val l=ledger()
        listOf("-1","1e2","1.12345").forEach {value ->
            assertThrows(Exception::class.java) {UsageBalances.calibrate(l,"usage",value,day)}
        }
        assertThrows(Exception::class.java) {UsageBalances.calibrate(l,"usage","10",day.minusDays(1))}
        assertThrows(Exception::class.java) {UsageBalances.calibrate(l,"usage","10",LocalDate.now().plusDays(1))}
        assertThrows(Exception::class.java) {UsageBalances.topUp(l,"usage","0",day)}
        assertThrows(Exception::class.java) {UsageBalances.topUp(l,"usage","20",LocalDate.now().plusDays(1))}
        val p=plan()
        listOf(p.copy(autoRenew=true),p.copy(amount="10"),p.copy(balanceAccount=BalanceAccount("10",day.toString())),
            p.copy(usageBalance=UsageBalance("10",LocalDate.now().plusDays(1).toString()))).forEach {invalid ->
            assertThrows(Exception::class.java) {Book.validate(l.copy(plans=listOf(invalid)))}
        }
        assertThrows(Exception::class.java) {Book.validate(l.copy(benefits=listOf(Benefit(planId=p.id,name="不应出现",anchor=day.toString()))))}
        money("0",UsageBalances.balance(UsageBalances.calibrate(l,"usage","0",day).plans.single()))
    }
}
