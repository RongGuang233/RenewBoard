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
            device().copy(saleDate="2026-09-02"),
            device().copy(status=DeviceStatus.RETIRED),
            device().copy(status=DeviceStatus.RETIRED,endDate="2026-08-31"),
            device().copy(status=DeviceStatus.RETIRED,endDate="2026-09-08"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03",saleAmount="-1"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03",saleAmount="10",saleDate="2026-09-02"),
            device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03",saleAmount="10",saleDate="2026-09-08"),
            device().copy(status=DeviceStatus.RETIRED,endDate="2026-09-03",saleDate="2026-09-04")
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
    @Test fun expandedCategoriesRoundTripAndExistingSerializedNamesRemainReadable() {
        val items=DeviceCategory.entries.map { category ->
            device().copy(id=category.name,category=category,status=DeviceStatus.WISHLIST,startDate=null)
        }
        val restored=Book.decode(Book.encode(Ledger(devices=items))).data.devices
        assertEquals(items,restored)
        listOf("PHONE","COMPUTER","TABLET","AUDIO","CAMERA","GAMING","OTHER").forEach { oldName ->
            val legacyJson="""{"id":"old","name":"原设备","category":"$oldName","status":"WISHLIST","purchaseAmount":"100"}"""
            assertEquals(oldName,Book.json.decodeFromString<Device>(legacyJson).category.name)
        }
        assertTrue(DeviceCategory.entries.map {it.label}.containsAll(listOf("鼠标","椅子","耳机","显示屏","手机","电脑","手表","平板","键盘","手柄","电纸书")))
    }

    @Test fun searchAndSortRespectStatusAndNumericPricesAndServiceDays() {
        val older=device().copy(id="older",name="旧手机",purchaseAmount="900",startDate="2026-08-01")
        val newer=device().copy(id="newer",name="新手机",purchaseAmount="10000",startDate="2026-09-01")
        val retired=older.copy(id="retired",status=DeviceStatus.RETIRED,endDate="2026-09-01")
        val all=listOf(older,newer,retired)
        assertEquals(listOf("newer","older"),Devices.list(all,DeviceStatus.ACTIVE,"手机",DeviceSort.DATE,today).map {it.id})
        assertEquals(listOf("newer","older"),Devices.list(all,DeviceStatus.ACTIVE,"",DeviceSort.PRICE,today).map {it.id})
        assertEquals(listOf("older","newer"),Devices.list(all,DeviceStatus.ACTIVE,"",DeviceSort.SERVICE,today).map {it.id})
        assertTrue(Devices.list(all,DeviceStatus.ACTIVE,"不存在",DeviceSort.DATE,today).isEmpty())
    }
    @Test fun soldNetCostKeepsPurchaseDailyCostAndCanRepresentProfit() {
        val sold=device().copy(status=DeviceStatus.SOLD,endDate="2026-09-03",saleAmount="2000")
        assertEquals(BigDecimal("1000"),Devices.netCost(sold))
        assertEquals(BigDecimal("1000.00"),Devices.dailyCost(sold,today))
        assertEquals(BigDecimal("-1000"),Devices.netCost(sold.copy(saleAmount="4000")))
        assertEquals("音箱",DeviceCategory.AUDIO.label)
        assertEquals("游戏主机",DeviceCategory.GAMING.label)
    }

    @Test fun juneRetirementAndSeptemberSaleKeepServiceDaysAndBothDatesThroughBackup() {
        val retired=device().copy(status=DeviceStatus.RETIRED,startDate="2026-01-01",endDate="2026-06-30")
        val sold=retired.copy(status=DeviceStatus.SOLD,saleAmount="2000",saleDate="2026-09-07")
        Devices.validate(sold,today)
        assertEquals(181L,Devices.serviceDays(sold,today.plusYears(1)))
        assertEquals(Devices.dailyCost(retired,today),Devices.dailyCost(sold,today))
        assertEquals(BigDecimal("16.57"),Devices.dailyCost(sold,today))
        assertEquals(BigDecimal("1000"),Devices.netCost(sold))
        assertEquals("2026-09-07",Devices.saleDate(sold))
        val restored=Book.decode(Book.encode(Ledger(devices=listOf(sold)))).data.devices.single()
        assertEquals(sold,restored)
    }

    @Test fun legacySoldDateFallsBackWithoutRewritingStoredRecord() {
        val legacyJson="""{"id":"legacy","name":"旧设备","status":"SOLD","purchaseAmount":"3000","startDate":"2026-09-01","endDate":"2026-09-03","saleAmount":"2000"}"""
        val legacy=Book.json.decodeFromString<Device>(legacyJson)
        Devices.validate(legacy,today)
        assertNull(legacy.saleDate)
        assertEquals("2026-09-03",Devices.saleDate(legacy))
        assertEquals(3L,Devices.serviceDays(legacy,today))
        val restored=Book.decode(Book.encode(Ledger(devices=listOf(legacy)))).data.devices.single()
        assertEquals(legacy,restored)
        assertNull(restored.saleDate)
    }

}
