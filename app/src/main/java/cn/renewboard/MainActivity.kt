package cn.renewboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
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
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme(colorScheme=Palette) { RenewBoard() } } }
}
@Composable private fun Title(text: String, sub: String? = null, action:(()->Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(text,fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold); if(sub!=null) Text(sub,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
        if(action!=null) FilledTonalIconButton(onClick=action,modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Add,contentDescription="记一笔订阅") }
    }
}
@Composable private fun Section(text: String) { Text(text,fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=20.dp,bottom=8.dp)) }
@Composable internal fun Field(label: String, value: String, change: (String)->Unit, modifier: Modifier = Modifier, secret: Boolean=false, dateField: Boolean=false) {
    if(secret) OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
    else {
        val c = LocalContext.current
        OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,
            trailingIcon=if(dateField) {{ IconButton(onClick={
                val date=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
                android.app.DatePickerDialog(c,{_,y,m,d->change(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
            }) { Icon(Icons.Outlined.CalendarMonth,contentDescription="选择日期") } }} else null)
    }
}
@Composable private fun Hint(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp,modifier=Modifier.padding(vertical=8.dp)) }
private fun money(totals: Map<String,BigDecimal>) = if(totals.isEmpty()) "暂无费用" else totals.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value.setScale(2,RoundingMode.HALF_UP).toPlainString()}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RenewBoard() {
    val c = LocalContext.current; val repo = remember { c.repository() }
    val ledger by repo.flow.collectAsStateWithLifecycle(initialValue=Ledger())
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
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    fun message(s: String) { scope.launch { snack.showSnackbar(s) } }
    fun change(f: (Ledger)->Ledger) { scope.launch { try { repo.update(f) } catch(e: Exception) { message(e.message ?: "未能保存，请检查输入") } } }
    val hasPreviousPage=creating || editId!=null || detailId!=null
    fun goBack() {
        when {
            creating -> creating=false
            editId!=null -> editId=null
            else -> detailId=null
        }
    }
    BackHandler(enabled=hasPreviousPage) { goBack() }
    Scaffold(snackbarHost={SnackbarHost(snack)}, topBar={
        if(hasPreviousPage) Surface(color=Paper) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=8.dp)) {
                FilledTonalButton(onClick=::goBack,modifier=Modifier.heightIn(min=48.dp)) {
                    Icon(Icons.Outlined.ArrowBack,null)
                    Spacer(Modifier.width(8.dp))
                    Text("返回",fontWeight=FontWeight.SemiBold,fontSize=16.sp)
                }
            }
        }
    }, bottomBar={ if(!hasPreviousPage && !subpage) NavigationBar(containerColor=Paper) {
        listOf("到期","订阅","账本","设备","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(listOf(Icons.Outlined.Event,Icons.Outlined.Bookmarks,Icons.Outlined.ReceiptLong,Icons.Outlined.Devices,Icons.Outlined.Settings)[i],null)},label={Text(s)}) }
    } }) { padding ->
        if(!hasPreviousPage && tab>=2) {
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when(tab) {
                    2 -> LedgerScreen(ledger,::change) { subpage=it }
                    3 -> DevicesScreen(ledger,::change) { subpage=it }
                    4 -> SettingsScreen(ledger,::change,::message) { subpage=it }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp).imePadding().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
            if(creating || editId!=null) {
                PlanEditor(ledger,ledger.plans.find { it.id==editId }) { p,bs,initial ->
                    scope.launch { try {
                        repo.update { old -> old.copy(plans=old.plans.filterNot { it.id==p.id }+p, benefits=old.benefits.filterNot { it.planId==p.id }+bs, payments=old.payments+listOfNotNull(initial)) }
                        creating=false; editId=null; detailId=null; message("已保存")
                    } catch(e: Exception) { message(e.message ?: "输入无效") } }
                }
            } else if(detailId!=null) {
                val p = ledger.plans.find { it.id==detailId }
                if(p==null) { detailId=null } else Detail(ledger,p,onEdit={editId=p.id},change=::change,onDeleted={detailId=null})
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
    val soon=upcoming.count { ChronoUnit.DAYS.between(today,Book.expiry(it,l.plans.single { p->p.id==it.planId })) in 0..7 }
    Title("订阅簿", "${today.monthValue} 月 ${today.dayOfMonth} 日",onAdd)
    Card(colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("未来 30 天预计扣款",color=Color.White.copy(alpha=.8f),fontSize=15.sp)
            Text(Book.forecastCny(l,today,today.plusDays(30))?.let { "¥${it.setScale(2,RoundingMode.HALF_UP)}" } ?: "待补录人民币",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(vertical=16.dp))
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
    upcoming.forEach { benefit ->
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
    Title("我的订阅",action=onAdd)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(!archived,{archived=false},label={Text("使用中")}); FilterChip(archived,{archived=true},label={Text("已归档")}) }
    val plans=l.plans.filter { it.archived==archived }
    if(plans.isEmpty()) Hint(if(archived) "没有归档的订阅" else "添加你的第一项会员。")
    plans.forEach { plan ->
        if(plan.balanceAccount!=null) { BalanceCard(plan) { open(plan.id) }; return@forEach }
        val expiry=l.benefits.filter { it.planId==plan.id }.minOfOrNull { Book.expiry(it,plan) }
        Card(onClick={open(plan.id)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(plan.name)
                Column(Modifier.weight(1f)) { Text(plan.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold); Text(expiry?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日到期" } ?: "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp)) }
                Column(horizontalAlignment=Alignment.End) { Text("${plan.currency} ${plan.amount}",fontSize=17.sp,fontWeight=FontWeight.Bold); Text(if(plan.autoRenew) "每 ${plan.interval} ${plan.cycle.label}" else "手动续费",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ServicePicker(close:()->Unit, select:(ServicePreset)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    AlertDialog(onDismissRequest=close,title={Text("选择会员")},text={Column {
        Field("搜索会员",query,{query=it})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (listOf("全部")+servicePresets.map { it.category }.distinct()).forEach { group ->
                FilterChip(category==group,{category=group},label={Text(group,fontSize=12.sp)})
            }
        }
        Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
            val matches=servicePresets.filter { p -> (category=="全部" || p.category==category) && (listOf(p.name,p.category)+p.aliases).any { it.contains(query.trim(),ignoreCase=true) } }
            if(matches.isEmpty()) Hint("没有找到，可返回自定义名称。")
            matches.groupBy { it.category }.forEach { (category,items) ->
                Section(category)
                items.forEach { preset -> Row(Modifier.fillMaxWidth().clickable { select(preset) }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ServiceIcon(preset.name,size=40.dp); Text(preset.name,fontSize=16.sp)
                } }
            }
        }
    }},confirmButton={TextButton(close){Text("自定义 / 返回")}})
}

@Composable private fun PlanEditor(l: Ledger, existing: Plan?, save: (Plan,List<Benefit>,Payment?)->Unit) {
    val id=remember { existing?.id ?: newId() }
    val today=remember { LocalDate.now() }
    var prepaid by rememberSaveable { mutableStateOf(existing?.balanceAccount!=null) }
    var balance by rememberSaveable { mutableStateOf(existing?.balanceAccount?.let { Prepaid.balance(existing,today).toPlainString() } ?: "") }
    var balanceDate by rememberSaveable { mutableStateOf(today.toString()) }
    var deductionDay by rememberSaveable { mutableStateOf(existing?.billingAnchor?.let { LocalDate.parse(it).dayOfMonth.toString() } ?: "1") }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }; var amount by rememberSaveable { mutableStateOf(existing?.amount ?: "") }
    var currency by rememberSaveable { mutableStateOf(existing?.currency ?: "CNY") }; var cycle by remember { mutableStateOf(existing?.cycle ?: Cycle.MONTH) }
    var interval by rememberSaveable { mutableStateOf((existing?.interval ?: 1).toString()) }; var auto by rememberSaveable { mutableStateOf(existing?.autoRenew ?: true) }
    var anchor by rememberSaveable { mutableStateOf(existing?.billingAnchor ?: LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }; var paid by rememberSaveable { mutableStateOf(existing==null) }
    var error by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var cycleMenu by remember { mutableStateOf(false) }
    var benefitsExpanded by rememberSaveable { mutableStateOf(false) }
    var cnyAmount by rememberSaveable { mutableStateOf("") }
    var benefits by remember { mutableStateOf(l.benefits.filter { it.planId==id }.ifEmpty {
        listOf(Benefit(planId=id,name="",anchor=anchor,renewals=1))
    }) }
    var following by remember { mutableStateOf(benefits.filter {
        it.anchor==(existing?.billingAnchor ?: anchor) && it.renewals>0
    }.map { it.id }.toSet()) }
    val previewPlan=Plan(id=id,name=name,amount=amount,currency=currency,cycle=cycle,
        interval=interval.toIntOrNull()?.takeIf { it in 1..120 } ?: 1,billingAnchor=anchor)
    fun resolvedBenefit(b:Benefit):Benefit = if(b.id in following) b.copy(anchor=anchor) else b
    fun displayedExpiry(b:Benefit):String = runCatching {
        Book.expiry(resolvedBenefit(b),if(b.id in following) previewPlan else existing ?: previewPlan).toString()
    }.getOrDefault(b.anchor)
    Title(if(existing==null) "记一笔订阅" else "编辑订阅")
    if(existing==null) {
        OutlinedButton({picker=true},Modifier.fillMaxWidth()) { Icon(Icons.Outlined.GridView,null); Spacer(Modifier.width(8.dp)); Text("选择常见会员") }
        if(picker) ServicePicker({picker=false}) { preset-> name=preset.name;currency=preset.currency;
            prepaid=preset.name in listOf("中国电信","中国移动","中国联通")
            if(prepaid) anchor=today.withDayOfMonth(1).plusMonths(1).toString();benefits=benefits.mapIndexed { i,b->if(i==0)b.copy(name=preset.name) else b };picker=false }
    }
    if(name.isNotBlank()) ServiceIcon(name,modifier=Modifier.padding(vertical=12.dp),size=56.dp)
    Field("订阅 / 套餐名称",name,{name=it})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!prepaid,{prepaid=false},label={Text("周期订阅")})
        FilterChip(prepaid,{
            if(!prepaid && existing?.balanceAccount==null) anchor=today.withDayOfMonth(1).plusMonths(1).toString()
            prepaid=true
        },label={Text("话费余额")})
    }
    Field(if(prepaid) "每月扣费金额" else "套餐价格",amount,{amount=it})
    if(prepaid) {
        Field("查询到的余额",balance,{balance=it})
        Field("余额查询日期",balanceDate,{balanceDate=it},dateField=true)
        Hint("填写运营商显示的余额，以及查到这笔余额的日期，默认今天。")
        OutlinedTextField(deductionDay,{deductionDay=it},label={Text("每月扣费日")},suffix={Text("日")},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        Hint("每月 1–31 日；无该日期时按月末。查询日期之后的月费计入账本。")
    } else {
    Field("币种，如 CNY / USD",currency,{currency=it.trim().uppercase()})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(interval,{interval=it},label={Text("周期")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))
        Box {
            OutlinedButton({cycleMenu=true},Modifier.height(56.dp).semantics { contentDescription="选择周期单位" }) {
                Text(cycle.label); Spacer(Modifier.width(12.dp)); Icon(Icons.Outlined.ExpandMore,null)
            }
            DropdownMenu(expanded=cycleMenu,onDismissRequest={cycleMenu=false}) {
                Cycle.entries.forEach { v -> DropdownMenuItem(text={Text(v.label)},onClick={cycle=v;cycleMenu=false}) }
            }
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically) { Switch(auto,{auto=it}); Text("自动续费",Modifier.padding(start=12.dp)) }
    Field("扣款日期",anchor,{anchor=it},dateField=true)
    TextButton({benefitsExpanded=!benefitsExpanded},Modifier.fillMaxWidth().padding(top=12.dp)) {
        Text("包含的权益",fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        Icon(if(benefitsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(benefitsExpanded) "收起权益" else "展开权益")
    }
    if(benefitsExpanded) {
    benefits.forEachIndexed { i,b ->
        key(b.id) { Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) { Column(Modifier.padding(16.dp)) {
            Field("权益 ${i+1} 名称",b.name,{v->benefits=benefits.map { if(it.id==b.id) it.copy(name=v) else it }})
            Field("到期日期",displayedExpiry(b),{v->
                following=following-b.id
                benefits=benefits.map { if(it.id==b.id) it.copy(anchor=v,renewals=0,giftDays=0) else it }
            },dateField=true)
            if(b.id in following) Text("跟随扣款日期与周期",fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
            else TextButton({
                following=following+b.id
                benefits=benefits.map { if(it.id==b.id) it.copy(anchor=anchor,renewals=1) else it }
            }) { Text("恢复跟随周期") }
            if(benefits.size>1) TextButton({benefits=benefits.filterNot { it.id==b.id }}) { Text("移除此权益") }
        } } }
    }
    TextButton({
        val added=Benefit(planId=id,name="",anchor=anchor,renewals=1)
        benefits=benefits+added;following=following+added.id
    }) { Text("＋ 添加联合权益") }
    }
    if(existing==null) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(paid,{paid=it}); Text("同时记录首次付款") }
    if(existing==null && paid && currency!="CNY") { Field("人民币实付金额",cnyAmount,{cnyAmount=it}); Hint("填写付款当天的人民币金额，保存后固定。") }
    }
    Field("备注",note,{note=it})
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    Button(onClick={ try {
        if(prepaid) {
            require(LocalDate.parse(balanceDate)<=today) { "余额查询日期不能晚于今天" }
            existing?.balanceAccount?.let { require(LocalDate.parse(balanceDate)>=LocalDate.parse(it.asOf)) { "余额查询日期不能早于最近余额记录 ${it.asOf}" } }
        }
        val billingAnchor=if(prepaid) {
            val day=deductionDay.toIntOrNull()
            require(day!=null && day in 1..31) { "每月扣费日请填写 1–31" }
            if(existing?.balanceAccount!=null && LocalDate.parse(existing.billingAnchor).dayOfMonth==day) existing.billingAnchor
            else LocalDate.of(LocalDate.parse(balanceDate).year,1,day).toString()
        } else anchor
        val p=Plan(id,name.trim(),amount,if(prepaid) "CNY" else currency,if(prepaid) Cycle.MONTH else cycle,
            if(prepaid) 1 else interval.toInt(),if(prepaid) true else auto,billingAnchor,
            if(prepaid) 0 else existing?.paidCycles ?: if(paid) 1 else 0,existing?.archived ?: false,note,
            if(prepaid) BalanceAccount(balance,balanceDate) else null)
        val bs=if(prepaid) l.benefits.filter { it.planId==id } else benefits.map { b ->
            val resolved=resolvedBenefit(b)
            val named = if(resolved.name.isBlank() && benefits.size==1) resolved.copy(name=name.trim()) else resolved
            when {
                b.id in following -> named
                existing==null && named.renewals==0 && named.giftDays==0 &&
                    LocalDate.parse(named.anchor)==Book.advance(LocalDate.parse(anchor),cycle,p.interval.toLong()) -> named.copy(anchor=anchor,renewals=1)
                existing!=null && (existing.cycle!=cycle || existing.interval!=p.interval) ->
                    named.copy(anchor=Book.expiry(named,existing).minusDays(named.giftDays.toLong()).toString(),renewals=0)
                else -> named
            }
        }
        if(!prepaid && existing==null && paid && currency!="CNY") require(cnyAmount.isNotBlank()) { "请填写付款当天的人民币实付金额" }
        val receipt=if(!prepaid && existing==null && paid) Payment(planId=id,planName=p.name,amount=amount,currency=currency,date=anchor,note="首次付款",benefitIds=bs.map { it.id },cnyAmount=if(currency=="CNY") null else cnyAmount.trim()) else null
        Book.validate(l.copy(plans=l.plans.filterNot { it.id==id }+p,benefits=l.benefits.filterNot { it.planId==id }+bs,payments=l.payments+listOfNotNull(receipt)))
        save(p,bs,receipt)
    } catch(e: Exception) { error="请检查名称、金额、周期和日期：${e.message ?: "格式错误"}" } },modifier=Modifier.fillMaxWidth().padding(top=16.dp)) { Text("保存订阅") }
}

@Composable private fun Detail(l: Ledger,p: Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit) {
    if(p.balanceAccount!=null) { BalanceDetail(l,p,onEdit,change,onDeleted); return }
    var renewing by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf(false) }
    var giftId by remember { mutableStateOf<String?>(null) }; var gift by remember { mutableStateOf("7") }
    ServiceIcon(p.name,size=64.dp); Title(p.name,if(p.autoRenew) "每 ${p.interval} ${p.cycle.label} · ${p.currency} ${p.amount}" else "一次性 / 手动续费 · ${p.currency} ${p.amount}")
    if(p.autoRenew) Hint("下次预计扣款 ${Book.nextCharge(p,LocalDate.now())}")
    l.benefits.filter { it.planId==p.id }.forEach { b -> Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) { Column(Modifier.padding(20.dp)) { Text(b.name,fontSize=20.sp,fontWeight=FontWeight.SemiBold); Text("${Book.expiry(b,p)} 到期",modifier=Modifier.padding(top=8.dp)); if(b.giftDays>0) Hint("含赠送 ${b.giftDays} 天"); TextButton({giftId=b.id}) { Text("增加赠送时长") } } } }
    if(p.note.isNotBlank()) Hint(p.note)
    Button({renewing=true},Modifier.fillMaxWidth()) { Text("记录付款 / 提前续费") }
    OutlinedButton(onEdit,Modifier.fillMaxWidth()) { Text("编辑订阅与到期日") }
    TextButton({change { it.copy(plans=it.plans.map { x->if(x.id==p.id)x.copy(archived=!x.archived) else x }) }}) { Text(if(p.archived) "恢复使用" else "归档订阅") }
    TextButton({deleting=true}) { Text("删除订阅",color=MaterialTheme.colorScheme.error) }
    Section("此套餐已付记录")
    l.payments.filter { it.planId==p.id }.sortedByDescending { it.date }.forEach { Hint("${it.date}   ${it.currency} ${it.amount}   ${it.note}") }
    if(renewing) RenewalDialog(l,p,{renewing=false},change)
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) { include -> change { Book.delete(it,p.id,include) };deleting=false;onDeleted() }
    if(giftId!=null) AlertDialog(onDismissRequest={giftId=null},title={Text("赠送时长")},text={Column { Field("增加天数",gift,{gift=it}); Hint("仅延长此项权益，不生成付款，也不改变套餐扣款日。") }},confirmButton={TextButton({ val n=gift.toIntOrNull(); if(n!=null && n in 1..36500) { val id=giftId;change { it.copy(benefits=it.benefits.map { b->if(b.id==id)b.copy(giftDays=b.giftDays+n) else b }) }; giftId=null } }){Text("增加")}},dismissButton={TextButton({giftId=null}){Text("取消")}})
}
@Composable private fun RenewalDialog(l: Ledger,p: Plan,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    var amount by remember { mutableStateOf(p.amount) }; var date by remember { mutableStateOf(LocalDate.now().toString()) }; var note by remember { mutableStateOf("续费") }
    var cnyAmount by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(l.benefits.filter { it.planId==p.id }.map { it.id }.toSet()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("记录实际付款")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Field("付款金额 ${p.currency}",amount,{amount=it}); Field("付款日期 YYYY-MM-DD",date,{date=it},dateField=true); Field("付款备注",note,{note=it})
        if(p.currency!="CNY") { Field("人民币实付金额",cnyAmount,{cnyAmount=it}); Hint("以付款当天账单为准，保存后固定。") }
        Hint("选择续费权益 · 延长 ${p.interval} ${p.cycle.label}")
        l.benefits.filter { it.planId==p.id }.forEach { b->Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(b.id in selected,{selected=if(it)selected+b.id else selected-b.id}); Text(b.name) } }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try { val d=LocalDate.parse(date); Book.renew(l,p.id,amount,d,selected,note,cnyAmount.takeIf { p.currency!="CNY" }); change { Book.renew(it,p.id,amount,d,selected,note,cnyAmount.takeIf { p.currency!="CNY" }) };close() }catch(e:Exception){error=e.message ?: "请检查日期、金额与权益选择"}}){Text("确认付款")}},dismissButton={TextButton(close){Text("取消")}})
}
