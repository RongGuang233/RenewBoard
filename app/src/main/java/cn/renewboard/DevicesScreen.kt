package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private fun deviceIcon(category: DeviceCategory): ImageVector = when(category) {
    DeviceCategory.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceCategory.COMPUTER -> Icons.Outlined.Laptop
    DeviceCategory.TABLET -> Icons.Outlined.TabletAndroid
    DeviceCategory.AUDIO -> Icons.Outlined.Headphones
    DeviceCategory.CAMERA -> Icons.Outlined.PhotoCamera
    DeviceCategory.GAMING -> Icons.Outlined.SportsEsports
    DeviceCategory.OTHER -> Icons.Outlined.Devices
}
private fun deviceMoney(value: String) = "¥" + BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DevicesScreen(l: Ledger, change: ((Ledger)->Ledger)->Unit, onSubpageChange: (Boolean)->Unit = {}) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(DeviceStatus.ACTIVE.name) }
    var deleting by remember { mutableStateOf(false) }
    val device = l.devices.find { it.id == selectedId }
    val subpage = editing || selectedId != null
    LaunchedEffect(subpage) { onSubpageChange(subpage) }
    DisposableEffect(Unit) { onDispose { onSubpageChange(false) } }
    fun back() { if(editing) editing=false else selectedId=null }
    BackHandler(enabled=subpage) { back() }
    Column(Modifier.fillMaxSize()) {
        if(subpage) TopAppBar(title={ Text(if(editing) if(selectedId==null) "添加设备" else "编辑设备" else "设备详情") },
            navigationIcon={ FilledTonalIconButton(onClick=::back,modifier=Modifier.padding(start=8.dp)) { Icon(Icons.Outlined.ArrowBack,"返回") } },
            windowInsets=WindowInsets(0,0,0,0))
        if(editing) {
            key(selectedId) { DeviceEditor(device, onSave={ saved ->
                change { old -> old.copy(devices=old.devices.filterNot { it.id==saved.id } + saved) }
                selectedId=saved.id; editing=false
            }) }
        } else if(selectedId!=null) {
            if(device!=null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Icon(deviceIcon(device.category),null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                Text(device.name,fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("${device.category.label} · ${device.status.label}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(device.status==DeviceStatus.WISHLIST) "购买预算" else "购入金额")
                        Text(deviceMoney(device.purchaseAmount),fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        Devices.serviceDays(device)?.let { Text("已服役 $it 天",fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
                        Devices.dailyCost(device)?.let { Text("日均 ${deviceMoney(it.toPlainString())}") }
                    }
                }
                if(device.status!=DeviceStatus.WISHLIST) Text("日均按购入金额 ÷ 服役天数计算，首日计1天。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                device.startDate?.let { Text("${if(device.status==DeviceStatus.WISHLIST) "计划日期" else "开始服役"}  $it") }
                device.endDate?.let { Text("结束服役  $it") }
                device.saleAmount?.let { Text("卖出金额  ${deviceMoney(it)}") }
                if(device.note.isNotBlank()) Text(device.note)
                Button(onClick={editing=true},modifier=Modifier.fillMaxWidth()) { Text("编辑设备") }
                TextButton(onClick={deleting=true},modifier=Modifier.fillMaxWidth()) { Text("删除设备",color=MaterialTheme.colorScheme.error) }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("我的设备",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                FilledTonalIconButton(onClick={selectedId=null;editing=true}) { Icon(Icons.Outlined.Add,"添加设备") }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                DeviceStatus.entries.forEach { status ->
                    FilterChip(selected=filter==status.name,onClick={filter=status.name},label={Text(status.label,fontSize=12.sp)},modifier=Modifier.weight(1f))
                }
            }
            val visible=l.devices.filter { it.status.name==filter }
            if(visible.isEmpty()) {
                Text("还没有${DeviceStatus.valueOf(filter).label}的设备",fontSize=20.sp,modifier=Modifier.padding(top=32.dp))
                Text("记录设备，看看它陪伴了你多久。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visible.forEach { item ->
                Card(Modifier.fillMaxWidth().clickable {selectedId=item.id},shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Icon(deviceIcon(item.category),null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(item.name,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                            Text(Devices.serviceDays(item)?.let {"服役 $it 天"} ?: "购买预算",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(deviceMoney(item.purchaseAmount),fontWeight=FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if(deleting && device!=null) AlertDialog(onDismissRequest={deleting=false},title={Text("删除设备？")},text={Text(device.name)},
        confirmButton={TextButton(onClick={change {old->old.copy(devices=old.devices.filterNot {it.id==device.id})};deleting=false;selectedId=null}) {Text("删除")}},
        dismissButton={TextButton(onClick={deleting=false}) {Text("取消")}})
}

@Composable private fun DeviceEditor(original: Device?, onSave: (Device)->Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var category by rememberSaveable { mutableStateOf(original?.category?.name ?: DeviceCategory.PHONE.name) }
    var status by rememberSaveable { mutableStateOf(original?.status?.name ?: DeviceStatus.ACTIVE.name) }
    var price by rememberSaveable { mutableStateOf(original?.purchaseAmount ?: "") }
    var start by rememberSaveable { mutableStateOf(original?.startDate ?: LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(original?.endDate ?: LocalDate.now().toString()) }
    var sale by rememberSaveable { mutableStateOf(original?.saleAmount ?: "") }
    var note by rememberSaveable { mutableStateOf(original?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val currentStatus=DeviceStatus.valueOf(status)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Field("设备名称",name,{name=it})
        Text("分类",fontWeight=FontWeight.SemiBold)
        DeviceCategory.entries.chunked(3).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { item ->
            FilterChip(selected=category==item.name,onClick={category=item.name},label={Text(item.label)})
        } } }
        Text("状态",fontWeight=FontWeight.SemiBold)
        DeviceStatus.entries.chunked(2).forEach {row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { row.forEach { item ->
            FilterChip(selected=status==item.name,onClick={status=item.name},label={Text(item.label)})
        } } }
        Field(if(currentStatus==DeviceStatus.WISHLIST) "购买预算（元）" else "购入金额（元）",price,{price=it})
        Field(if(currentStatus==DeviceStatus.WISHLIST) "计划日期（选填）" else "服役日期",start,{start=it},dateField=true)
        if(currentStatus==DeviceStatus.RETIRED || currentStatus==DeviceStatus.SOLD) Field("结束日期",end,{end=it},dateField=true)
        if(currentStatus==DeviceStatus.SOLD) Field("卖出金额（元）",sale,{sale=it})
        Field("设备备注",note,{note=it})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={
            try {
                val saved=Device(id=original?.id ?: newId(),name=name.trim(),category=DeviceCategory.valueOf(category),status=currentStatus,
                    purchaseAmount=price.trim(),startDate=start.trim().ifEmpty {null},
                    endDate=if(currentStatus==DeviceStatus.RETIRED || currentStatus==DeviceStatus.SOLD) end.trim().ifEmpty {null} else null,
                    saleAmount=if(currentStatus==DeviceStatus.SOLD) sale.trim() else null,note=note)
                Devices.validate(saved); onSave(saved)
            } catch(e: Exception) { error=if(e is java.time.format.DateTimeParseException) "请使用 YYYY-MM-DD 日期格式" else e.message ?: "请检查输入" }
        },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("保存设备")}
    }
}
