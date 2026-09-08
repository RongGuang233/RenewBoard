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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var forecastOpen by rememberSaveable { mutableStateOf(false) }
    var accountHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentPlanId by rememberSaveable {mutableStateOf<String?>(null)}
    var paymentOnlyRecord by rememberSaveable {mutableStateOf(false)}
    var balanceAction by rememberSaveable {mutableStateOf<BalanceAction?>(null)}
    val pageStates=rememberSaveableStateHolder()
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    fun message(s: String) { scope.launch { snack.showSnackbar(s) } }
    fun change(f: (Ledger)->Ledger) { scope.launch { try { repo.update(f) } catch(e: Exception) { message(e.message ?: "未能保存，请检查输入") } } }
    LaunchedEffect(incoming,received) {
        val intent=incoming ?: return@LaunchedEffect
        if(received==null) return@LaunchedEffect
        val id=intent.getStringExtra("planId")
        if(id!=null) {
            if(ledger.plans.any {it.id==id}) {
                creating=false;editId=null;receiptId=null;forecastOpen=false;accountHistoryId=null;detailId=id;tab=1
                paymentOnlyRecord=false;balanceAction=null
                paymentPlanId=if(intent.getStringExtra("subscriptionAction")=="pay" && ledger.plans.single{it.id==id}.balanceAccount==null) id else null
                when(intent.getStringExtra("subscriptionAction")) {
                    "snooze" -> {Jobs.snooze(c,id,ledger);message("已安排明天提醒")}
                }
            } else message("这项订阅已删除")
        }
        onIntentHandled()
    }
    LaunchedEffect(receiptId,ledger.payments,received) {
        if(received!=null && receiptId!=null && ledger.payments.none {it.id==receiptId}) receiptId=null
    }
    val hasPreviousPage=creating || editId!=null || detailId!=null
    fun goBack() {
        when {
            creating -> creating=false
            editId!=null -> editId=null
            else -> detailId=null
        }
    }
    BackHandler(enabled=hasPreviousPage && !creating && editId==null && paymentPlanId==null && balanceAction==null) { goBack() }
    Scaffold(snackbarHost={SnackbarHost(snack)}, topBar={
        if(hasPreviousPage && !creating && editId==null && paymentPlanId==null && balanceAction==null && receiptId==null && accountHistoryId==null) Surface(color=Paper) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=8.dp)) {
                PageBack(back=::goBack)
            }
        }
    }, bottomBar={ if(!hasPreviousPage && !subpage && paymentPlanId==null && receiptId==null && !forecastOpen && accountHistoryId==null) NavigationBar(containerColor=Paper) {
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
        } else if(balanceAction!=null) {
            val plan=ledger.plans.find {it.id==detailId && it.balanceAccount!=null}
            if(plan==null) {LaunchedEffect(detailId) {balanceAction=null}} else {
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).systemBarsPadding()) {
                    key(plan.id,balanceAction) {BalanceEditor(ledger,plan,balanceAction!!,close={balanceAction=null}) {transform ->
                        repo.update(transform);message("已保存")
                    }}
                }
            }
        } else if(paymentPlanId!=null) {
            val plan=ledger.plans.find{it.id==paymentPlanId}
            if(plan==null) {paymentPlanId=null} else Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).systemBarsPadding()) {
                key(plan.id) {PaymentEditor(ledger,plan,paymentOnlyRecord,close={paymentPlanId=null}) {transform ->repo.update(transform);message("已记录付款")}}
            }
        } else if(receiptId!=null && receipt!=null) {
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {PaymentDetailScreen(ledger,receipt,{receiptId=null},::change)}
        } else if(accountHistoryId!=null) {
            Box(Modifier.fillMaxSize().padding(padding)) {PlanHistoryScreen(ledger,accountHistoryId!!,{accountHistoryId=null},::change)}
        } else if(forecastOpen && !hasPreviousPage) {
            pageStates.SaveableStateProvider("forecast") {
                Box(Modifier.fillMaxSize().padding(padding)) {ForecastScreen(ledger,{forecastOpen=false},{detailId=it})}
            }
        } else if(!hasPreviousPage && tab>=2) {
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when(tab) {
                    2 -> pageStates.SaveableStateProvider("ledger") {LedgerScreen(ledger,::change) { subpage=it }}
                    3 -> pageStates.SaveableStateProvider("devices") {DevicesScreen(ledger,::change) { subpage=it }}
                    4 -> SettingsScreen(ledger,::message) { subpage=it }
                }
            }
        } else if(detailId!=null) {
            val p=ledger.plans.find {it.id==detailId}
            if(p==null) {LaunchedEffect(detailId) {detailId=null}} else key(p.id) {
                val deleted={DraftStore(c).remove("subscription:${p.id}");DraftStore(c).remove("payment:${p.id}");detailId=null}
                if(p.balanceAccount!=null) Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp).imePadding().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
                    BalanceDetail(ledger,p,{editId=p.id},::change,deleted,{receiptId=it},{accountHistoryId=p.id},openAction={balanceAction=it})
                } else Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    SubscriptionDetailScreen(ledger,p,onEdit={editId=p.id},change=::change,onDeleted=deleted,
                        openPayment={receiptId=it},openHistory={accountHistoryId=p.id},recordPayment={only->paymentOnlyRecord=only;paymentPlanId=p.id})
                }
            }
        } else pageStates.SaveableStateProvider(if(tab==0) "home" else "subscriptions") {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp).imePadding().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
                when(tab) {
                    0 -> Overview(ledger,onAdd={creating=true},openForecast={forecastOpen=true}) {detailId=it}
                    1 -> Subscriptions(ledger,onAdd={creating=true}) {detailId=it}
                }
            }
        }
    }
}

@Composable private fun Overview(l: Ledger, onAdd:()->Unit, openForecast:()->Unit, open: (String)->Unit) {
    val today=LocalDate.now()
    val upcoming=l.benefits.filter { b->l.plans.any { it.id==b.planId && !it.archived && it.balanceAccount==null } }.sortedBy { Book.expiry(it,l.plans.single { p->p.id==it.planId }) }
    var showExpired by rememberSaveable { mutableStateOf(false) }
    val forecast=Book.forecastSummary(l,today,today.plusDays(30))
    val soon=upcoming.count { ChronoUnit.DAYS.between(today,Book.expiry(it,l.plans.single { p->p.id==it.planId })) in 0..7 }
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("订阅簿",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        Text("${today.monthValue} 月 ${today.dayOfMonth} 日",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalIconButton(onClick=onAdd,modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Add,"记一笔订阅")}
    }
    Card(onClick=openForecast,colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal=20.dp,vertical=16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("未来 30 天预计扣款",color=Color.White.copy(alpha=.85f),fontSize=14.sp)
                Text("预计扣款 ${Book.forecastCharges(l,today,today.plusDays(30)).size} 笔",color=Color.White.copy(alpha=.85f),fontSize=13.sp)
            }
            Text("¥${forecast.known.setScale(2,RoundingMode.HALF_UP)}",fontSize=34.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(top=8.dp))
            if(forecast.missingPlanIds.isNotEmpty()) {
                Text("已知金额 · ${forecast.missingPlanIds.size} 项待补录",color=Color.White)
                forecast.missingPlanIds.forEach { id -> TextButton(onClick={open(id)}) {Text("补录 ${l.plans.single{it.id==id}.name} →",color=Color.White)} }
            }
        }
    }
    val balances=l.plans.filter { it.balanceAccount!=null && !it.archived }
    val urgentBalances=balances.filter {Prepaid.balance(it,today).signum()<0 || Prepaid.rechargeDate(it)?.let {d->d<=today.plusDays(7)}==true}
    if(urgentBalances.isNotEmpty()) {
        Section("话费待充值")
        urgentBalances.forEach {BalanceCard(it,compact=true) {open(it.id)}}
    }
    if(upcoming.isNotEmpty() || balances.isEmpty()) {
    Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
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
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(benefit.name,size=36.dp)
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
    val healthyBalances=balances.filterNot {it in urgentBalances}
    if(healthyBalances.isNotEmpty()) {
        Section("话费余额")
        healthyBalances.forEach {BalanceCard(it,compact=true) {open(it.id)}}
    }
}

@Composable private fun Subscriptions(l: Ledger, onAdd:()->Unit, open: (String)->Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable {mutableStateOf("")}
    var renewalFilter by rememberSaveable {mutableStateOf("全部")}
    var sort by rememberSaveable {mutableStateOf("最近到期")}
    var filters by remember {mutableStateOf(false)}
    var costHelp by remember {mutableStateOf(false)}
    val today=LocalDate.now()
    val comparing=sort in listOf("单次金额","折合月费")
    Title("我的订阅",action=onAdd)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Field("搜索订阅",query,{query=it},Modifier.weight(1f))
        Box {
            IconButton(onClick={filters=true}) {Icon(Icons.Outlined.Tune,"订阅筛选与排序")}
            DropdownMenu(filters,{filters=false}) {
                listOf("全部","自动续费","手动续费").forEach {value->DropdownMenuItem(text={Text((if(renewalFilter==value) "✓ " else "")+value)},onClick={renewalFilter=value;filters=false})}
                HorizontalDivider()
                listOf("最近到期","名称","单次金额","折合月费").forEach {value->DropdownMenuItem(text={Text((if(sort==value) "✓ " else "")+value)},onClick={sort=value;filters=false})}
            }
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!archived,{archived=false},label={Text("使用中")})
        FilterChip(archived,{archived=true},label={Text("已归档")})
        Spacer(Modifier.weight(1f))
        TextButton(onClick={filters=true},contentPadding=PaddingValues(horizontal=4.dp)) {
            Text(sort,fontSize=13.sp);Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
        }
    }
    if(renewalFilter!="全部") InputChip(selected=true,onClick={renewalFilter="全部"},label={Text(renewalFilter)},
        trailingIcon={Icon(Icons.Outlined.Close,null,Modifier.size(16.dp))},modifier=Modifier.semantics {contentDescription="清除续费筛选"})
    if(comparing) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("预计金额，仅用于比较",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
        TextButton(onClick={costHelp=true}) {Text("折算说明")}
    }
    val filtered=l.plans.filter { it.archived==archived && SubscriptionList.matches(l,it,query) && (renewalFilter=="全部" || it.autoRenew==(renewalFilter=="自动续费")) }
    val plans=when(sort) {
        "名称" -> filtered.sortedBy{it.name}
        "单次金额","折合月费" -> SubscriptionList.sortByCost(l,filtered,sort=="折合月费",today)
        else -> filtered.sortedBy {p->if(p.balanceAccount!=null) Prepaid.rechargeDate(p) ?: LocalDate.MAX else l.benefits.filter{it.planId==p.id}.minOfOrNull{Book.expiry(it,p)} ?: LocalDate.MAX}
    }
    if(plans.isEmpty()) Hint(if(query.isNotBlank() || renewalFilter!="全部") "没有匹配的订阅" else if(archived) "没有归档的订阅" else "添加你的第一项会员。")
    plans.forEach { plan ->
        val original=displayMoney(plan.currency,SubscriptionList.originalAmount(plan,today).toPlainString())
        val period=SubscriptionList.period(plan)
        val compared=if(comparing) SubscriptionList.comparisonCny(l,plan,sort=="折合月费",today) else null
        val amountText=if(!comparing) original else compared?.let {
            (if(sort=="折合月费" || plan.currency!="CNY") "≈" else "")+displayMoney("CNY",it.toPlainString())
        } ?: "待补人民币"
        val caption=when {
            comparing && (plan.currency!="CNY" || (sort=="折合月费" && (plan.cycle!=Cycle.MONTH || plan.interval!=1) && plan.balanceAccount==null)) -> "$original / $period"
            plan.balanceAccount!=null -> "每月月费"
            else -> "每$period"+if(plan.autoRenew) "" else " · 手动"
        }
        if(plan.balanceAccount!=null) { BalanceCard(plan,comparison=if(comparing) amountText to caption else null) {open(plan.id)};return@forEach }
        val matching=if(query.isNotBlank() && !plan.name.contains(query.trim(),ignoreCase=true)) SubscriptionList.matchingBenefits(l,plan,query) else emptyList()
        val expiry=l.benefits.filter { it.planId==plan.id }.minOfOrNull { Book.expiry(it,plan) }
        Card(onClick={open(plan.id)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(plan.name)
                Column(Modifier.weight(1f)) {
                    Text(plan.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    if(matching.isNotEmpty()) Text("包含：${matching.first()}"+if(matching.size>1) "等${matching.size}项" else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(expiry?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日到期" } ?: "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text(amountText,fontSize=17.sp,fontWeight=FontWeight.Bold)
                    Text(caption,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if(costHelp) AlertDialog(onDismissRequest={costHelp=false},title={Text("金额比较口径")},
        text={Text("单次金额比较每个扣费周期的设定价格，话费使用当前月费。\n\n折合月费按周期平均：年付除以12，周付按一年52周折算；多周、多月或多年再按周期倍数计算。\n\n外币按该订阅最近一次已记录付款的人民币实付比例估算，无依据时标为待补录并排在末尾。比较值不写入账本，也不改变历史付款。")},
        confirmButton={TextButton(onClick={costHelp=false}) {Text("知道了")}})
}
