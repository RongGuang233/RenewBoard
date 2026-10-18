package cn.renewboard

/** The common mainland banks offered when adding a bank account. */
internal val commonAssetBanks = listOf(
    "中国农业银行", "中国工商银行", "中国建设银行", "中国银行", "交通银行", "招商银行",
    "中国邮政储蓄银行", "兴业银行", "中信银行", "浦发银行", "平安银行", "民生银行", "光大银行"
)

private data class AssetBrand(val name: String, val icon: Int, val aliases: List<String>)

private val assetBrands = listOf(
    AssetBrand("中国农业银行", R.drawable.asset_abc, listOf("农业银行", "农业", "农行", "ABC")),
    AssetBrand("中国工商银行", R.drawable.asset_icbc, listOf("工商银行", "工商", "工行", "ICBC")),
    AssetBrand("中国建设银行", R.drawable.asset_ccb, listOf("建设银行", "建设", "建行", "CCB")),
    AssetBrand("中国银行", R.drawable.asset_boc, listOf("中行", "BOC")),
    AssetBrand("交通银行", R.drawable.asset_bocom, listOf("交通", "交行", "BOCOM")),
    AssetBrand("招商银行", R.drawable.asset_cmb, listOf("招商", "招行", "CMB")),
    AssetBrand("中国邮政储蓄银行", R.drawable.asset_psbc, listOf("邮政储蓄银行", "邮储银行", "邮储", "PSBC")),
    AssetBrand("兴业银行", R.drawable.asset_cib, listOf("兴业", "CIB")),
    AssetBrand("中信银行", R.drawable.asset_citic, listOf("中信", "CITIC")),
    AssetBrand("浦发银行", R.drawable.asset_spdb, listOf("上海浦东发展银行", "浦发", "SPDB")),
    AssetBrand("平安银行", R.drawable.asset_pingan, listOf("平安口袋银行")),
    AssetBrand("民生银行", R.drawable.asset_cmbc, listOf("中国民生银行", "民生", "CMBC")),
    AssetBrand("光大银行", R.drawable.asset_ceb, listOf("中国光大银行", "光大", "CEB")),
    AssetBrand("微信", R.drawable.asset_wechat, listOf("微信零钱", "微信支付", "WeChat", "Weixin")),
    AssetBrand("支付宝", R.drawable.asset_alipay, listOf("支付宝余额", "Alipay"))
)

private fun assetBrand(value: String): AssetBrand? {
    val text = value.trim()
    if (text.isEmpty()) return null
    // Prefer full names before abbreviations. In particular, BOC must not match BOCOM.
    assetBrands.firstOrNull { text.contains(it.name, ignoreCase = true) }?.let { return it }
    return assetBrands.firstOrNull { brand ->
        brand.aliases.any { alias ->
            if (alias.all { it.isLetter() && it.code < 128 }) {
                Regex("(?i)(?<![A-Z])${Regex.escape(alias)}(?![A-Z])").containsMatchIn(text)
            } else text.contains(alias)
        }
    }
}

/** Resolve old account labels without rewriting the user's saved account name. */
internal fun canonicalAssetType(type: String, name: String = ""): String {
    // These products share Alipay's icon but remain separate account types.
    if (type in listOf("余额宝", "花呗")) return type
    if (type == "其他预付") return "其他充值"
    return assetBrand(type)?.name ?: assetBrand(name)?.name ?: type
}

/** Official offline app icons; non-brand types keep their semantic category icon. */
internal fun assetBrandIcon(type: String, name: String = ""): Int? {
    if (listOf(type, name).any { "余额宝" in it || "花呗" in it }) return R.drawable.asset_alipay
    return assetBrand(type)?.icon ?: assetBrand(name)?.icon
}
