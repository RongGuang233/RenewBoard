package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal fun assetMoney(v:BigDecimal,currency:String="CNY"):String {
    val format=java.text.DecimalFormat("#,##0.00",java.text.DecimalFormatSymbols(java.util.Locale.US))
    format.roundingMode=RoundingMode.HALF_UP
    return (if(currency=="CNY") "¥" else "$currency ")+format.format(v)
}
internal fun assetIcon(category:AssetCategory)=when(category) {
    AssetCategory.CASH->Icons.Outlined.AccountBalanceWallet
    AssetCategory.INVESTMENT->Icons.Outlined.TrendingUp
    AssetCategory.RECEIVABLE->Icons.Outlined.CallReceived
    AssetCategory.DEBT->Icons.Outlined.CreditCard
    AssetCategory.PREPAID->Icons.Outlined.Savings
    AssetCategory.TUITION->Icons.Outlined.School
}

// A neutral, centered balance leads; compact white groups retain RenewBoard's blue actions.
// 16dp gutters, 12dp card radii and 64dp minimum account rows keep the list readable and touchable.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AssetsScreen(
    ledger:Ledger,save:suspend ((Ledger)->Ledger)->Unit,
    tuitionContent:@Composable (Boolean,()->Unit,ScrollState,@Composable ()->Unit)->Unit,
    onSubpageChange:(Boolean)->Unit={}
) {
    var page by rememberSaveable {mutableStateOf("home")}
    var detailReturn by rememberSaveable {mutableStateOf("home")}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var hidden by rememberSaveable {mutableStateOf(false)}
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf(false)}
    var createCategory by rememberSaveable {mutableStateOf(AssetCategory.CASH.name)}
    var createType by rememberSaveable {mutableStateOf("银行卡")}
    var refreshTuition by rememberSaveable {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    var savingBorrowing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("asset-display",android.content.Context.MODE_PRIVATE)}
    var collapsed by remember {mutableStateOf(prefs.getStringSet("collapsed",emptySet())!!.toSet())}
    var order by remember {mutableStateOf((prefs.getString("groups","")!!.split(",").mapNotNull {name->AssetCategory.entries.find {it.name==name}}+AssetCategory.entries).distinct())}
    var accountOrder by remember {mutableStateOf(runCatching {
        val ids=org.json.JSONArray(prefs.getString("accounts","[]"))
        (0 until ids.length()).map {ids.getString(it)}
    }.getOrDefault(emptyList()))}
    val scope=rememberCoroutineScope()
    val accounts=Assets.accounts(ledger).sortedBy {accountOrder.indexOf(it.id).takeIf {index->index>=0} ?: Int.MAX_VALUE}
    val homeScroll=rememberScrollState()
    val tuitionScroll=rememberScrollState()
    val reorder=remember {AssetReorderState()}
    var viewport by remember {mutableStateOf(Rect.Zero)}
    val edge=with(LocalDensity.current) {48.dp.toPx()}
    val scrollStep=with(LocalDensity.current) {10.dp.toPx()}
    reorder.onMove={from,to->
        if(from.startsWith("category:")) {
            val next=order.toMutableList()
            val source=AssetCategory.valueOf(from.substringAfter(":"))
            val destination=AssetCategory.valueOf(to.substringAfter(":"))
            val target=next.indexOf(destination)
            next.remove(source);next.add(target,source);order=next
            prefs.edit().putString("groups",next.joinToString(",") {it.name}).apply()
        } else {
            val next=accounts.map {it.id}.toMutableList()
            val source=from.substringAfter(":");val target=next.indexOf(to.substringAfter(":"))
            next.remove(source);next.add(target,source);accountOrder=next
            prefs.edit().putString("accounts",org.json.JSONArray(next).toString()).apply()
        }
    }
    LaunchedEffect(reorder.dragging) {
        while(reorder.dragging!=null) {
            withFrameNanos { }
            val speed=when {
                reorder.pointerY<viewport.top+edge -> -scrollStep
                reorder.pointerY>viewport.bottom-edge -> scrollStep
                else -> 0f
            }
            if(speed!=0f) {(if(page=="tuition") tuitionScroll else homeScroll).scrollBy(speed);reorder.moveToward(speed)}
        }
    }
    val account=accounts.find {it.id==selected}
    val sub=page!="home"
    LaunchedEffect(sub) {onSubpageChange(sub)}
    DisposableEffect(Unit) {onDispose {onSubpageChange(false)}}
    fun tuition(refresh:Boolean=false) {refreshTuition=refresh;page="tuition"}
    fun detail(a:AssetAccount) {selected=a.id;detailReturn=page;page="detail"}
    fun selectType(category:AssetCategory,type:String) {
        val existing=accounts.firstOrNull {it.planId==null && it.studentId==null && it.category==category && canonicalAssetType(it.subtype,it.name)==canonicalAssetType(type,type)}
        if(existing!=null) {selected=existing.id;detailReturn="home";page="detail"}
        else {selected=null;createCategory=category.name;createType=type;page="edit"}
    }
    fun back() {page=when {page=="edit" && account!=null->"detail";page=="edit"->if(createType in commonAssetBanks) "bank" else "type";page=="bank"->"type";page=="detail"->detailReturn;else->"home"}}
    BackHandler(sub) {back()}
    Column(Modifier.fillMaxSize().pageTransition(page)) {
        if(sub) TopAppBar(title={Text(when(page) {"detail"->"账户详情";"edit"->if(selected==null) "添加账户" else "编辑";"type"->"选择账户类型";"bank"->"选择银行";"borrowed"->"借入账户";"lent"->"借出账户";else->"学费"})},
            navigationIcon={PageBack(back=::back)},windowInsets=WindowInsets(0,0,0,0))
        when(page) {
            "type" -> AssetTypePicker {cat,type->
                when {cat==AssetCategory.TUITION->tuition();cat==AssetCategory.CASH && type=="银行卡"->page="bank";else->selectType(cat,type)}
            }
            "bank" -> AssetBankPicker(onSelect={selectType(AssetCategory.CASH,it)})
            "edit" -> key(selected,createCategory,createType) {
                AssetEditor(account,save,onSaved={selected=it;page="detail"},onTuition={tuition()},initialCategory=AssetCategory.valueOf(createCategory),initialType=createType)
            }
            "detail" -> if(account!=null) AssetDetail(ledger,account,hidden,onEdit={page="edit"},onDelete={deleteId=account.id;error=null})
            "borrowed", "lent" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                val loans=accounts.filter {Assets.isBorrowing(it) && it.category==if(page=="borrowed") AssetCategory.DEBT else AssetCategory.RECEIVABLE}
                if(loans.isEmpty()) Text("暂无${if(page=="borrowed") "借入" else "借出"}账户",color=MaterialTheme.colorScheme.onSurfaceVariant)
                else AssetCard {loans.forEach {a->AssetAccountRow(ledger,a,hidden,onClick={detail(a)})}}
            }
            "tuition" -> Column(Modifier.fillMaxSize().onGloballyPositioned {viewport=it.boundsInRoot()}.padding(horizontal=16.dp)) {
                tuitionContent(refreshTuition,{refreshTuition=false},tuitionScroll) {
                    val students=accounts.filter {it.category==AssetCategory.TUITION}
                    if(students.isNotEmpty()) AssetCard {
                        Text("学生账户",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(16.dp))
                        students.forEach {a->key(a.id) {
                            AssetReorderItem(reorder,"account:${a.id}",AssetCategory.TUITION.name) {handle->
                                AssetAccountRow(ledger,a,hidden,onClick={detail(a)},modifier=handle)
                            }
                        }}
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().onGloballyPositioned {viewport=it.boundsInRoot()}.verticalScroll(homeScroll).padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("资产",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    Box {
                        IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"资产管理菜单")}
                        DropdownMenu(menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("新增账户")},leadingIcon={Icon(Icons.Outlined.Add,null)},onClick={menu=false;selected=null;detailReturn="home";page="type"})
                            DropdownMenuItem(text={Text("学费同步")},onClick={menu=false;tuition()})
                            DropdownMenuItem(text={Column {
                                Text("显示借入借出")
                                Text("关闭后不计入资产统计",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }},trailingIcon={Switch(checked=ledger.assets.showBorrowing,onCheckedChange=null)},enabled=!savingBorrowing,onClick={
                                menu=false;savingBorrowing=true;error=null
                                scope.launch {
                                    try {save {it.copy(assets=it.assets.copy(showBorrowing=!it.assets.showBorrowing))}}
                                    catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "设置保存失败，请重试"}
                                    finally {savingBorrowing=false}
                                }
                            })
                        }
                    }
                }
                val totals=Assets.totals(ledger)
                AssetCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(if(totals.pending>0) "已知净资产" else "净资产",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick={hidden=!hidden}) {Icon(if(hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(hidden) "显示资产金额" else "隐藏资产金额",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                        }
                        Text(if(hidden) "••••••" else assetMoney(totals.net),fontSize=32.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth()) {
                            AssetSummaryAmount("总资产",totals.assets,hidden,Modifier.weight(1f))
                            AssetSummaryAmount("总负债",totals.liabilities,hidden,Modifier.weight(1f))
                        }
                        if(totals.pending>0) Text("${totals.pending} 个账户待折算或核实，暂未计入",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))
                    }
                }
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(ledger.assets.showBorrowing) AssetBorrowingSummary(accounts,hidden,onBorrowed={page="borrowed"},onLent={page="lent"})
                if(accounts.isEmpty()) Text("从右上角菜单添加账户，记录当前余额。",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(16.dp))
                order.forEach {category->
                    val group=accounts.filter {it.category==category && !Assets.isBorrowing(it)}
                    if(group.isNotEmpty()) key(category) {
                        AssetReorderItem(reorder,"category:${category.name}","categories") {handle->
                            if(category==AssetCategory.TUITION) TuitionOverview(ledger,group,hidden,onRefresh={tuition(true)},onStudents={tuition()},headerModifier=handle)
                            else AssetGroup(ledger,category,group,hidden,category.name in collapsed,onToggle={
                                collapsed=if(category.name in collapsed) collapsed-category.name else collapsed+category.name
                                prefs.edit().putStringSet("collapsed",collapsed).apply()
                            },onAccount={detail(it)},headerModifier=handle,accountContent={a->
                                AssetReorderItem(reorder,"account:${a.id}",category.name) {rowHandle->
                                    AssetAccountRow(ledger,a,hidden,onClick={detail(a)},modifier=rowHandle)
                                }
                            })
                        }
                    }
                }
            }
        }
    }
    deleteId?.let {id->accounts.find {it.id==id}?.let {a->
        AlertDialog(onDismissRequest={if(!deleting) deleteId=null},title={Text("删除账户？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("将删除“${a.name}”及其余额记录，历史净资产记录保留。"+if(a.studentId!=null) "LessonManager 中的数据不受影响，之后可重新选择导入。" else "")
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }},
            confirmButton={TextButton(enabled=!deleting,onClick={
                deleting=true
                scope.launch {
                    try {save {Assets.remove(it,id)};deleteId=null;selected=null;page=detailReturn}
                    catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "删除失败，请重试"}
                    finally {deleting=false}
                }
            }) {Text(if(deleting) "正在删除…" else "删除",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(enabled=!deleting,onClick={deleteId=null}) {Text("取消")}})
    }}
}

@Composable private fun AssetDetail(l:Ledger,a:AssetAccount,hidden:Boolean,onEdit:()->Unit,onDelete:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {AssetAccountIcon(a.category,a.subtype,a.name);Text(a.name,fontSize=24.sp,fontWeight=FontWeight.Bold)}
        Text(a.category.label+(if(a.subtype.isNotBlank()) " · ${a.subtype}" else ""),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(hidden) "••••••" else assetMoney(if(a.category==AssetCategory.TUITION) BigDecimal(a.balance).abs() else BigDecimal(a.balance),a.currency),fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
        Text("更新于 ${a.asOf} · "+(if(Assets.includedInTotals(l,a)) "计入净资产" else "不计入净资产"),style=MaterialTheme.typography.bodyMedium)
        Assets.value(l,a).pendingReason?.let {Hint(it)}
        if(a.category==AssetCategory.TUITION) Text(tuitionBalanceLabel(a.balance)+if(a.included) (if(BigDecimal(a.balance).signum()>0) " · 计入负债" else if(BigDecimal(a.balance).signum()<0) " · 计入资产" else "") else " · 不计入净资产")
        a.cnyRate?.let {Hint("折算依据：1 ${a.currency} = $it CNY")}
        if(a.note.isNotBlank()) Text(a.note)
        Button(onClick=onEdit,modifier=Modifier.fillMaxWidth()) {Text("编辑")}
        TextButton(onClick=onDelete) {Text("删除账户",color=MaterialTheme.colorScheme.error)}
        HorizontalDivider()
        Text("余额记录",fontWeight=FontWeight.SemiBold)
        if(a.history.isEmpty()) Hint("暂无余额记录")
        a.history.asReversed().forEach {record->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text(record.kind);Text(record.date,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Text(if(hidden) "••••" else assetMoney(BigDecimal(record.balance),record.currency ?: a.currency))
            }
        }
    }
}

// One form edits the account. Creation still supplies a default name from the chosen type.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AssetEditor(a:AssetAccount?,save:suspend ((Ledger)->Ledger)->Unit,onSaved:(String)->Unit,onTuition:()->Unit,initialCategory:AssetCategory=AssetCategory.CASH,initialType:String="银行卡") {
    val originalJson=rememberSaveable {a?.let {Book.json.encodeToString(it.copy(history=emptyList()))}.orEmpty()}
    val original=remember(originalJson) {originalJson.takeIf {it.isNotEmpty()}?.let {Book.json.decodeFromString<AssetAccount>(it)}}
    val defaultName=initialType.takeUnless {it=="其他" || it.isBlank()} ?: initialCategory.label
    var name by rememberSaveable {mutableStateOf(original?.name ?: defaultName)}
    var category by rememberSaveable {mutableStateOf(original?.category?.name ?: initialCategory.name)}
    var subtype by rememberSaveable {mutableStateOf(original?.subtype ?: initialType)}
    var balance by rememberSaveable {mutableStateOf(original?.balance ?: "")}
    var currency by rememberSaveable {mutableStateOf(original?.currency ?: "CNY")}
    var asOf by rememberSaveable {mutableStateOf(original?.asOf ?: LocalDate.now().toString())}
    var included by rememberSaveable {mutableStateOf(original?.included ?: Assets.defaultIncluded(initialCategory,initialType))}
    var rate by rememberSaveable {mutableStateOf(original?.cnyRate.orEmpty())}
    var note by rememberSaveable {mutableStateOf(original?.note.orEmpty())}
    var correction by rememberSaveable {mutableStateOf(false)}
    var choosingType by rememberSaveable {mutableStateOf(false)}
    var choosingBank by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val cat=AssetCategory.valueOf(category)
    val synced=original?.studentId!=null
    fun chooseType(chosen:AssetCategory,type:String) {
        val oldDefault=subtype.takeUnless {it=="其他" || it.isBlank()} ?: cat.label
        if(name==oldDefault) name=type.takeUnless {it=="其他"} ?: chosen.label
        category=chosen.name;subtype=type;choosingType=false;choosingBank=false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AssetAccountIcon(cat,subtype,name);Text(name,fontWeight=FontWeight.SemiBold)
        }
        if(cat==AssetCategory.TUITION && original==null) {
            Hint("学费由 LessonManager 导入，学生标识用于后续自动更新，避免重复输入。")
            Button(onClick=onTuition) {Text("连接并导入学费")}
        } else {
            if(original!=null) Field("账户名称",name,{name=it})
            if(original!=null && !synced) OutlinedButton(onClick={choosingType=true},modifier=Modifier.fillMaxWidth().semantics {contentDescription="编辑账户类型"}) {
                Text("${cat.label} · ${subtype.ifBlank {"其他"}}",modifier=Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)
            }
            MoneyField(if(cat==AssetCategory.DEBT) "剩余欠款" else "当前余额",balance,{balance=it},
                supportingText=if(synced) "正数为待上课，负数为待收款；新一期同步数据会更新此余额。" else null)
            if(synced) Text("学费 · 人民币 CNY",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else CurrencyPicker(currency) {if(currency!=it) rate="";currency=it}
            Field("余额日期",asOf,{asOf=it},dateField=true)
            if(currency!="CNY") MoneyField("折算汇率（1 $currency = ? CNY）",rate,{rate=it},supportingText="留空保留原币余额，列为待折算账户")
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {Text("计入净资产",modifier=Modifier.weight(1f));Switch(included,{included=it},modifier=Modifier.semantics {contentDescription="计入净资产开关"})}
            Field("备注",note,{note=it})
            if(original!=null && a?.history?.isNotEmpty()==true) {
                TextButton(onClick={correction=!correction}) {Text(if(correction) "取消纠正上次录入" else "纠正上次录入")}
                if(correction) Text("将替换最近一条余额记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {
                        val draft=(original ?: AssetAccount(name=name,category=cat)).copy(name=name.trim(),category=cat,subtype=subtype,balance=balance.trim(),currency=currency,
                            asOf=asOf,included=included,cnyRate=rate.trim().ifBlank {null},note=note.trim())
                        var savedId=draft.id
                        save {current->
                            if(original==null) {
                                val existing=Assets.accounts(current).firstOrNull {
                                    it.studentId==null && it.category==cat && canonicalAssetType(it.subtype,it.name)==canonicalAssetType(subtype,name)
                                }
                                if(existing!=null) {savedId=existing.id;current}
                                else Assets.put(current,draft.copy(history=listOf(AssetBalanceRecord(balance=draft.balance,date=asOf,kind="初始余额",currency=currency))))
                            } else Assets.edit(current,original,draft,correction)
                        }
                        onSaved(savedId)
                    } catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "账户保存失败"} finally {busy=false}
                }
            },modifier=Modifier.fillMaxWidth()) {Text(if(busy) "正在保存…" else "保存账户")}
        }
    }
    if(choosingType) ModalBottomSheet(onDismissRequest={choosingType=false;choosingBank=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        if(choosingBank) AssetBankPicker {chooseType(AssetCategory.CASH,it)}
        else AssetTypePicker(includeTuition=false) {chosen,type->
            if(chosen==AssetCategory.CASH && type=="银行卡") choosingBank=true else chooseType(chosen,type)
        }
    }
}
