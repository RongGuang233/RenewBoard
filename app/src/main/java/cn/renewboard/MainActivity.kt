package cn.renewboard

import android.os.Bundle
import android.content.Intent
import kotlinx.coroutines.flow.map
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.math.RoundingMode

private val Ink = Color(0xFF152B49)
private val Paper = Color(0xFFF5F7FC)
private val Leaf = Color(0xFF245BD6)
private val Amber = Color(0xFFAD501F)
private val Palette = lightColorScheme(primary=Leaf, onPrimary=Color.White, background=Paper, surface=Paper, surfaceVariant=Color(0xFFE8EEF9), secondary=Amber, onBackground=Ink,onSurface=Ink, primaryContainer=Color(0xFFE2EBFF),onPrimaryContainer=Ink,secondaryContainer=Color(0xFFE2EBFF),onSecondaryContainer=Ink,tertiary=Amber,tertiaryContainer=Color(0xFFF1E7D2),onTertiaryContainer=Ink,surfaceTint=Leaf,surfaceContainer=Color(0xFFF0F3FA),surfaceContainerHigh=Color(0xFFEAF0FB),surfaceContainerHighest=Color(0xFFE2EAF8),surfaceContainerLow=Color(0xFFF4F6FC),surfaceContainerLowest=Paper)
class MainActivity: ComponentActivity() {
    private var incoming by mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); incoming=intent; setContent { MaterialTheme(colorScheme=Palette) { RenewBoard(incoming) {incoming=null;intent?.apply {removeExtra("planId");removeExtra("subscriptionAction");data=null}} } } }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);incoming=intent}
}
@Composable private fun Title(text: String, sub: String? = null, action:(()->Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(text,fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold); if(sub!=null) Text(sub,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
        if(action!=null) FilledTonalIconButton(onClick=action,modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Add,contentDescription="记一笔订阅") }
    }
}
@Composable private fun Section(text: String) { Text(text,fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=20.dp,bottom=8.dp)) }
@Composable internal fun Field(label: String, value: String, change: (String)->Unit, modifier: Modifier = Modifier, secret: Boolean=false, dateField: Boolean=false, keyboardType: KeyboardType=KeyboardType.Text) {
    if(secret) OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
    else {
        var choosingDate by remember { mutableStateOf(false) }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=keyboardType),
            trailingIcon=if(dateField) {{ IconButton(onClick={
                focus.clearFocus(); keyboard?.hide(); choosingDate=true
            }) { Icon(Icons.Outlined.CalendarMonth,contentDescription="选择日期") } }} else null)
        if(choosingDate) CalendarDateDialog(label,value,{choosingDate=false}) {
            change(it); choosingDate=false
        }
    }
}
@Composable internal fun MoneyField(label:String,value:String,change:(String)->Unit,modifier:Modifier=Modifier) = Field(label,value,change,modifier,keyboardType=KeyboardType.Decimal)
@Composable internal fun PageBack(description:String="返回",back:()->Unit) {
    FilledTonalButton(onClick=back,contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=description}) {
        Icon(Icons.Outlined.ArrowBack,null,Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("返回")
    }
}
@Composable internal fun CurrencyPicker(value:String,compact:Boolean=false,change:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) };var query by remember {mutableStateOf("")}
    val names=linkedMapOf("CNY" to "人民币 ¥","USD" to "美元 $","EUR" to "欧元 €","HKD" to "港币 HK$","JPY" to "日元 JP¥","GBP" to "英镑 £","TWD" to "新台币 NT$","SGD" to "新加坡元 S$","AUD" to "澳元 A$")
    Box {
        OutlinedButton(onClick={open=true},modifier=Modifier.semantics {contentDescription="选择币种"}) {Text(if(compact) value else names[value] ?: value);Icon(Icons.Outlined.ExpandMore,null)}
        DropdownMenu(open,{open=false}) {
            names.forEach { (code,name)->DropdownMenuItem(text={Text(name)},onClick={change(code);open=false})}
            DropdownMenuItem(text={Text("其他币种")},onClick={open=false;other=true;query=""})
        }
    }
    if(other) AlertDialog(onDismissRequest={other=false},title={Text("选择币种")},text={Column {
        Field("搜索币种",query,{query=it})
        val currencies=remember {java.util.Currency.getAvailableCurrencies().sortedBy{it.currencyCode}}
        Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
            currencies.filter {it.currencyCode.contains(query,true) || it.getDisplayName(java.util.Locale.SIMPLIFIED_CHINESE).contains(query,true)}.forEach {currency ->
                TextButton(onClick={change(currency.currencyCode);other=false},modifier=Modifier.fillMaxWidth()) {Text("${currency.getDisplayName(java.util.Locale.SIMPLIFIED_CHINESE)} · ${currency.currencyCode}")}
            }
        }
    }},confirmButton={TextButton(onClick={other=false}) {Text("取消")}})
}
internal fun displayMoney(currency:String,amount:String):String = (if(currency=="CNY") "¥" else "$currency ")+runCatching{BigDecimal(amount).setScale(2,RoundingMode.HALF_UP).toPlainString()}.getOrDefault(amount)
@Composable internal fun Hint(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp,modifier=Modifier.padding(vertical=8.dp)) }
private fun money(totals: Map<String,BigDecimal>) = if(totals.isEmpty()) "暂无费用" else totals.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value.setScale(2,RoundingMode.HALF_UP).toPlainString()}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RenewBoard(incoming:Intent?=null,onIntentHandled:()->Unit={}) {
    val c = LocalContext.current; val repo = remember { c.repository() }
    val received = remember(repo) {repo.flow.map {it as Ledger?}}.collectAsStateWithLifecycle(initialValue=null).value
    val ledger=received ?: Ledger()
    val lifecycleOwner=LocalLifecycleOwner.current
    LaunchedEffect(ledger.plans,lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            repo.recordMonthlyFees()
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var subpage by remember(tab) { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentPlanId by rememberSaveable {mutableStateOf<String?>(null)}
    var paymentOnlyRecord by rememberSaveable {mutableStateOf(false)}
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    fun message(s: String) { scope.launch { snack.showSnackbar(s) } }
    fun change(f: (Ledger)->Ledger) { scope.launch { try { repo.update(f) } catch(e: Exception) { message(e.message ?: "未能保存，请检查输入") } } }
    LaunchedEffect(incoming,received) {
        val intent=incoming ?: return@LaunchedEffect
        if(received==null) return@LaunchedEffect
        val id=intent.getStringExtra("planId")
        if(id!=null) {
            if(ledger.plans.any {it.id==id}) {
                creating=false;editId=null;receiptId=null;detailId=id;tab=1
                paymentOnlyRecord=false
                paymentPlanId=if(intent.getStringExtra("subscriptionAction")=="pay" && ledger.plans.single{it.id==id}.balanceAccount==null) id else null
                when(intent.getStringExtra("subscriptionAction")) {
                    "snooze" -> {Jobs.snooze(c,id);message("已安排明天提醒")}
                }
            } else message("这项订阅已删除")
        }
        onIntentHandled()
    }
    val hasPreviousPage=creating || editId!=null || detailId!=null
    fun goBack() {
        when {
            creating -> creating=false
            editId!=null -> editId=null
            else -> detailId=null
        }
    }
    BackHandler(enabled=hasPreviousPage && !creating && editId==null && paymentPlanId==null) { goBack() }
    Scaffold(snackbarHost={SnackbarHost(snack)}, topBar={
        if(hasPreviousPage && !creating && editId==null && paymentPlanId==null && receiptId==null) Surface(color=Paper) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=8.dp)) {
                PageBack(back=::goBack)
            }
        }
    }, bottomBar={ if(!hasPreviousPage && !subpage && paymentPlanId==null && receiptId==null) NavigationBar(containerColor=Paper) {
        listOf("到期","订阅","账本","设备","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(listOf(Icons.Outlined.Event,Icons.Outlined.Bookmarks,Icons.Outlined.ReceiptLong,Icons.Outlined.Devices,Icons.Outlined.Settings)[i],null)},label={Text(s)}) }
    } }) { padding ->
        val receipt=ledger.payments.find {it.id==receiptId}
        if(received==null) {
            Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
        } else if(creating || editId!=null) {
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).systemBarsPadding()) {
                key(editId ?: "new") {SubscriptionEditor(ledger,ledger.plans.find{it.id==editId},close={creating=false;editId=null}) {p,bs,initial ->
                    repo.update {old->old.copy(plans=old.plans.filterNot{it.id==p.id}+p,benefits=old.benefits.filterNot{it.planId==p.id}+bs,payments=old.payments+listOfNotNull(initial))}
                    detailId=null;message("已保存")
                }}
            }
        } else if(paymentPlanId!=null) {
            val plan=ledger.plans.find{it.id==paymentPlanId}
            if(plan==null) {paymentPlanId=null} else Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).systemBarsPadding()) {
                key(plan.id) {PaymentEditor(ledger,plan,paymentOnlyRecord,close={paymentPlanId=null}) {transform ->repo.update(transform);message("已记录付款")}}
            }
        } else if(receiptId!=null && receipt!=null) {
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {PaymentDetailScreen(ledger,receipt,{receiptId=null},::change)}
        } else if(!hasPreviousPage && tab>=2) {
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when(tab) {
                    2 -> LedgerScreen(ledger,::change) { subpage=it }
                    3 -> DevicesScreen(ledger,::change) { subpage=it }
                    4 -> SettingsScreen(ledger,::change,::message) { subpage=it }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp).imePadding().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
            if(detailId!=null) {
                val p = ledger.plans.find { it.id==detailId }
                if(p==null) { detailId=null } else key(p.id) {Detail(ledger,p,onEdit={editId=p.id},change=::change,onDeleted={DraftStore(c).remove("subscription:${p.id}");DraftStore(c).remove("payment:${p.id}");detailId=null},openPayment={receiptId=it},recordPayment={only->paymentOnlyRecord=only;paymentPlanId=p.id})}
            } else when(tab) {
                0 -> Overview(ledger,onAdd={creating=true}) { detailId=it }
                1 -> Subscriptions(ledger,onAdd={creating=true}) { detailId=it }
            }
        }
    }
}

@Composable private fun Overview(l: Ledger, onAdd:()->Unit, open: (String)->Unit) {
    val today=LocalDate.now()
    val upcoming=l.benefits.filter { b->l.plans.any { it.id==b.planId && !it.archived && it.balanceAccount==null } }.sortedBy { Book.expiry(it,l.plans.single { p->p.id==it.planId }) }
    var showExpired by rememberSaveable { mutableStateOf(false) }
    val forecast=Book.forecastSummary(l,today,today.plusDays(30))
    val soon=upcoming.count { ChronoUnit.DAYS.between(today,Book.expiry(it,l.plans.single { p->p.id==it.planId })) in 0..7 }
    Title("订阅簿", "${today.monthValue} 月 ${today.dayOfMonth} 日",onAdd)
    Card(colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("未来 30 天预计扣款",color=Color.White.copy(alpha=.8f),fontSize=15.sp)
            Text("¥${forecast.known.setScale(2,RoundingMode.HALF_UP)}",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(vertical=16.dp))
            if(forecast.missingPlanIds.isNotEmpty()) {
                Text("已知金额 · ${forecast.missingPlanIds.size} 项待补录",color=Color.White)
                forecast.missingPlanIds.forEach { id -> TextButton(onClick={open(id)}) {Text("补录 ${l.plans.single{it.id==id}.name} →",color=Color.White)} }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${l.plans.count { !it.archived }} 个订阅",color=Color.White.copy(alpha=.8f))
                Text("自动续费",color=Color.White.copy(alpha=.8f))
            }
        }
    }
    val balances=l.plans.filter { it.balanceAccount!=null && !it.archived }
    if(balances.isNotEmpty()) {
        Section("话费余额")
        balances.forEach { BalanceCard(it) { open(it.id) } }
    }
    if(upcoming.isNotEmpty() || balances.isEmpty()) {
    Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text("最近到期",fontSize=22.sp,fontWeight=FontWeight.Bold)
        if(soon>0) Surface(color=Color(0xFFFFE9D8),shape=RoundedCornerShape(12.dp)) { Text("本周 $soon 项",color=Amber,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp)) }
    }
    if(upcoming.isEmpty()) { Text("还没有需要记挂的到期日",fontWeight=FontWeight.SemiBold); Hint("添加订阅，开始记录。") }
    }
    val expired=upcoming.filter { Book.expiry(it,l.plans.single {p->p.id==it.planId})<today }
    if(expired.isNotEmpty()) TextButton(onClick={showExpired=!showExpired}) {Text("已过期 ${expired.size} 项");Icon(if(showExpired) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
    upcoming.filter {showExpired || it !in expired}.forEach { benefit ->
        val plan=l.plans.single { it.id==benefit.planId }; val date=Book.expiry(benefit,plan); val days=ChronoUnit.DAYS.between(today,date)
        Card(onClick={open(plan.id)},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(benefit.name)
                Column(Modifier.weight(1f)) {
                    Text(benefit.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    Text("${date.monthValue} 月 ${date.dayOfMonth} 日" + if(benefit.name!=plan.name) " · ${plan.name}" else "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text(if(days<0) "已到期" else if(days==0L) "今天" else "$days",fontSize=if(days>0) 26.sp else 18.sp,fontWeight=FontWeight.Bold,color=if(days<=7) Amber else Leaf)
                    if(days>0) Text("天后",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable private fun Subscriptions(l: Ledger, onAdd:()->Unit, open: (String)->Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable {mutableStateOf("")}
    var renewalFilter by rememberSaveable {mutableStateOf("全部")}
    var sort by rememberSaveable {mutableStateOf("最近到期")}
    var filters by remember {mutableStateOf(false)}
    Title("我的订阅",action=onAdd)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Field("搜索订阅",query,{query=it},Modifier.weight(1f))
        Box {
            IconButton(onClick={filters=true}) {Icon(Icons.Outlined.Tune,"订阅筛选与排序")}
            DropdownMenu(filters,{filters=false}) {
                listOf("全部","自动续费","手动续费").forEach {value->DropdownMenuItem(text={Text((if(renewalFilter==value) "✓ " else "")+value)},onClick={renewalFilter=value;filters=false})}
                HorizontalDivider()
                listOf("最近到期","名称","金额").forEach {value->DropdownMenuItem(text={Text((if(sort==value) "✓ " else "")+value)},onClick={sort=value;filters=false})}
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(!archived,{archived=false},label={Text("使用中")}); FilterChip(archived,{archived=true},label={Text("已归档")}) }
    val filtered=l.plans.filter { it.archived==archived && it.name.contains(query.trim(),ignoreCase=true) && (renewalFilter=="全部" || it.autoRenew==(renewalFilter=="自动续费")) }
    val plans=when(sort) {
        "名称" -> filtered.sortedBy{it.name}
        "金额" -> filtered.sortedWith(compareBy<Plan>{it.currency}.thenByDescending{BigDecimal(it.amount)})
        else -> filtered.sortedBy {p->if(p.balanceAccount!=null) Prepaid.rechargeDate(p) ?: LocalDate.MAX else l.benefits.filter{it.planId==p.id}.minOfOrNull{Book.expiry(it,p)} ?: LocalDate.MAX}
    }
    if(plans.isEmpty()) Hint(if(query.isNotBlank() || renewalFilter!="全部") "没有匹配的订阅" else if(archived) "没有归档的订阅" else "添加你的第一项会员。")
    plans.forEach { plan ->
        if(plan.balanceAccount!=null) { BalanceCard(plan) { open(plan.id) }; return@forEach }
        val expiry=l.benefits.filter { it.planId==plan.id }.minOfOrNull { Book.expiry(it,plan) }
        Card(onClick={open(plan.id)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(plan.name)
                Column(Modifier.weight(1f)) { Text(plan.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold); Text(expiry?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日到期" } ?: "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp)) }
                Column(horizontalAlignment=Alignment.End) { Text(displayMoney(plan.currency,plan.amount),fontSize=17.sp,fontWeight=FontWeight.Bold); Text(if(plan.autoRenew) "每 ${plan.interval} ${plan.cycle.label}" else "手动续费",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
@Composable private fun Detail(l: Ledger,p: Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit,openPayment:(String)->Unit={},recordPayment:(Boolean)->Unit={}) {
    if(p.balanceAccount!=null) { BalanceDetail(l,p,onEdit,change,onDeleted); return }
    var deleting by remember { mutableStateOf(false) }
    var more by remember {mutableStateOf(false)}
    var giftId by remember { mutableStateOf<String?>(null) }; var gift by remember { mutableStateOf("7") }
    val context=LocalContext.current
    var snoozed by remember {mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ServiceIcon(p.name,size=40.dp)
        Column(Modifier.weight(1f)) {Text(p.name,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("${displayMoney(p.currency,p.amount)} / ${p.interval} ${p.cycle.label}",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Box {
            IconButton(onClick={more=true}) {Icon(Icons.Outlined.MoreVert,"订阅更多操作")}
            DropdownMenu(more,{more=false}) {
                DropdownMenuItem(text={Text("编辑订阅与到期日")},onClick={more=false;onEdit()})
                DropdownMenuItem(text={Text("仅补记付款")},onClick={more=false;recordPayment(true)})
                DropdownMenuItem(text={Text("明天提醒")},onClick={more=false;Jobs.snooze(context,p.id);snoozed=true})
                DropdownMenuItem(text={Text(if(p.archived) "恢复使用" else "归档订阅")},onClick={more=false;change {it.copy(plans=it.plans.map {x->if(x.id==p.id)x.copy(archived=!x.archived) else x})}})
                DropdownMenuItem(text={Text("删除订阅",color=MaterialTheme.colorScheme.error)},onClick={more=false;deleting=true})
            }
        }
    }
    if(p.autoRenew) Hint("下次预计扣款 ${Book.nextCharge(p,LocalDate.now())}") else Hint("已标记关闭自动续费 · 有效期提醒仍保留")
    if(snoozed) Hint("已安排明天提醒")
    l.benefits.filter {it.planId==p.id}.forEach {b -> Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) {Column(Modifier.padding(16.dp)) {
        Text(b.name,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text("${Book.expiry(b,p)} 到期",modifier=Modifier.padding(top=6.dp))
        if(b.giftDays>0) Hint("含赠送 ${b.giftDays} 天")
        TextButton(onClick={giftId=b.id}) {Text("增加赠送时长")}
    }}}
    val missing=l.payments.filter {it.planId==p.id && it.currency!="CNY" && it.cnyAmount==null}
    if(missing.isNotEmpty()) OutlinedButton(onClick={openPayment(missing.first().id)},modifier=Modifier.fillMaxWidth()) {Text("补录人民币（${missing.size} 笔）")}
    else if(p.currency!="CNY" && l.payments.none {it.planId==p.id && it.cnyAmount!=null}) OutlinedButton(onClick={recordPayment(true)},modifier=Modifier.fillMaxWidth()) {Text("补记付款与人民币金额")}
    Button(onClick={recordPayment(false)},modifier=Modifier.fillMaxWidth()) {Text("记录付款 / 提前续费")}
    TextButton(onClick={change {it.copy(plans=it.plans.map {x->if(x.id==p.id)x.copy(autoRenew=!x.autoRenew) else x})}},modifier=Modifier.fillMaxWidth()) {Text(if(p.autoRenew) "标记已关闭自动续费" else "标记已开启自动续费")}
    if(p.note.isNotBlank()) Hint(p.note)
    Section("此套餐已付记录")
    l.payments.filter {it.planId==p.id}.sortedByDescending {it.date}.forEach {receipt->
        TextButton(onClick={openPayment(receipt.id)},modifier=Modifier.fillMaxWidth()) {
            Text(receipt.date,modifier=Modifier.weight(1f));Text(displayMoney(receipt.currency,receipt.amount));Icon(Icons.Outlined.ChevronRight,null)
        }
    }
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) {include->change {Book.delete(it,p.id,include)};deleting=false;onDeleted()}
    if(giftId!=null) AlertDialog(onDismissRequest={giftId=null},title={Text("赠送时长")},text={Field("增加天数",gift,{gift=it},keyboardType=KeyboardType.Number)},confirmButton={TextButton(onClick={val n=gift.toIntOrNull();if(n!=null && n in 1..36500) {val id=giftId;change {it.copy(benefits=it.benefits.map {b->if(b.id==id)b.copy(giftDays=b.giftDays+n) else b})};giftId=null}}) {Text("增加")}},dismissButton={TextButton(onClick={giftId=null}) {Text("取消")}})
}
