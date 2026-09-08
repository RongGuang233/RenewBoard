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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
    DeviceCategory.AUDIO -> Icons.Outlined.Speaker
    DeviceCategory.CAMERA -> Icons.Outlined.PhotoCamera
    DeviceCategory.GAMING -> Icons.Outlined.VideogameAsset
    DeviceCategory.OTHER -> Icons.Outlined.Devices
}
private fun deviceMoney(value: String) = "¥" + BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DevicesScreen(l: Ledger, change: ((Ledger)->Ledger)->Unit, onSubpageChange: (Boolean)->Unit = {}) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var purchasing by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(DeviceStatus.ACTIVE.name) }
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(DeviceSort.DATE.name) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val listScroll = rememberScrollState()
    val device = l.devices.find { it.id == selectedId }
    val subpage = editing || purchasing || selectedId != null
    LaunchedEffect(subpage) { onSubpageChange(subpage) }
    DisposableEffect(Unit) { onDispose { onSubpageChange(false) } }
    fun back() { if(editing) editing=false else selectedId=null }
    BackHandler(enabled=subpage && !editing && !purchasing) { back() }
    if(purchasing && device!=null) {
        key(device.id) { DevicePurchaseEditor(device,onClose={purchasing=false},onSaved={saved->
            selectedId=saved.id;purchasing=false
        }) }
        return
    }
    if(editing) {
        key(selectedId) { DeviceEditor(device,onClose={editing=false},onSaved={saved->
            selectedId=saved.id;editing=false
        }) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if(subpage) TopAppBar(title={ Text(if(editing) if(selectedId==null) "添加设备" else "编辑设备" else "设备详情") },
            navigationIcon={ PageBack(description="返回",back=::back) },
            actions={ if(!editing && device!=null) Box {
                IconButton(onClick={moreMenu=true}) {Icon(Icons.Outlined.MoreVert,"设备更多操作")}
                DropdownMenu(expanded=moreMenu,onDismissRequest={moreMenu=false}) {
                    if(device.status==DeviceStatus.WISHLIST) DropdownMenuItem(text={Text("编辑设备")},onClick={moreMenu=false;editing=true})
                    DropdownMenuItem(text={Text("变更设备状态")},onClick={moreMenu=false;editing=true})
                    DropdownMenuItem(text={Text("删除设备")},onClick={moreMenu=false;deleting=true})
                }
            } },
            windowInsets=WindowInsets(0,0,0,0))
        if(selectedId!=null) {
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
                        Text(if(device.status==DeviceStatus.WISHLIST) "购买预算" else if(device.status==DeviceStatus.SOLD) "净花费" else "购入金额",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(deviceMoney(if(device.status==DeviceStatus.SOLD) Devices.netCost(device).toPlainString() else device.purchaseAmount),fontSize=26.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    }
                    Devices.serviceDays(device)?.let { days -> Column(horizontalAlignment=Alignment.End) {
                        Text("已服役 $days 天",fontWeight=FontWeight.SemiBold)
                        Devices.dailyCost(device)?.let { Text("日均购入成本 ${deviceMoney(it.toPlainString())}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                }
                HorizontalDivider()
                device.startDate?.let { DeviceInfo(if(device.status==DeviceStatus.WISHLIST) "计划日期" else "服役日期",it) }
                if(device.status==DeviceStatus.SOLD) {
                    val soldOn=Devices.saleDate(device)
                    if(device.endDate!=soldOn) device.endDate?.let {DeviceInfo("停止服役日期",it)}
                    soldOn?.let {DeviceInfo(if(device.endDate==soldOn) "停止服役 / 卖出日期" else "卖出日期",it)}
                } else device.endDate?.let {DeviceInfo("退役日期",it)}
                if(device.status==DeviceStatus.SOLD) DeviceInfo("购入金额",deviceMoney(device.purchaseAmount))
                device.saleAmount?.let { DeviceInfo("卖出金额",deviceMoney(it)) }
                if(device.note.isNotBlank()) Text(device.note)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick={if(device.status==DeviceStatus.WISHLIST) purchasing=true else editing=true},modifier=Modifier.weight(1f)) {
                        Text(if(device.status==DeviceStatus.WISHLIST) "记为已购买" else "编辑设备")
                    }

                }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(listScroll).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("我的设备",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                FilledTonalIconButton(onClick={selectedId=null;editing=true}) { Icon(Icons.Outlined.Add,"添加设备") }
            }
            OutlinedTextField(value=search,onValueChange={search=it},label={Text("搜索设备")},singleLine=true,
                leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DeviceDropdown("",DeviceStatus.valueOf(filter).label,DeviceStatus.entries.map { it.label },"筛选设备状态") { label ->
                    filter=DeviceStatus.entries.single {it.label==label}.name
                    if(DeviceSort.valueOf(sort) !in DeviceSort.options(DeviceStatus.valueOf(filter))) sort=DeviceSort.DATE.name
                } }
                Box {
                    IconButton(onClick={sortMenu=true}) {Icon(Icons.Outlined.Sort,"设备排序")}
                    DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                        DeviceSort.options(DeviceStatus.valueOf(filter)).forEach {item -> DropdownMenuItem(text={Text(item.label(DeviceStatus.valueOf(filter)))},onClick={sort=item.name;sortMenu=false},
                            trailingIcon=if(sort==item.name) {{Icon(Icons.Outlined.Check,null)}} else null) }
                    }
                }
            }
            val visible=Devices.list(l.devices,DeviceStatus.valueOf(filter),search,DeviceSort.valueOf(sort))
            if(visible.isEmpty()) {
                Text(if(search.isNotBlank()) "没有匹配的设备" else "还没有${DeviceStatus.valueOf(filter).label}的设备",fontSize=18.sp,modifier=Modifier.padding(top=20.dp))
                Text("记录设备，看看它陪伴了你多久。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visible.forEach { item ->
                Card(Modifier.fillMaxWidth().clickable {selectedId=item.id},shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(deviceIcon(item.category),null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(item.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                            Text(Devices.serviceDays(item)?.let {"服役 $it 天"} ?: item.category.label,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(deviceMoney(if(item.status==DeviceStatus.SOLD) Devices.netCost(item).toPlainString() else item.purchaseAmount),fontWeight=FontWeight.SemiBold)
                            Text(when(item.status) {DeviceStatus.SOLD -> "净花费";DeviceStatus.WISHLIST -> "预算";else -> "购入金额"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if(deleting && device!=null) AlertDialog(onDismissRequest={deleting=false},title={Text("删除设备？")},text={Text(device.name)},
        confirmButton={TextButton(onClick={change {old->old.copy(devices=old.devices.filterNot {it.id==device.id})};deleting=false;selectedId=null}) {Text("删除")}},
        dismissButton={TextButton(onClick={deleting=false}) {Text("取消")}})
}

@Serializable private data class DevicePurchaseDraft(val price:String,val start:String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DevicePurchaseEditor(original:Device,onClose:()->Unit,onSaved:(Device)->Unit) {
    val context=LocalContext.current
    val store=remember(context) {DraftStore(context)}
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val draftKey="device:purchase:${original.id}"
    val baselineJson=rememberSaveable(draftKey) {Book.json.encodeToString(DevicePurchaseDraft(original.purchaseAmount,LocalDate.now().toString()))}
    val stored=remember(draftKey) {store.read(draftKey)?.takeIf {runCatching {Book.json.decodeFromString<DevicePurchaseDraft>(it)}.isSuccess}}
    var draftJson by rememberSaveable(draftKey) {mutableStateOf(stored ?: baselineJson)}
    val draft=remember(draftJson) {Book.json.decodeFromString<DevicePurchaseDraft>(draftJson)}
    val dirty=draftJson!=baselineJson
    var restored by rememberSaveable(draftKey) {mutableStateOf(stored!=null)}
    var leaving by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var completed by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun update(value:DevicePurchaseDraft) {if(!saving) {draftJson=Book.json.encodeToString(value);error=null}}
    fun back() {if(!saving) {focus.clearFocus();keyboard?.hide();if(dirty) leaving=true else {store.remove(draftKey);onClose()}}}
    fun discard() {store.remove(draftKey);draftJson=baselineJson;restored=false;error=null}
    LaunchedEffect(draftJson,saving) {
        if(!saving && !completed) {if(dirty) store.write(draftKey,draftJson) else store.remove(draftKey)}
    }
    BackHandler {back()}
    Scaffold(modifier=Modifier.fillMaxSize().imePadding(),topBar={
        TopAppBar(title={Text("记为已购买")},navigationIcon={PageBack(description="返回",back=::back)},windowInsets=WindowInsets(0,0,0,0))
    },bottomBar={Surface(shadowElevation=4.dp) {
        Button(onClick={
            try {
                val saved=original.copy(status=DeviceStatus.ACTIVE,purchaseAmount=draft.price.trim(),
                    startDate=draft.start.trim().ifEmpty {null},endDate=null,saleAmount=null,saleDate=null)
                Devices.validate(saved)
                focus.clearFocus();keyboard?.hide();saving=true
                scope.launch {
                    try {
                        context.repository().update {old->
                            check(old.devices.any {it.id==saved.id && it.status==DeviceStatus.WISHLIST}) {"设备已不在待购买状态，请返回查看"}
                            old.copy(devices=old.devices.map {if(it.id==saved.id) saved else it})
                        }
                        // Keep unfinished descriptive edits, but never restore the pre-purchase lifecycle.
                        val editKey="device:${saved.id}"
                        store.read(editKey)?.let {json->
                            runCatching {Book.json.decodeFromString<DeviceDraft>(json)}.getOrNull()?.let {edit->
                                store.write(editKey,Book.json.encodeToString(edit.copy(
                                    status=saved.status,price=saved.purchaseAmount,start=saved.startDate ?: "",
                                    end=saved.endDate ?: "",sale=saved.saleAmount ?: "",saleDate=saved.saleDate,
                                    independentEnd=false)))
                            }
                        }
                        completed=true;store.remove(draftKey);onSaved(saved)
                    } catch(e:CancellationException) {throw e}
                    catch(e:Exception) {error=e.message ?: "保存失败，请重试"}
                    finally {saving=false}
                }
            } catch(e:Exception) {error=if(e is java.time.format.DateTimeParseException) "请使用 YYYY-MM-DD 日期格式" else e.message ?: "请检查输入"}
        },enabled=!saving,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=48.dp)) {
            Text(if(saving) "正在保存…" else "确认已购买")
        }
    }},contentWindowInsets=WindowInsets(0,0,0,0)) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(original.name,fontSize=23.sp,fontWeight=FontWeight.Bold)
            if(restored) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复上次草稿",modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={discard()},enabled=!saving) {Text("放弃草稿")}
            }
            MoneyField("实际购入金额（元）",draft.price,{update(draft.copy(price=it))})
            Text("已填入购买预算，可按实际金额修改。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Field("开始服役日期",draft.start,{update(draft.copy(start=it))},dateField=true)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(leaving) AlertDialog(onDismissRequest={leaving=false},title={Text("保留购买草稿？")},text={Text("还有未保存的修改。")},
        confirmButton={TextButton(onClick={store.write(draftKey,draftJson);leaving=false;onClose()}) {Text("保留草稿")}},
        dismissButton={Row {
            TextButton(onClick={discard();leaving=false;onClose()}) {Text("放弃修改")}
            TextButton(onClick={leaving=false}) {Text("继续编辑")}
        }})
}

@Serializable private data class DeviceDraft(
    val id:String, val name:String, val category:DeviceCategory, val status:DeviceStatus,
    val price:String, val start:String, val end:String, val sale:String, val note:String,
    val saleDate:String? = null, val independentEnd:Boolean = false
) {
    val soldOn: String get() = saleDate ?: end
    fun statusChanged(value:DeviceStatus):DeviceDraft {
        if(value==status) return this
        return when(value) {
            DeviceStatus.SOLD -> copy(status=value,
                saleDate=saleDate ?: if(sale.isNotEmpty()) end else LocalDate.now().toString(),
                end=if(status==DeviceStatus.RETIRED || independentEnd || saleDate!=null || sale.isNotEmpty()) end else LocalDate.now().toString(),
                independentEnd=independentEnd || status==DeviceStatus.RETIRED)
            DeviceStatus.RETIRED -> copy(status=value,independentEnd=true)
            else -> copy(status=value)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceEditor(original:Device?,onClose:()->Unit,onSaved:(Device)->Unit) {
    val context=LocalContext.current
    val store=remember(context) {DraftStore(context)}
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val draftKey=original?.id?.let {"device:$it"} ?: "device:new"
    val baselineJson=rememberSaveable(draftKey) {Book.json.encodeToString(DeviceDraft(
        id=original?.id ?: newId(),name=original?.name ?: "",category=original?.category ?: DeviceCategory.PHONE,
        status=original?.status ?: DeviceStatus.ACTIVE,price=original?.purchaseAmount ?: "",
        start=original?.startDate ?: LocalDate.now().toString(),end=original?.endDate ?: LocalDate.now().toString(),
        sale=original?.saleAmount ?: "",note=original?.note ?: "",
        saleDate=original?.let(Devices::saleDate),
        independentEnd=original?.status==DeviceStatus.RETIRED ||
            (original?.status==DeviceStatus.SOLD && original.endDate!=Devices.saleDate(original)))) }
    val stored=remember(draftKey) {store.read(draftKey)?.takeIf {runCatching {Book.json.decodeFromString<DeviceDraft>(it)}.isSuccess}}
    var draftJson by rememberSaveable(draftKey) {mutableStateOf(stored ?: baselineJson)}
    val baseline=remember(baselineJson) {Book.json.decodeFromString<DeviceDraft>(baselineJson)}
    val draft=remember(draftJson) {Book.json.decodeFromString<DeviceDraft>(draftJson)}
    // A new draft carries its own stable ID across leaving and reopening the form.
    val dirty=draft.copy(id=baseline.id)!=baseline
    var restored by rememberSaveable(draftKey) {mutableStateOf(stored!=null)}
    var leaving by remember {mutableStateOf(false)}
    var noteExpanded by rememberSaveable {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var completed by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun update(change:(DeviceDraft)->DeviceDraft) {if(!saving) {draftJson=Book.json.encodeToString(change(draft));error=null}}
    fun back() {if(!saving) {focus.clearFocus();keyboard?.hide();if(dirty) leaving=true else {store.remove(draftKey);onClose()}}}
    fun discard() {store.remove(draftKey);draftJson=baselineJson;restored=false;error=null}
    LaunchedEffect(draftJson,saving) {
        if(!saving && !completed) {if(dirty) store.write(draftKey,draftJson) else store.remove(draftKey)}
    }
    BackHandler {back()}
    Scaffold(modifier=Modifier.fillMaxSize().imePadding(),topBar={
        TopAppBar(title={Text(if(original==null) "添加设备" else "编辑设备")},
            navigationIcon={PageBack(description="返回",back=::back)},windowInsets=WindowInsets(0,0,0,0))
    },bottomBar={Surface(shadowElevation=4.dp) {
        Button(onClick={
            try {
                val saved=Device(id=draft.id,name=draft.name.trim(),category=draft.category,status=draft.status,
                    purchaseAmount=draft.price.trim(),startDate=draft.start.trim().ifEmpty {null},
                    endDate=if(draft.status==DeviceStatus.RETIRED || draft.status==DeviceStatus.SOLD) draft.end.trim().ifEmpty {null} else null,
                    saleAmount=if(draft.status==DeviceStatus.SOLD) draft.sale.trim() else null,note=draft.note,
                    saleDate=if(draft.status!=DeviceStatus.SOLD) null
                        else if(original?.status==DeviceStatus.SOLD && original.saleDate==null &&
                            draft.soldOn==original.endDate && draft.end==original.endDate) null
                        else draft.soldOn.trim().ifEmpty {null})
                Devices.validate(saved)
                focus.clearFocus();keyboard?.hide();saving=true
                scope.launch {
                    try {
                        context.repository().update {old->old.copy(devices=old.devices.filterNot {it.id==saved.id}+saved)}
                        if(original?.status==DeviceStatus.WISHLIST && saved.status!=DeviceStatus.WISHLIST) {
                            store.remove("device:purchase:${saved.id}")
                        }
                        completed=true;store.remove(draftKey);onSaved(saved)
                    } catch(e:CancellationException) {throw e}
                    catch(e:Exception) {error=e.message ?: "保存失败，请重试"}
                    finally {saving=false}
                }
            } catch(e:Exception) {error=if(e is java.time.format.DateTimeParseException) "请使用 YYYY-MM-DD 日期格式" else e.message ?: "请检查输入"}
        },enabled=!saving,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=48.dp)) {
            Text(if(saving) "正在保存…" else "保存设备")
        }
    }},contentWindowInsets=WindowInsets(0,0,0,0)) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(restored) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复上次草稿",modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={discard()},enabled=!saving) {Text("放弃草稿")}
            }
            Field("设备名称",draft.name,{value->update {it.copy(name=value)}})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {DeviceCategoryPicker(draft.category) {value->update {it.copy(category=value)}}}
                Box(Modifier.weight(1f)) {DeviceDropdown("状态",draft.status.label,DeviceStatus.entries.map {it.label},"选择设备状态") {label->
                    update {it.statusChanged(DeviceStatus.entries.single {item->item.label==label})}
                }}
            }
            MoneyField(if(draft.status==DeviceStatus.WISHLIST) "购买预算（元）" else "购入金额（元）",draft.price,{value->update {it.copy(price=value)}})
            Field(if(draft.status==DeviceStatus.WISHLIST) "计划日期（选填）" else "服役日期",draft.start,{value->update {it.copy(start=value)}},dateField=true)
            if(draft.status==DeviceStatus.RETIRED) Field("退役日期",draft.end,{value->update {it.copy(end=value,independentEnd=true)}},dateField=true)
            if(draft.status==DeviceStatus.SOLD) {
                Field("卖出日期",draft.soldOn,{value->update {it.copy(saleDate=value,end=if(it.independentEnd) it.end else value)}},dateField=true)
                Row(Modifier.fillMaxWidth().clickable {update {it.copy(independentEnd=!it.independentEnd,end=if(it.independentEnd) it.soldOn else it.end)}},verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=draft.independentEnd,onCheckedChange={checked->update {it.copy(independentEnd=checked,end=if(checked) it.end else it.soldOn)}})
                    Text("停止服役日期不同",style=MaterialTheme.typography.bodyMedium)
                }
                if(draft.independentEnd) Field("停止服役日期",draft.end,{value->update {it.copy(end=value)}},dateField=true)
            }
            if(draft.status==DeviceStatus.SOLD) MoneyField("卖出金额（元）",draft.sale,{value->update {it.copy(sale=value)}})
            TextButton(onClick={noteExpanded=!noteExpanded}) {Text("设备备注");Icon(if(noteExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
            if(noteExpanded) Field("设备备注",draft.note,{value->update {it.copy(note=value)}})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(leaving) AlertDialog(onDismissRequest={leaving=false},title={Text("保留设备草稿？")},text={Text("还有未保存的修改。")},
        confirmButton={TextButton(onClick={store.write(draftKey,draftJson);leaving=false;onClose()}) {Text("保留草稿")}},
        dismissButton={Row {
            TextButton(onClick={discard();leaving=false;onClose()}) {Text("放弃修改")}
            TextButton(onClick={leaving=false}) {Text("继续编辑")}
        }})
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
