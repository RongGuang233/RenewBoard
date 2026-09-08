package cn.renewboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Local display presets only: prices and billing periods are always entered by the user. */
data class ServicePreset(
    val name: String,
    val category: String,
    val currency: String = "CNY",
    val aliases: List<String> = emptyList(),
    val iconRes: Int? = null,
)

val servicePresets = listOf(
    ServicePreset("百度网盘", "网盘存储", "CNY", listOf("百度云", "baidu"), R.drawable.service_baidu),
    ServicePreset("阿里云盘", "网盘存储", "CNY", listOf("阿里网盘", "aliyun"), R.drawable.service_aliyun),
    ServicePreset("夸克网盘", "网盘存储", "CNY", listOf("夸克", "quark"), R.drawable.service_quark),
    ServicePreset("115网盘", "网盘存储", "CNY", listOf("115生活", "115"), R.drawable.service_115),
    ServicePreset("天翼云盘", "网盘存储", "CNY", listOf("天翼网盘", "tianyi"), R.drawable.service_tianyi),
    ServicePreset("中国移动云盘", "网盘存储", "CNY", listOf("和彩云", "移动云盘", "cmcloud"), R.drawable.service_cmcloud),
    ServicePreset("腾讯微云", "网盘存储", "CNY", listOf("微云", "weiyun"), R.drawable.service_weiyun),
    ServicePreset("坚果云", "网盘存储", "CNY", listOf("nutstore", "jianguo"), R.drawable.service_jianguo),
    ServicePreset("迅雷网盘", "网盘存储", "CNY", listOf("迅雷", "xunlei"), R.drawable.service_xunlei),
    ServicePreset("OneDrive", "网盘存储", "CNY", listOf("微软网盘", "microsoft 365"), R.drawable.service_onedrive),
    ServicePreset("Google One", "网盘存储", "USD", listOf("谷歌网盘", "google drive", "googleone"), R.drawable.service_googleone),
    ServicePreset("Dropbox", "网盘存储", "USD", listOf(), R.drawable.service_dropbox),
    ServicePreset("iCloud+", "网盘存储", "CNY", listOf("苹果云", "icloud"), R.drawable.service_icloud),
    ServicePreset("MEGA", "网盘存储", "USD", listOf(), R.drawable.service_mega),
    ServicePreset("Box", "网盘存储", "USD", listOf(), R.drawable.service_box),
    ServicePreset("pCloud", "网盘存储", "USD", listOf(), R.drawable.service_pcloud),
    ServicePreset("ChatGPT", "AI 工具", "USD", listOf("openai", "gpt plus", "gpt pro"), R.drawable.service_chatgpt),
    ServicePreset("Claude", "AI 工具", "USD", listOf("克劳德", "Anthropic"), R.drawable.service_claude),
    ServicePreset("Gemini", "AI 工具", "USD", listOf(), R.drawable.service_gemini),
    ServicePreset("Perplexity", "AI 工具", "USD", listOf(), R.drawable.service_perplexity),
    ServicePreset("Microsoft Copilot", "AI 工具", "USD", listOf("copilot"), R.drawable.service_copilot),
    ServicePreset("Kimi", "AI 工具", "CNY", listOf("月之暗面"), R.drawable.service_kimi),
    ServicePreset("Grok", "AI 工具", "USD", listOf(), R.drawable.service_grok),
    ServicePreset("Poe", "AI 工具", "USD", listOf(), R.drawable.service_poe),
    ServicePreset("即梦 AI", "AI 工具", "CNY", listOf("即梦", "jimeng"), R.drawable.service_jimeng),
    ServicePreset("可灵 AI", "AI 工具", "CNY", listOf("可灵", "kling"), R.drawable.service_kling),
    ServicePreset("Suno", "AI 工具", "USD", listOf(), R.drawable.service_suno),
    ServicePreset("UU加速器", "游戏加速", "CNY", listOf("网易UU", "uu加速", "uu"), R.drawable.service_uu),
    ServicePreset("UU主机加速器", "游戏加速", "CNY", listOf("uuconsole"), R.drawable.service_uuconsole),
    ServicePreset("小黑盒加速器", "游戏加速", "CNY", listOf("小黑盒", "黑盒", "heybox", "heihe"), R.drawable.service_heihe),
    ServicePreset("雷神加速器", "游戏加速", "CNY", listOf("雷神", "leishen"), R.drawable.service_leishen),
    ServicePreset("迅游加速器", "游戏加速", "CNY", listOf("迅游", "xunyou"), R.drawable.service_xunyou),
    ServicePreset("奇游加速器", "游戏加速", "CNY", listOf("奇游", "qiyou"), R.drawable.service_qiyou),
    ServicePreset("biubiu加速器", "游戏加速", "CNY", listOf("biubiu"), R.drawable.service_biubiu),
    ServicePreset("哔哩哔哩大会员", "影音阅读", "CNY", listOf("哔哩哔哩", "b站", "哔站", "bilibili"), R.drawable.service_bilibili),
    ServicePreset("哔哩哔哩充电", "影音阅读", "CNY", listOf("b站充电", "哔站充电", "充电", "bilibili"), R.drawable.service_bilibili),
    ServicePreset("腾讯视频", "影音阅读", "CNY", listOf("wetv", "tencent"), R.drawable.service_tencent),
    ServicePreset("爱奇艺", "影音阅读", "CNY", listOf("iqiyi"), R.drawable.service_iqiyi),
    ServicePreset("优酷视频", "影音阅读", "CNY", listOf("优酷", "youku"), R.drawable.service_youku),
    ServicePreset("芒果TV", "影音阅读", "CNY", listOf("芒果", "mango"), R.drawable.service_mango),
    ServicePreset("YouTube Premium", "影音阅读", "USD", listOf("油管", "youtube"), R.drawable.service_youtube),
    ServicePreset("Netflix", "影音阅读", "USD", listOf("奈飞", "网飞"), R.drawable.service_netflix),
    ServicePreset("Disney+", "影音阅读", "USD", listOf("disney", "迪士尼"), R.drawable.service_disney),
    ServicePreset("Prime Video", "影音阅读", "USD", listOf("prime", "亚马逊视频", "amazon prime"), R.drawable.service_prime),
    ServicePreset("Apple TV+", "影音阅读", "USD", listOf("appletv"), R.drawable.service_appletv),
    ServicePreset("网易云音乐", "影音阅读", "CNY", listOf("网易云", "云音乐", "网抑云", "netease"), R.drawable.service_netease),
    ServicePreset("QQ音乐", "影音阅读", "CNY", listOf("qq music", "qqmusic"), R.drawable.service_qqmusic),
    ServicePreset("Apple Music", "影音阅读", "CNY", listOf("苹果音乐", "applemusic"), R.drawable.service_applemusic),
    ServicePreset("Spotify", "影音阅读", "USD", listOf("声田"), R.drawable.service_spotify),
    ServicePreset("酷狗音乐", "影音阅读", "CNY", listOf("kugou"), R.drawable.service_kugou),
    ServicePreset("喜马拉雅", "影音阅读", "CNY", listOf("ximalaya"), R.drawable.service_ximalaya),
    ServicePreset("哔哩哔哩漫画", "影音阅读", "CNY", listOf("b站漫画", "bobo"), R.drawable.service_bobo),
    ServicePreset("WPS Office", "效率创作", "CNY", listOf("wps"), R.drawable.service_wps),
    ServicePreset("Notion", "效率创作", "USD", listOf(), R.drawable.service_notion),
    ServicePreset("Canva", "效率创作", "CNY", listOf("可画"), R.drawable.service_canva),
    ServicePreset("Adobe Acrobat", "效率创作", "USD", listOf("adobe"), R.drawable.service_adobe),
    ServicePreset("剪映", "效率创作", "CNY", listOf("capcut"), R.drawable.service_capcut),
    ServicePreset("哈啰单车", "出行通信", "CNY", listOf("哈啰", "哈罗", "hellobike", "hello"), R.drawable.service_hello),
    ServicePreset("中国电信", "出行通信", "CNY", listOf("电信", "天翼", "telecom"), R.drawable.service_telecom),
    ServicePreset("中国移动", "出行通信", "CNY", listOf("移动", "咪咕", "mobile"), R.drawable.service_mobile),
    ServicePreset("机场 VPN", "其他", "CNY", listOf("机场", "vpn", "梯子", "代理")),
    ServicePreset("游戏加速器（自定义）", "其他", "CNY", listOf("游戏加速器", "加速器")),
)

/** Longest matching name wins, so a Bilibili charging subscription keeps its own icon. */
fun servicePresetFor(name: String): ServicePreset? {
    val normalized = name.trim().lowercase().replace(" ", "")
    if (normalized.isEmpty()) return null
    return servicePresets.mapNotNull { preset ->
        val match = (preset.aliases + preset.name).map { it.lowercase().replace(" ", "") }
            .filter { normalized.contains(it) }.maxOfOrNull { it.length }
        match?.let { preset to it }
    }.maxByOrNull { it.second }?.first
}

/** Bundled app icons work offline; unspecified services use category symbols. */
@Composable
fun ServiceIcon(name: String, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val preset = servicePresetFor(name)
    val iconRes = preset?.iconRes
    val generic = when (preset?.name) {
        "机场 VPN" -> Icons.Rounded.Flight
        "游戏加速器（自定义）" -> Icons.Rounded.SportsEsports
        else -> Icons.Rounded.Subscriptions
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * .29f))
            .background(if (iconRes != null) Color.White else Color(0xFFE2EBFF))
            .semantics { contentDescription = "${preset?.name ?: name}图标" },
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes), contentDescription = null,
                modifier = Modifier.fillMaxSize(.9f), contentScale = ContentScale.Fit,
            )
        } else {
            Icon(generic, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(size * .61f))
        }
    }
}
