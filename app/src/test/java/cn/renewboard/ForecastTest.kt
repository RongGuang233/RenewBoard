package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ForecastTest {
    private fun date(value:String)=LocalDate.parse(value)
    private fun money(expected:String,actual:BigDecimal?)=assertEquals(0,BigDecimal(expected).compareTo(requireNotNull(actual)))
    private val monthly=Plan(id="monthly",name="月末会员",amount="30",billingAnchor="2024-01-31")

    @Test fun eventsKeepMonthEndAnchorAndExcludeEndDate() {
        val l=Ledger(plans=listOf(monthly))
        val charges=Book.forecastCharges(l,date("2024-02-29"),date("2024-05-31"))
        assertEquals(listOf("2024-02-29","2024-03-31","2024-04-30"),charges.map {it.date.toString()})
        assertTrue(charges.all {it.planId==monthly.id && it.planName==monthly.name && it.currency=="CNY"})
        money("90",charges.fold(BigDecimal.ZERO) {sum,it -> sum+it.cnyAmount!!})
        assertTrue(Book.forecastCharges(l,date("2024-05-31"),date("2024-05-31")).isEmpty())
    }

    @Test fun countRepresentsChargesRatherThanSubscriptions() {
        val weekly=monthly.copy(id="weekly",cycle=Cycle.WEEK,billingAnchor="2024-01-01",paidCycles=0,amount="10")
        val l=Ledger(plans=listOf(weekly,weekly.copy(id="manual",autoRenew=false),weekly.copy(id="archived",archived=true),
            weekly.copy(id="free",amount="0"),monthly.copy(id="future",paidCycles=12)))
        val charges=Book.forecastCharges(l,date("2024-01-01"),date("2024-01-31"))
        assertEquals(5,charges.size)
        assertEquals(setOf("weekly"),charges.map {it.planId}.toSet())
        money("50",Book.forecastSummary(l,date("2024-01-01"),date("2024-01-31")).known)
    }

    @Test fun phoneChargesUseUnsettledDatesAndScheduledMonthlyFee() {
        val phone=monthly.copy(id="phone",name="中国移动",amount="30",billingAnchor="2024-01-31",
            balanceAccount=BalanceAccount("100","2024-02-29",MonthlyFeeChange("50","2024-04-01")))
        val l=Ledger(plans=listOf(phone))
        val charges=Book.forecastCharges(l,date("2024-02-29"),date("2024-05-01"))
        assertEquals(listOf("2024-03-31","2024-04-30"),charges.map {it.date.toString()})
        money("30",charges[0].amount);money("50",charges[1].amount)
        money("80",Book.forecast(l,date("2024-02-29"),date("2024-05-01"))["CNY"])
    }

    @Test fun foreignChargesUseLastEligibleSettlementAndIgnoreRefundAndLiveRates() {
        val plan=monthly.copy(currency="USD",amount="20",billingAnchor="2024-01-01")
        val original=Payment(id="paid",planId=plan.id,planName=plan.name,amount="20",currency="USD",date="2024-01-01",cnyAmount="140")
        val l=Ledger(plans=listOf(plan),payments=listOf(original,original.copy(id="last",date="2024-02-01",cnyAmount="144"),
            original.copy(id="refund",date="2024-02-01",refundOf=original.id,cnyAmount="199"),
            original.copy(id="future",date="2024-02-02",cnyAmount="200")),settings=Settings(rates=mapOf("USD" to "99")))
        val charges=Book.forecastCharges(l,date("2024-02-01"),date("2024-04-01"))
        assertEquals(2,charges.size)
        charges.forEach {money("144",it.cnyAmount)}
        money("288",Book.forecastSummary(l,date("2024-02-01"),date("2024-04-01")).known)
    }

    @Test fun unknownForeignAmountsRemainVisibleWithoutInventedConversion() {
        val l=Ledger(plans=listOf(monthly.copy(currency="USD"),monthly.copy(id="yuan")))
        val charges=Book.forecastCharges(l,date("2024-02-01"),date("2024-04-01"))
        assertEquals(4,charges.size)
        assertEquals(2,charges.count {it.cnyAmount==null})
        val summary=Book.forecastSummary(l,date("2024-02-01"),date("2024-04-01"))
        assertEquals(listOf(monthly.id),summary.missingPlanIds)
        money("60",summary.known)
        assertNull(Book.forecastCny(l,date("2024-02-01"),date("2024-04-01")))
    }

    @Test fun repeatedFractionalConversionPreservesAggregatePrecision() {
        val plan=monthly.copy(currency="USD",amount="1",billingAnchor="2024-01-01")
        val payment=Payment(planId=plan.id,planName=plan.name,amount="3",currency="USD",date="2024-01-01",cnyAmount="1")
        val l=Ledger(plans=listOf(plan),payments=listOf(payment))
        val charges=Book.forecastCharges(l,date("2024-02-01"),date("2024-05-01"))
        money("1",charges.fold(BigDecimal.ZERO) {sum,it -> sum+it.cnyAmount!!})
        money("1",Book.forecastSummary(l,date("2024-02-01"),date("2024-05-01")).known)
    }
}
