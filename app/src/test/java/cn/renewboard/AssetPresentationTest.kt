package cn.renewboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AssetPresentationTest {
    @Test fun borrowingSummaryIncludesLoansAndKeepsCurrenciesSeparate() {
        val accounts=listOf(
            AssetAccount(name="借入款",category=AssetCategory.DEBT,subtype="借入款",balance="100"),
            AssetAccount(name="不计入的借入",category=AssetCategory.DEBT,subtype="借入款",balance="50",included=false),
            AssetAccount(name="美元借入",category=AssetCategory.DEBT,subtype="借入款",balance="12",currency="USD"),
            AssetAccount(name="已归档借入",category=AssetCategory.DEBT,subtype="借入款",balance="500",archived=true),
            AssetAccount(name="信用卡",category=AssetCategory.DEBT,subtype="信用卡",balance="900"),
            AssetAccount(name="贷款",category=AssetCategory.DEBT,subtype="贷款",balance="1000"),
            AssetAccount(name="借出款",category=AssetCategory.RECEIVABLE,subtype="借出款",balance="200"),
            AssetAccount(name="押金",category=AssetCategory.RECEIVABLE,subtype="可退押金",balance="400"),
            AssetAccount(name="待上课",category=AssetCategory.TUITION,subtype="借入款",balance="600"))
        assertEquals(BorrowingBalances("¥650.00\nUSD 12.00","¥200.00"),assetBorrowingBalances(accounts))
    }

    @Test fun borrowingSummaryWithoutLoansShowsZeroInBothDirections() {
        assertEquals(BorrowingBalances("¥0.00","¥0.00"),assetBorrowingBalances(emptyList()))
    }

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
        assertEquals("待上课",tuitionBalanceLabel("20"))
        assertEquals("待收款",tuitionBalanceLabel("-20"))
        assertEquals("已结清",tuitionBalanceLabel("0.00"))
    }

    @Test fun oldBankAndAppNamesResolveToTheirSelectableTypesWithoutAmbiguousBankCodes() {
        assertEquals("其他充值",canonicalAssetType("其他预付"))
        assertNotEquals(canonicalAssetType("余额宝"),canonicalAssetType("支付宝"))
        assertNotEquals(canonicalAssetType("花呗"),canonicalAssetType("支付宝"))
        assertEquals("招商银行",canonicalAssetType("银行卡","招商银行工资卡"))
        assertEquals("中国农业银行",canonicalAssetType("银行卡","农行储蓄卡"))
        assertEquals("微信",canonicalAssetType("其他","微信零钱"))
        assertEquals("交通银行",canonicalAssetType("BOCOM"))
        assertEquals("中国银行",canonicalAssetType("BOC"))
        assertNotEquals(canonicalAssetType("BOC"),canonicalAssetType("BOCOM"))
    }

    @Test fun allSelectableBanksAndPaymentAppsHaveBrandIconsWhileCashKeepsSemanticIcon() {
        (commonAssetBanks+listOf("微信","支付宝")).forEach {assertNotNull("Missing brand icon: $it",assetBrandIcon(it))}
        assertNotEquals(assetBrandIcon("微信"),assetBrandIcon("支付宝"))
        assertNotEquals(assetBrandIcon("BOC"),assetBrandIcon("BOCOM"))
        assertNull(assetBrandIcon("现金"))
    }
}
