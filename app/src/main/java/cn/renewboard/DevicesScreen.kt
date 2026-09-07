package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    DeviceCategory.HEADPHONES -> Icons.Outlined.Headphones
    DeviceCategory.MONITOR -> Icons.Outlined.Monitor
    DeviceCategory.WATCH -> Icons.Outlined.Watch
    DeviceCategory.KEYBOARD -> Icons.Outlined.Keyboard
    DeviceCategory.MOUSE -> Icons.Outlined.Mouse
    DeviceCategory.CONTROLLER -> Icons.Outlined.SportsEsports
    DeviceCategory.EREADER -> Icons.Outlined.MenuBook
    DeviceCategory.CHAIR -> Icons.Outlined.Chair
    DeviceCategory.ROUTER -> Icons.Outlined.Router
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
            if(device!=null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(deviceIcon(device.category),null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(device.name,fontSize=23.sp,fontWeight=FontWeight.Bold)
                        Text("${device.category.label} · ${device.status.label}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column {
                        Text(if(device.status==DeviceStatus.WISHLIST) "购买预算" else "购入金额",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(deviceMoney(device.purchaseAmount),fontSize=26.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    }
                    Devices.serviceDays(device)?.let { days -> Column(horizontalAlignment=Alignment.End) {
                        Text("已服役 $days 天",fontWeight=FontWeight.SemiBold)
                        Devices.dailyCost(device)?.let { Text("日均 ${deviceMoney(it.toPlainString())}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                }
                HorizontalDivider()
                device.startDate?.let { DeviceInfo(if(device.status==DeviceStatus.WISHLIST) "计划日期" else "服役日期",it) }
                device.endDate?.let { DeviceInfo(if(device.status==DeviceStatus.SOLD) "卖出日期" else "退役日期",it) }
                device.saleAmount?.let { DeviceInfo("卖出金额",deviceMoney(it)) }
                if(device.note.isNotBlank()) Text(device.note)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick={editing=true},modifier=Modifier.weight(1f)) { Text("编辑设备") }
                    TextButton(onClick={deleting=true}) { Text("删除设备",color=MaterialTheme.colorScheme.error) }
                }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("我的设备",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                FilledTonalIconButton(onClick={selectedId=null;editing=true}) { Icon(Icons.Outlined.Add,"添加设备") }
            }
            DeviceDropdown("",DeviceStatus.valueOf(filter).label,DeviceStatus.entries.map { it.label },"筛选设备状态") { label ->
                filter=DeviceStatus.entries.single {it.label==label}.name
            }
            val visible=l.devices.filter { it.status.name==filter }
            if(visible.isEmpty()) {
                Text("还没有${DeviceStatus.valueOf(filter).label}的设备",fontSize=18.sp,modifier=Modifier.padding(top=20.dp))
                Text("记录设备，看看它陪伴了你多久。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visible.forEach { item ->
                Card(Modifier.fillMaxWidth().clickable {selectedId=item.id},shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(deviceIcon(item.category),null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(item.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
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
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Field("设备名称",name,{name=it})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { DeviceCategoryPicker(DeviceCategory.valueOf(category)) { selected ->
                category=selected.name
            } }
            Box(Modifier.weight(1f)) { DeviceDropdown("状态",currentStatus.label,DeviceStatus.entries.map {it.label},"选择设备状态") { label ->
                status=DeviceStatus.entries.single {it.label==label}.name
            } }
        }
        Field(if(currentStatus==DeviceStatus.WISHLIST) "购买预算（元）" else "购入金额（元）",price,{price=it})
        Field(if(currentStatus==DeviceStatus.WISHLIST) "计划日期（选填）" else "服役日期",start,{start=it},dateField=true)
        if(currentStatus==DeviceStatus.RETIRED || currentStatus==DeviceStatus.SOLD) Field(if(currentStatus==DeviceStatus.SOLD) "卖出日期" else "退役日期",end,{end=it},dateField=true)
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

@Composable private fun DeviceInfo(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable private fun DeviceDropdown(label: String,value: String,options: List<String>,description: String,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    Box {
        TextButton(onClick={focus.clearFocus();keyboard?.hide();expanded=true},contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.semantics {contentDescription=description}) {
            if(label.isNotEmpty()) Text("$label  ",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,fontWeight=FontWeight.SemiBold)
            Icon(Icons.Outlined.ExpandMore,null,Modifier.size(20.dp))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { option -> DropdownMenuItem(text={Text(option)},onClick={onSelect(option);expanded=false}) }
        }
    }
}

@Composable private fun DeviceCategoryPicker(selected: DeviceCategory,onSelect: (DeviceCategory)->Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    TextButton(onClick={focus.clearFocus();keyboard?.hide();query="";open=true},contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.semantics {contentDescription="选择设备分类"}) {
        Icon(deviceIcon(selected),null,Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(selected.label,fontWeight=FontWeight.SemiBold)
        Icon(Icons.Outlined.ExpandMore,null,Modifier.size(20.dp))
    }
    if(open) Dialog(onDismissRequest={open=false}) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max=480.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("设备分类",fontSize=20.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    IconButton(onClick={open=false}) {Icon(Icons.Outlined.Close,"关闭分类选择")}
                }
                OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索分类")},singleLine=true,
                    leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth())
                val visible=DeviceCategory.entries.filter {it.label.contains(query.trim(),ignoreCase=true)}
                if(visible.isEmpty()) Text("没有匹配的分类",modifier=Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(columns=GridCells.Fixed(3),modifier=Modifier.fillMaxWidth().weight(1f,fill=false),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(visible,key={it.name}) { item ->
                        val active=item==selected
                        Surface(onClick={onSelect(item);keyboard?.hide();open=false},shape=RoundedCornerShape(14.dp),
                            color=if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor=if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
                            Column(Modifier.padding(vertical=12.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Icon(deviceIcon(item),null,Modifier.size(24.dp))
                                Text(item.label,fontSize=13.sp,maxLines=1,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}
