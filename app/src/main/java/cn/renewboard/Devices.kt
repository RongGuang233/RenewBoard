package cn.renewboard

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable enum class DeviceStatus(val label: String) { ACTIVE("服役中"), RETIRED("已退役"), SOLD("已卖出"), WISHLIST("待购买") }
@Serializable enum class DeviceCategory(val label: String) {
    PHONE("手机"), COMPUTER("电脑"), TABLET("平板"), HEADPHONES("耳机"), MONITOR("显示屏"),
    WATCH("手表"), KEYBOARD("键盘"), MOUSE("鼠标"), CONTROLLER("手柄"), EREADER("电纸书"), CHAIR("椅子"),
    AUDIO("耳机音箱"), CAMERA("相机"), GAMING("游戏设备"), ROUTER("路由器"), OTHER("其他")
}
@Serializable data class Device(
    val id: String = newId(), val name: String, val category: DeviceCategory = DeviceCategory.OTHER,
    val status: DeviceStatus = DeviceStatus.ACTIVE, val purchaseAmount: String,
    val startDate: String? = null, val endDate: String? = null, val saleAmount: String? = null, val note: String = ""
)

object Devices {
    fun validate(device: Device, today: LocalDate = LocalDate.now()) {
        require(device.id.isNotBlank() && device.name.isNotBlank() && device.name.length <= 100) { "请填写设备名称（最多100字）" }
        fun money(value: String) { require(value.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,4})?"))) { "金额格式错误（最多4位小数）" } }
        fun date(value: String): LocalDate = LocalDate.parse(value).also { require(it.year in 1900..2200) { "日期应在1900至2200年" } }
        money(device.purchaseAmount)
        val start = device.startDate?.let(::date)
        val end = device.endDate?.let(::date)
        if (device.status != DeviceStatus.WISHLIST) {
            require(start != null && start <= today) { "服役日期不能为空或晚于今天" }
            if (device.status == DeviceStatus.ACTIVE) require(end == null) { "服役中的设备无需结束日期" }
            else require(end != null && end >= start && end <= today) { "结束日期应在服役日期至今天之间" }
        } else require(end == null) { "待购买设备无需结束日期" }
        if (device.status == DeviceStatus.SOLD) {
            require(device.saleAmount != null) { "请填写卖出金额" }
            money(device.saleAmount)
        } else require(device.saleAmount == null) { "仅已卖出设备填写卖出金额" }
    }
    fun serviceDays(device: Device, today: LocalDate = LocalDate.now()): Long? {
        if (device.status == DeviceStatus.WISHLIST) return null
        val start = device.startDate?.let(LocalDate::parse) ?: return null
        val end = if (device.status == DeviceStatus.ACTIVE) today else device.endDate?.let(LocalDate::parse) ?: return null
        return (ChronoUnit.DAYS.between(start, end) + 1).takeIf { it > 0 }
    }
    fun dailyCost(device: Device, today: LocalDate = LocalDate.now()): BigDecimal? =
        serviceDays(device, today)?.let { BigDecimal(device.purchaseAmount).divide(BigDecimal(it), 2, RoundingMode.HALF_UP) }
}
