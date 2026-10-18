package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
    ledger:Ledger,save:suspend ((Ledger)->Ledger)->Unit,openPlan:(String)->Unit,
    tuitionContent:@Composable (Boolean,()->Unit,@Composable ()->Unit)->Unit,
    onSubpageChange:(Boolean)->Unit={}
) {
    var page by rememberSaveable {mutableStateOf("home")}
    var detailReturn by rememberSaveable {mutableStateOf("home")}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var hidden by rememberSaveable {mutableStateOf(false)}
    var balanceOpen by rememberSaveable {mutableStateOf(false)}
    var actionsId by rememberSaveable {mutableStateOf<String?>(null)}
    var createCategory by rememberSaveable {mutableStateOf(AssetCategory.CASH.name)}
    var createType by rememberSaveable {mutableStateOf("银行卡")}
    var refreshTuition by rememberSaveable {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
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
    val account=accounts.find {it.id==selected}
    val sub=page!="home"
    LaunchedEffect(sub) {onSubpageChange(sub)}
    DisposableEffect(Unit) {onDispose {onSubpageChange(false)}}
    fun tuition(refresh:Boolean=false) {refreshTuition=refresh;page="tuition"}
    fun detail(a:AssetAccount) {selected=a.id;detailReturn=page;page="detail"}
    fun update(a:AssetAccount) {
        selected=a.id
        when {a.planId!=null->openPlan(a.planId);a.studentId!=null->tuition(true);else->balanceOpen=true}
    }
    fun selectType(category:AssetCategory,type:String) {
        val existing=accounts.firstOrNull {it.planId==null && it.studentId==null && it.category==category && canonicalAssetType(it.subtype,it.name)==canonicalAssetType(type,type)}
        if(existing!=null) {selected=existing.id;detailReturn="home";page="detail"}
        else {selected=null;createCategory=category.name;createType=type;page="edit"}
    }
    fun back() {page=when {page=="edit" && account!=null->"detail";page=="edit"->if(createType in commonAssetBanks) "bank" else "type";page=="bank"->"type";page=="detail"->detailReturn;else->"home"}}
    BackHandler(sub && !balanceOpen) {back()}
    Column(Modifier.fillMaxSize().pageTransition(page)) {
        if(sub) TopAppBar(title={Text(when(page) {"detail"->"账户详情";"edit"->if(selected==null) "添加账户" else "账户设置";"type"->"选择账户类型";"manage"->"账户管理";"order"->"账户排序";"bank"->"选择银行";"trend"->"净资产变化";else->"学费"})},
            navigationIcon={PageBack(back=::back)},windowInsets=WindowInsets(0,0,0,0))
        when(page) {
            "type" -> AssetTypePicker {cat,type->
                when {cat==AssetCategory.TUITION->tuition();cat==AssetCategory.CASH && type=="银行卡"->page="bank";else->selectType(cat,type)}
            }
            "bank" -> AssetBankPicker(onSelect={selectType(AssetCategory.CASH,it)})
            "edit" -> key(selected,createCategory,createType) {
                AssetEditor(account,ledger,save,onSaved={selected=it;page="detail"},onTuition={tuition()},initialCategory=AssetCategory.valueOf(createCategory),initialType=createType)
            }
            "detail" -> if(account!=null) AssetDetail(ledger,account,hidden,onEdit={page="edit"},onBalance={update(account)},openPlan=openPlan,onTuition={tuition(true)})
            "trend" -> AssetTrend(ledger.assets.history,hidden)
            "tuition" -> Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                tuitionContent(refreshTuition,{refreshTuition=false}) {
                    val students=accounts.filter {it.category==AssetCategory.TUITION && !it.archived}
                    if(students.isNotEmpty()) AssetCard {
                        Text("学生账户",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(16.dp))
                        students.forEach {a->AssetAccountRow(ledger,a,hidden,onClick={detail(a)},onLongClick={actionsId=a.id})}
                    }
                }
            }
            "order" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("分组顺序",fontWeight=FontWeight.SemiBold)
                AssetCard {
                    order.forEachIndexed {i,category->
                        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(category.label,modifier=Modifier.weight(1f))
                            fun move(to:Int) {val next=order.toMutableList();java.util.Collections.swap(next,i,to);order=next;prefs.edit().putString("groups",next.joinToString(",") {it.name}).apply()}
                            IconButton(enabled=i>0,onClick={move(i-1)}) {Icon(Icons.Outlined.KeyboardArrowUp,"上移${category.label}")}
                            IconButton(enabled=i<order.lastIndex,onClick={move(i+1)}) {Icon(Icons.Outlined.KeyboardArrowDown,"下移${category.label}")}
                        }
                    }
                }
                order.forEach {category->
                    val group=accounts.filter {it.category==category && !it.archived}
                    if(group.isNotEmpty()) {
                        Text("${category.label} · 账户顺序",fontWeight=FontWeight.SemiBold)
                        AssetCard {
                            group.forEachIndexed {i,a->
                                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                    AssetAccountIcon(a.category,a.subtype,a.name)
                                    Spacer(Modifier.width(12.dp))
                                    Text(a.name,modifier=Modifier.weight(1f))
                                    fun move(to:Int) {
                                        val next=accounts.map {it.id}.toMutableList()
                                        java.util.Collections.swap(next,next.indexOf(a.id),next.indexOf(group[to].id))
                                        accountOrder=next
                                        prefs.edit().putString("accounts",org.json.JSONArray(next).toString()).apply()
                                    }
                                    IconButton(enabled=i>0,onClick={move(i-1)}) {Icon(Icons.Outlined.KeyboardArrowUp,"上移账户${a.name}")}
                                    IconButton(enabled=i<group.lastIndex,onClick={move(i+1)}) {Icon(Icons.Outlined.KeyboardArrowDown,"下移账户${a.name}")}
                                }
                            }
                        }
                    }
                }
            }
            "manage" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(onClick={tuition()}) {Icon(Icons.Outlined.Sync,null);Spacer(Modifier.width(8.dp));Text("学费同步")}
                Text("归档账户",fontWeight=FontWeight.SemiBold)
                Text("归档仅收起账户，仍按各账户设置统计。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val archived=accounts.filter {it.archived}
                if(archived.isEmpty()) Text("暂无归档账户",color=MaterialTheme.colorScheme.onSurfaceVariant)
                else AssetCard {archived.forEach {a->AssetAccountRow(ledger,a,hidden,onClick={detail(a)},onLongClick={actionsId=a.id})}}
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("资产",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    TextButton(onClick={page="order"}) {Text("排序")}
                    IconButton(onClick={selected=null;detailReturn="home";page="type"}) {Icon(Icons.Outlined.Add,"添加账户",tint=MaterialTheme.colorScheme.primary)}
                    Box {
                        IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"资产管理菜单")}
                        DropdownMenu(menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("账户管理")},onClick={menu=false;page="manage"})
                            DropdownMenuItem(text={Text("学费同步")},onClick={menu=false;tuition()})
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
                        TextButton(onClick={page="trend"},modifier=Modifier.align(Alignment.End)) {Text("查看趋势");Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp))}
                    }
                }
                if(accounts.none {!it.archived}) Text(if(accounts.isEmpty()) "添加账户，记录当前余额。" else "账户已归档，可在右上角管理中查看。",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(16.dp))
                order.forEach {category->
                    val group=accounts.filter {it.category==category && !it.archived}
                    if(group.isNotEmpty()) {
                        if(category==AssetCategory.TUITION) TuitionOverview(ledger,group,hidden,onRefresh={tuition(true)},onStudents={tuition()})
                        else AssetGroup(ledger,category,group,hidden,category.name in collapsed,onToggle={
                            collapsed=if(category.name in collapsed) collapsed-category.name else collapsed+category.name
                            prefs.edit().putStringSet("collapsed",collapsed).apply()
                        },onAccount={detail(it)},onLongClick={actionsId=it.id})
                    }
                }
            }
        }
    }
    if(balanceOpen && account!=null) ModalBottomSheet(onDismissRequest={balanceOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Text("更新余额",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(horizontal=20.dp))
        AssetBalanceEditor(account,save) {balanceOpen=false}
    }
    actionsId?.let {id->accounts.find {it.id==id}?.let {a->
        ModalBottomSheet(onDismissRequest={actionsId=null;error=null}) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(a.name,style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={actionsId=null;update(a)},modifier=Modifier.fillMaxWidth()) {Text(if(a.studentId!=null) "刷新学费" else if(a.planId!=null) "打开来源账户" else "更新余额")}
                TextButton(onClick={actionsId=null;selected=a.id;detailReturn=page;page="edit"},modifier=Modifier.fillMaxWidth()) {Text("编辑账户")}
                TextButton(onClick={scope.launch {
                    try {save {current->val latest=Assets.accounts(current).first {it.id==a.id};Assets.put(current,latest.copy(archived=!latest.archived))};actionsId=null}
                    catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message}
                }},modifier=Modifier.fillMaxWidth()) {Text(if(a.archived) "取消归档" else "归档账户")}
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        }
    }}
}

@Composable private fun AssetDetail(l:Ledger,a:AssetAccount,hidden:Boolean,onEdit:()->Unit,onBalance:()->Unit,openPlan:(String)->Unit,onTuition:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {AssetAccountIcon(a.category,a.subtype,a.name);Text(a.name,fontSize=24.sp,fontWeight=FontWeight.Bold)}
        Text(a.category.label+(if(a.subtype.isNotBlank()) " · ${a.subtype}" else ""),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(hidden) "••••••" else assetMoney(if(a.category==AssetCategory.TUITION) BigDecimal(a.balance).abs() else BigDecimal(a.balance),a.currency),fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
        Text("更新于 ${a.asOf} · "+(if(a.included) "计入净资产" else "不计入净资产"),style=MaterialTheme.typography.bodyMedium)
        Assets.value(l,a).pendingReason?.let {Hint(it)}
        if(a.category==AssetCategory.TUITION) Text(tuitionBalanceLabel(a.balance)+if(a.included) (if(BigDecimal(a.balance).signum()>0) " · 计入负债" else if(BigDecimal(a.balance).signum()<0) " · 计入资产" else "") else " · 不计入净资产")
        a.cnyRate?.let {Hint("折算依据：1 ${a.currency} = $it CNY")}
        if(a.note.isNotBlank()) Text(a.note)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={when {a.planId!=null->openPlan(a.planId);a.studentId!=null->onTuition();else->onBalance()}},modifier=Modifier.weight(1f)) {Text(if(a.planId!=null) "打开来源账户" else if(a.studentId!=null) "刷新学费" else "更新余额")}
            OutlinedButton(onClick=onEdit) {Text("账户设置")}
        }
        HorizontalDivider()
        Text("余额记录",fontWeight=FontWeight.SemiBold)
        if(a.history.isEmpty()) Hint(if(a.planId!=null) "余额沿用来源账户，不重复建立账户流水。" else "暂无余额更新记录")
        a.history.asReversed().forEach {record->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text(record.kind);Text(record.date,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Text(if(hidden) "••••" else assetMoney(BigDecimal(record.balance),a.currency))
            }
        }
    }
}

@Composable private fun AssetEditor(a:AssetAccount?,l:Ledger,save:suspend ((Ledger)->Ledger)->Unit,onSaved:(String)->Unit,onTuition:()->Unit,initialCategory:AssetCategory=AssetCategory.CASH,initialType:String="银行卡") {
    val name=a?.name ?: initialType.takeUnless {it=="其他" || it.isBlank()} ?: initialCategory.label
    val category=a?.category?.name ?: initialCategory.name
    val subtype=a?.subtype ?: initialType
    var balance by rememberSaveable {mutableStateOf(a?.balance ?: "")}
    var currency by rememberSaveable {mutableStateOf(a?.currency ?: "CNY")}
    var asOf by rememberSaveable {mutableStateOf(a?.asOf ?: LocalDate.now().toString())}
    var included by rememberSaveable {mutableStateOf(a?.included ?: Assets.defaultIncluded(initialCategory,initialType))}
    var archived by rememberSaveable {mutableStateOf(a?.archived ?: false)}
    var rate by rememberSaveable {mutableStateOf(a?.cnyRate.orEmpty())}
    var eligible by rememberSaveable {mutableStateOf(a?.eligibleAmount?.takeIf {a.eligibleBasisBalance==a.balance}.orEmpty())}
    var note by rememberSaveable {mutableStateOf(a?.note.orEmpty())}
    var notesOpen by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val cat=AssetCategory.valueOf(category)
    val api=a?.planId?.let {id->l.plans.find {it.id==id}?.usageBalance!=null}==true
    val knownPlatformAmount=a?.eligibleAmount?.takeIf {a.eligibleFromPlatform && a.eligibleBasisBalance==a.balance}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AssetAccountIcon(cat,subtype,name);Text(name,fontWeight=FontWeight.SemiBold)
        }
        if(cat==AssetCategory.TUITION && a==null) {
            Hint("学费由 LessonManager 导入，学生标识用于后续自动更新，避免重复输入。")
            Button(onClick=onTuition) {Text("连接并导入学费")}
        } else {
            if(a==null) {MoneyField(if(cat==AssetCategory.DEBT) "剩余欠款" else "当前余额",balance,{balance=it});CurrencyPicker(currency) {currency=it};Field("余额日期",asOf,{asOf=it},dateField=true)}
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {Text("计入净资产",modifier=Modifier.weight(1f));Switch(included,{included=it},modifier=Modifier.semantics {contentDescription="计入净资产开关"})}
            if(currency!="CNY") MoneyField("折算汇率（1 $currency = ? CNY）",rate,{rate=it},supportingText="留空保留原币余额，列为待折算账户")
            if(api && included) {
                if(knownPlatformAmount!=null) Hint("平台已确认充值余额 $knownPlatformAmount；赠送余额不计入。")
                else MoneyField("可计入的充值余额",eligible,{eligible=it},supportingText="仅填本人充值部分，排除平台赠送；留空暂不计入。余额变化后需重新确认。")
            }
            if(a!=null) Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {Column(Modifier.weight(1f)) {Text("归档账户");Text("仅收起列表，统计规则保持不变",style=MaterialTheme.typography.bodySmall)};Switch(archived,{archived=it},modifier=Modifier.semantics {contentDescription="归档账户开关"})}
            TextButton(onClick={notesOpen=!notesOpen}) {Text(if(notesOpen) "收起备注" else "添加或编辑备注")}
            if(notesOpen) Field("备注",note,{note=it})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {
                        val draft=(a ?: AssetAccount(name=name,category=cat)).copy(name=name.trim(),category=cat,subtype=subtype,balance=if(a==null) balance.trim() else a.balance,currency=currency,
                            asOf=asOf,included=included,archived=archived,cnyRate=rate.trim().ifBlank {null},note=note.trim(),
                            eligibleAmount=if(api) knownPlatformAmount ?: eligible.trim().ifBlank {null} else a?.eligibleAmount,
                            eligibleBasisBalance=if(api && (knownPlatformAmount!=null || eligible.isNotBlank())) a?.balance else null,eligibleFromPlatform=knownPlatformAmount!=null,
                            history=a?.history ?: listOf(AssetBalanceRecord(balance=balance.trim(),date=asOf,kind="初始余额")))
                        var savedId=draft.id
                        save {current->
                            if(a==null) {
                                val existing=Assets.accounts(current).firstOrNull {
                                    it.planId==null && it.studentId==null && it.category==cat && canonicalAssetType(it.subtype,it.name)==canonicalAssetType(subtype,name)
                                }
                                if(existing!=null) {savedId=existing.id;return@save current}
                            }
                            val latest=if(a==null) null else Assets.accounts(current).find {it.id==a.id}
                            val merged=if(latest==null) draft else draft.copy(
                                balance=latest.balance,currency=latest.currency,asOf=latest.asOf,history=latest.history,
                                verified=latest.verified,sourceMissing=latest.sourceMissing,sourceUnverified=latest.sourceUnverified,sourceName=latest.sourceName,
                                cnyRate=draft.cnyRate.takeIf {draft.currency==latest.currency},
                                eligibleAmount=if(knownPlatformAmount!=null || latest.eligibleFromPlatform) latest.eligibleAmount else draft.eligibleAmount,
                                eligibleBasisBalance=if(knownPlatformAmount!=null || latest.eligibleFromPlatform) latest.eligibleBasisBalance else draft.eligibleBasisBalance,
                                eligibleFromPlatform=latest.eligibleFromPlatform)
                            Assets.put(current,merged)
                        }
                        onSaved(savedId)
                    } catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "账户保存失败"} finally {busy=false}
                }
            },modifier=Modifier.fillMaxWidth()) {Text(if(busy) "正在保存…" else "保存账户")}
        }
    }
}

@Composable private fun AssetBalanceEditor(a:AssetAccount,save:suspend ((Ledger)->Ledger)->Unit,onSaved:()->Unit) {
    var amount by rememberSaveable(a.id) {mutableStateOf(a.balance)}
    var date by rememberSaveable(a.id) {mutableStateOf(LocalDate.now().toString())}
    var correction by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(a.name,fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("原余额 ${assetMoney(BigDecimal(a.balance),a.currency)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(if(a.category==AssetCategory.DEBT) "新的剩余欠款" else "新的当前余额",amount,{amount=it})
        Field("余额日期",date,{date=it},dateField=true)
        amount.toBigDecimalOrNull()?.let {Text("余额差额 ${assetMoney(it-BigDecimal(a.balance),a.currency)}")}
        TextButton(onClick={correction=!correction}) {Text(if(correction) "取消纠正上次录入" else "纠正上次录入")}
        if(correction) Text("将替换最近一条余额记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy,onClick={busy=true;scope.launch {try {save {Assets.updateBalance(it,a.id,amount.trim(),date,correction)};onSaved()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "余额保存失败"} finally {busy=false}}},modifier=Modifier.fillMaxWidth()) {Text(if(busy) "正在保存…" else "保存余额")}
    }
}

@Composable private fun AssetTrend(points:List<NetWorthPoint>,hidden:Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Hint("只展示实际记录过的余额。统计范围调整和余额校正不代表收入，未更新的日期不补造数据。")
        if(points.isEmpty()) Hint("添加账户或更新余额后，这里会出现净资产变化。")
        if(points.size>1 && !hidden) {
            val values=points.map {(BigDecimal(it.assets)-BigDecimal(it.liabilities)).toFloat()}
            val min=values.min();val max=values.max();val span=(max-min).takeIf {it>0} ?: 1f
            val times=points.map {java.time.Instant.parse(it.at).toEpochMilli()}
            val duration=(times.last()-times.first()).coerceAtLeast(1L)
            val color=MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                values.zipWithNext().forEachIndexed {i,(a,b)->drawLine(color,Offset((times[i]-times.first()).toFloat()/duration*size.width,size.height-(a-min)/span*size.height),Offset((times[i+1]-times.first()).toFloat()/duration*size.width,size.height-(b-min)/span*size.height),strokeWidth=3.dp.toPx())}
            }
            val dateFormat=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(dateFormat.format(java.time.Instant.parse(points.first().at)),style=MaterialTheme.typography.labelSmall);Text(dateFormat.format(java.time.Instant.parse(points.last().at)),style=MaterialTheme.typography.labelSmall)}
        }
        points.asReversed().forEach {p->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text(p.reason);Text(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(p.at)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Column(horizontalAlignment=Alignment.End) {Text(if(hidden) "••••" else assetMoney(BigDecimal(p.assets)-BigDecimal(p.liabilities)),fontWeight=FontWeight.SemiBold);if(p.pending>0) Text("${p.pending} 个待核实",style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
