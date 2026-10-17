package cn.renewboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class AssetPresentationTest {
    @Test fun groupBalancesIncludeExcludedBalancesWithoutMixingCurrencies() {
        val accounts=listOf(
            AssetAccount(name="饭卡",category=AssetCategory.PREPAID,balance="200"),
            AssetAccount(name="话费",category=AssetCategory.PREPAID,balance="80",included=false),
            AssetAccount(name="美元充值",category=AssetCategory.PREPAID,balance="12",currency="USD",included=false))
        assertEquals("¥280.00\nUSD 12.00",assetGroupBalances(accounts))
        assertEquals(0,Assets.totals(Ledger(assets=AssetBook(accounts=accounts))).net.compareTo(BigDecimal("200")))
    }
    @Test fun moneyGroupsDigitsAndRoundsWithoutLosingSign() {
        assertEquals("¥-1,234.57",assetMoney(BigDecimal("-1234.567")))
        assertEquals("USD 1,234.50",assetMoney(BigDecimal("1234.5"),"USD"))
    }
    @Test fun tuitionLabelsExplainBothDirectionsAndZero() {
        assertEquals("待上课预收款",tuitionBalanceLabel("20"))
        assertEquals("已上课待收款",tuitionBalanceLabel("-20"))
        assertEquals("已结清",tuitionBalanceLabel("0.00"))
    }
}
