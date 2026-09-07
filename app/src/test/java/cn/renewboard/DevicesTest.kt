package cn.renewboard

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DevicesTest {
    private val today=LocalDate.of(2026,9,7)
    private fun device()=Device(id="phone",name="手机",category=DeviceCategory.PHONE,purchaseAmount="3000",startDate="2026-09-01")
    @Test fun activeServiceIncludesFirstDayAndDailyCostUsesPurchasePrice() {
        val d=device()
        assertEquals(1L,Devices.serviceDays(d,LocalDate.of(2026,9,1)))
        assertEquals(7L,Devices.serviceDays(d,today))
        assertEquals(BigDecimal("428.57"),Devices.dailyCost(d,today))
    }
    @Test fun retiredAndSoldServiceDaysFreezeAtEndRegardlessOfSaleProceeds() {
        val retired=device().copy(status=DeviceStatus.RETIRED,endDate="2026-09-03")
        val sold=retired.copy(status=DeviceStatus.SOLD,saleAmount="2000")
        listOf(retired,sold).forEach { d ->
            Devices.validate(d,today)
            assertEquals(3L,Devices.serviceDays(d,today.plusYears(1)))
            assertEquals(BigDecimal("1000.00"),Devices.dailyCost(d,today.plusYears(1)))
        }
    }
    @Test fun wishlistAllowsNoDateAndNeverContributesServiceStatistics() {
        val d=device().copy(status=DeviceStatus.WISHLIST,startDate=null)
        Devices.validate(d,today)
        assertNull(Devices.serviceDays(d,today)); assertNull(Devices.dailyCost(d,today))
        Devices.validate(d.copy(startDate="2027-01-01"),today)
    }
    @Test fun invalidDateMoneyAndStatusCombinationsAreRejected() {
        listOf(device().copy(name=" "),device().copy(purchaseAmount="-1"),device().copy(purchaseAmount="1e3"),
            device().copy(startDate=null),device().copy(startDate="2026-09-08"),device().copy(startDate="bad"),
            device().copy(endDate="2026-09-02"),device().copy(saleAmount="10"),
            device().copy(status=DeviceStatus.RETIRED),
            device().copy(status=DeviceStatus.RETIRED,endDate="2026-08-31"),
            device().copy(status=DeviceStatus.RETIRED,endDate="2026-09-08"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03",saleAmount="-1")
        ).forEach {d -> assertThrows(Exception::class.java) {Devices.validate(d,today)} }
    }
    @Test fun backupsRoundTripDevicesAndOldVersionOneBackupsDefaultToEmpty() {
        val l=Ledger(devices=listOf(device(),device().copy(id="wish",status=DeviceStatus.WISHLIST,startDate=null)))
        assertEquals(l,Book.decode(Book.encode(l)).data)
        val old=Book.encode(Ledger()).replace(",\"devices\":[]", "")
        assertFalse(old.contains("devices"))
        assertEquals(emptyList<Device>(),Book.decode(old).data.devices)
        assertThrows(Exception::class.java) {Book.encode(l.copy(devices=listOf(device(),device())))}
    }
    @Test fun deviceCostsNeverChangeSubscriptionActualOrForecastTotals() {
        val payment=Payment(planId="p",planName="会员",amount="20",currency="CNY",date="2026-09-01")
        val l=Ledger(payments=listOf(payment),devices=listOf(device()))
        assertEquals(BigDecimal("20"),Book.paidCny(l))
        assertEquals(BigDecimal.ZERO,Book.forecastCny(l,today,today.plusDays(30)))
    }
}
