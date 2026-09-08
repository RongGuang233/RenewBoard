package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SubscriptionListTest {
    private val today=LocalDate.of(2026,9,8)
    private fun plan(id:String,amount:String,cycle:Cycle=Cycle.MONTH,interval:Int=1,currency:String="CNY")=
        Plan(id=id,name=id,amount=amount,cycle=cycle,interval=interval,currency=currency,billingAnchor="2026-01-01")
    private fun same(expected:String,actual:BigDecimal?)=assertEquals(0,BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun monthlyComparisonRespectsYearWeekAndCycleMultiples() {
        same("20",SubscriptionList.comparisonCny(Ledger(),plan("annual","480",Cycle.YEAR,2),true,today))
        same("20",SubscriptionList.comparisonCny(Ledger(),plan("quarter","60",interval=3),true,today))
        same("26",SubscriptionList.comparisonCny(Ledger(),plan("fortnight","12",Cycle.WEEK,2),true,today))
        same("480",SubscriptionList.comparisonCny(Ledger(),plan("annual","480",Cycle.YEAR,2),false,today))
    }
    @Test fun phoneUsesMonthlyFeeEvenWhenBalanceIsNegative() {
        val p=plan("phone","39").copy(balanceAccount=BalanceAccount("-10",today.toString(),pendingFee=MonthlyFeeChange("49","2026-10-01")))
        same("39",SubscriptionList.comparisonCny(Ledger(),p,true,today))
        same("49",SubscriptionList.comparisonCny(Ledger(),p,true,today.plusMonths(1)))
    }
    @Test fun foreignUsesLatestEligibleActualPaymentAndNeverChangesHistory() {
        val p=plan("ai","40",currency="USD")
        val paid=Payment(id="paid",planId=p.id,planName=p.name,amount="20",currency="USD",date="2026-09-01",cnyAmount="140")
        val records=listOf(paid.copy(id="old",date="2026-08-01",cnyAmount="130"),paid,
            paid.copy(id="future",date="2026-10-01",cnyAmount="999"),
            paid.copy(id="refund",date=today.toString(),amount="1",cnyAmount="1",refundOf=paid.id),
            paid.copy(id="wrong-currency",currency="EUR",date=today.toString(),cnyAmount="999"),
            paid.copy(id="zero",date=today.toString(),amount="0",cnyAmount="999"))
        val l=Ledger(plans=listOf(p),payments=records)
        same("280",SubscriptionList.comparisonCny(l,p,true,today))
        same("280",SubscriptionList.comparisonCny(l.copy(settings=l.settings.copy(rates=mapOf("USD" to "99"))),p,true,today))
        assertEquals(records,l.payments)
    }
    @Test fun unknownCurrencyBasisSortsLastAndAnnualCostDoesNotDominateMonthly() {
        val annual=plan("annual","148",Cycle.YEAR).copy(autoRenew=false)
        val monthly=plan("monthly","45")
        val missing=plan("missing","999",currency="USD")
        val l=Ledger(plans=listOf(annual,missing,monthly))
        assertEquals(listOf("monthly","annual","missing"),SubscriptionList.sortByCost(l,l.plans,true,today).map {it.id})
        assertEquals(listOf("annual","monthly","missing"),SubscriptionList.sortByCost(l,l.plans,false,today).map {it.id})
        assertNull(SubscriptionList.comparisonCny(l,missing,true,today))
    }
    @Test fun benefitSearchMatchesOnlyItsParentWithoutDuplicatingResults() {
        val bundle=plan("联合套餐","50")
        val other=plan("其他套餐","30")
        val l=Ledger(plans=listOf(bundle,other),benefits=listOf(
            Benefit(planId=bundle.id,name="百度网盘",anchor=today.toString()),
            Benefit(planId=bundle.id,name="ChatGPT Plus",anchor=today.toString()),
            Benefit(planId=bundle.id,name="百度网盘",anchor=today.toString())))
        assertTrue(SubscriptionList.matches(l,bundle," 百度 "))
        assertTrue(SubscriptionList.matches(l,bundle,"chatgpt"))
        assertTrue(SubscriptionList.matches(l,bundle,"联合"))
        assertFalse(SubscriptionList.matches(l,other,"百度"))
        assertEquals(listOf("百度网盘"),SubscriptionList.matchingBenefits(l,bundle,"百度"))
        assertTrue(SubscriptionList.matchingBenefits(l,bundle," ").isEmpty())
    }
}
