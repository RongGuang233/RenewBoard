package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable internal data class SubscriptionDraft(
    val id:String, val prepaid:Boolean, val balance:String, val balanceDate:String, val deductionDay:String,
    val name:String, val amount:String, val currency:String, val cycle:Cycle, val interval:String,
    val auto:Boolean, val anchor:String, val note:String, val paid:Boolean, val cnyAmount:String,
    val benefits:List<Benefit>, val following:Set<String>
) {
    companion object {
        fun create(l:Ledger,p:Plan?):SubscriptionDraft {
            val id=p?.id ?: newId();val today=LocalDate.now().toString();val anchor=p?.billingAnchor ?: today
            val benefits=l.benefits.filter {it.planId==id}.ifEmpty {listOf(Benefit(planId=id,name="",anchor=anchor,renewals=1))}
            return SubscriptionDraft(id,p?.balanceAccount!=null,p?.balanceAccount?.let {Prepaid.balance(p,LocalDate.now()).toPlainString()} ?: "",today,
                p?.billingAnchor?.let {LocalDate.parse(it).dayOfMonth.toString()} ?: "1",p?.name ?: "",p?.amount ?: "",p?.currency ?: "CNY",p?.cycle ?: Cycle.MONTH,
                (p?.interval ?: 1).toString(),p?.autoRenew ?: true,anchor,p?.note ?: "",p==null,"",benefits,benefits.filter{it.anchor==anchor && it.renewals>0}.map{it.id}.toSet())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SubscriptionEditor(l:Ledger,existing:Plan?,close:()->Unit,save:suspend (Plan,List<Benefit>,Payment?)->Unit) {
    val context=LocalContext.current;val store=remember {DraftStore(context)}
    val draftKey="subscription:${existing?.id ?: "new"}"
    val baseline by rememberSaveable(stateSaver=Saver<SubscriptionDraft,String>(save={Book.json.encodeToString(it)},restore={Book.json.decodeFromString(it)})) {mutableStateOf(SubscriptionDraft.create(l,existing))}
    val loaded=remember {store.read(draftKey)?.let {runCatching {Book.json.decodeFromString<SubscriptionDraft>(it)}.getOrNull()}}
    val initial=loaded ?: baseline
    val id=initial.id;val today=LocalDate.now()
    var prepaid by rememberSaveable { mutableStateOf(initial.prepaid) }
    var balance by rememberSaveable { mutableStateOf(initial.balance) }
    var balanceDate by rememberSaveable { mutableStateOf(initial.balanceDate) }
    var deductionDay by rememberSaveable { mutableStateOf(initial.deductionDay) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var amount by rememberSaveable { mutableStateOf(initial.amount) }
    var currency by rememberSaveable { mutableStateOf(initial.currency) }
    var cycle by rememberSaveable { mutableStateOf(initial.cycle) }
    var interval by rememberSaveable { mutableStateOf(initial.interval) }
    var auto by rememberSaveable { mutableStateOf(initial.auto) }
    var anchor by rememberSaveable { mutableStateOf(initial.anchor) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    var paid by rememberSaveable { mutableStateOf(initial.paid) }
    var cnyAmount by rememberSaveable { mutableStateOf(initial.cnyAmount) }
    var benefits by rememberSaveable(stateSaver=Saver<List<Benefit>,String>(save={Book.json.encodeToString(it)},restore={Book.json.decodeFromString(it)})) {mutableStateOf(initial.benefits)}
    var following by rememberSaveable(stateSaver=Saver<Set<String>,String>(save={Book.json.encodeToString(it)},restore={Book.json.decodeFromString(it)})) {mutableStateOf(initial.following)}
    var picker by rememberSaveable {mutableStateOf(existing==null && loaded==null)}
    var cycleMenu by remember {mutableStateOf(false)}
    var moreExpanded by rememberSaveable {mutableStateOf(false)}
    var benefitsExpanded by rememberSaveable {mutableStateOf(false)}
    var confirmExit by remember {mutableStateOf(false)}
    var restored by rememberSaveable {mutableStateOf(loaded!=null)}
    var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val draft=SubscriptionDraft(id,prepaid,balance,balanceDate,deductionDay,name,amount,currency,cycle,interval,auto,anchor,note,paid,cnyAmount,benefits,following)
    val dirty=draft!=baseline
    LaunchedEffect(draft) {if(dirty) store.write(draftKey,Book.json.encodeToString(draft)) else store.remove(draftKey)}
    fun back() {if(busy) return;if(picker && name.isNotBlank()) picker=false else if(dirty) confirmExit=true else close()}
    BackHandler {back()}
    val previewPlan=Plan(id=id,name=name,amount=amount,currency=currency,cycle=cycle,
        interval=interval.toIntOrNull()?.takeIf { it in 1..120 } ?: 1,billingAnchor=anchor)
    fun resolvedBenefit(b:Benefit):Benefit = if(b.id in following) b.copy(anchor=anchor) else b
    fun displayedExpiry(b:Benefit):String = runCatching {
        Book.expiry(resolvedBenefit(b),if(b.id in following) previewPlan else existing ?: previewPlan).toString()
    }.getOrDefault(b.anchor)
    fun submit() {try {
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
        var p=Plan(id,name.trim(),amount,if(prepaid) "CNY" else currency,if(prepaid) Cycle.MONTH else cycle,
            if(prepaid) 1 else interval.toInt(),if(prepaid) true else auto,billingAnchor,
            if(prepaid) 0 else existing?.paidCycles ?: if(paid) 1 else 0,existing?.archived ?: false,note,
            if(prepaid) BalanceAccount(balance,balanceDate,existing?.balanceAccount?.pendingFee) else null)
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
        if(prepaid && existing?.balanceAccount!=null && amount.toBigDecimal().compareTo(existing.amount.toBigDecimal())!=0) {
            p=p.copy(balanceAccount=p.balanceAccount?.copy(pendingFee=null))
        }
        Book.validate(l.copy(plans=l.plans.filterNot { it.id==id }+p,benefits=l.benefits.filterNot { it.planId==id }+bs,payments=l.payments+listOfNotNull(receipt)))
        busy=true
        scope.launch {
            try {save(p,bs,receipt);store.remove(draftKey);close()}
            catch(e:Exception) {error=e.message ?: "未能保存，请重试"}
            finally {busy=false}
        }
    } catch(e:Exception) {error=when(e) {
        is NumberFormatException -> "金额和周期请填写数字"
        is java.time.format.DateTimeParseException -> "请检查日期，使用 YYYY-MM-DD 格式"
        else -> e.message ?: "请检查输入"
    }} }
    Scaffold(modifier=Modifier.imePadding(),contentWindowInsets=WindowInsets(0,0,0,0),topBar={TopAppBar(title={Text(if(picker) "选择会员" else if(existing==null) "记一笔订阅" else "编辑订阅",fontSize=21.sp)},navigationIcon={PageBack(back=::back)},windowInsets=WindowInsets(0,0,0,0))},
        bottomBar={if(!picker) Surface {Button(onClick=::submit,enabled=!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp).heightIn(min=48.dp)) {Text(if(busy) "保存中…" else "保存订阅")}}}) {padding ->
        if(picker) ServiceSelection(l,Modifier.padding(padding),custom={picker=false}) {preset ->
            name=preset.name;currency=preset.currency;prepaid=preset.name in listOf("中国电信","中国移动","中国联通")
            if(prepaid) anchor=today.withDayOfMonth(1).plusMonths(1).toString()
            benefits=benefits.mapIndexed {i,b->if(i==0) b.copy(name=preset.name) else b};picker=false
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
    if(restored) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("已恢复草稿",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
        TextButton(onClick={confirmExit=true}) {Text("放弃修改")}
    }
    if(existing==null) TextButton(onClick={picker=true}) {Text("更换会员")}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        if(name.isNotBlank()) ServiceIcon(name,size=36.dp)
        Field("订阅 / 套餐名称",name,{name=it},Modifier.weight(1f))
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!prepaid,{prepaid=false},label={Text("周期订阅")})
        FilterChip(prepaid,{
            if(!prepaid && existing?.balanceAccount==null) anchor=today.withDayOfMonth(1).plusMonths(1).toString()
            prepaid=true
        },label={Text("话费余额")})
    }
    if(prepaid) MoneyField("每月扣费金额",amount,{amount=it}) else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        MoneyField("套餐价格",amount,{amount=it},Modifier.weight(1f))
        CurrencyPicker(currency,compact=true) {currency=it}
    }
    if(prepaid) {
        MoneyField("查询到的余额",balance,{balance=it})
        Field("余额查询日期",balanceDate,{balanceDate=it},dateField=true)
        Hint("以运营商查到的余额为准。")
        OutlinedTextField(deductionDay,{deductionDay=it},label={Text("每月扣费日")},suffix={Text("日")},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())

    } else {
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
        Text("包含的权益",fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        Icon(if(benefitsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(benefitsExpanded) "收起权益" else "展开权益")
    }
    if(!benefitsExpanded) Text("${benefits.size}项权益 · ${benefits.firstOrNull()?.let {displayedExpiry(it)} ?: ""}到期",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    if(existing==null && paid && currency!="CNY") { MoneyField("人民币实付金额",cnyAmount,{cnyAmount=it}); Hint("填写付款当天的人民币金额，保存后固定。") }
    }
    TextButton(onClick={moreExpanded=!moreExpanded}) {Text("更多选项"); Icon(if(moreExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
    if(moreExpanded) Field("备注",note,{note=it})
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }
    if(confirmExit) AlertDialog(onDismissRequest={confirmExit=false},title={Text("保留未完成的修改？")},
        confirmButton={TextButton(onClick={store.write(draftKey,Book.json.encodeToString(draft));confirmExit=false;close()}) {Text("保留草稿")}},
        dismissButton={TextButton(onClick={store.remove(draftKey);confirmExit=false;close()}) {Text("放弃修改")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ServiceSelection(l:Ledger,modifier:Modifier=Modifier,custom:()->Unit,select:(ServicePreset)->Unit) {
    var query by rememberSaveable {mutableStateOf("")};var category by rememberSaveable {mutableStateOf("全部")}
    Column(modifier.fillMaxSize().padding(horizontal=20.dp,vertical=8.dp)) {
        Field("搜索会员",query,{query=it})
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("选择常见会员",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            TextButton(onClick=custom) {Text("自定义订阅")}
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (listOf("全部")+servicePresets.map{it.category}.distinct()).forEach {item->FilterChip(category==item,{category=item},label={Text(item,fontSize=12.sp)})}
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val recent=(l.payments.asReversed().map{it.planName}+l.plans.asReversed().map{it.name}).mapNotNull{servicePresetFor(it)}.distinctBy{it.name}.take(6)
            if(query.isBlank() && category=="全部" && recent.isNotEmpty()) {
                Text("最近使用",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=10.dp))
                recent.chunked(3).forEach {row->Row(Modifier.fillMaxWidth()) {
                    row.forEach {p->Column(Modifier.weight(1f).clickable {select(p)}.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {ServiceIcon(p.name,size=32.dp);Text(p.name,style=MaterialTheme.typography.bodySmall,maxLines=1)}}
                    repeat(3-row.size) {Spacer(Modifier.weight(1f))}
                }}
            }
            val matches=servicePresets.filter {p->(category=="全部" || p.category==category) && (listOf(p.name,p.category)+p.aliases).any{it.contains(query.trim(),true)}}
            if(matches.isEmpty()) Text("没有找到，试试自定义订阅",modifier=Modifier.padding(vertical=20.dp))
            matches.forEach {p->Row(Modifier.fillMaxWidth().clickable {select(p)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {ServiceIcon(p.name,size=32.dp);Text(p.name,style=MaterialTheme.typography.bodyLarge)}}
        }
    }
}
