package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable internal data class PaymentDraft(
    val planId: String, val amount: String, val date: String, val note: String = "", val cnyAmount: String = "",
    val onlyRecord: Boolean = false, val selected: Set<String> = emptySet(), val restart: Boolean? = null, val periods: String = "1", val amountEdited:Boolean = true
)

internal fun PaymentDraft.withPeriods(p:Plan,value:String):PaymentDraft {
    val count=value.toIntOrNull()?.takeIf {it in 1..120}
    return copy(periods=value,amount=if(!amountEdited && count!=null) p.amount.toBigDecimal().multiply(count.toBigDecimal()).toPlainString() else amount)
}
internal fun renewalDuration(p:Plan,periods:String):String {
    val count=periods.toIntOrNull()?.takeIf {it in 1..120}?.times(p.interval)
    val unit=when(p.cycle) {Cycle.MONTH->"个月";Cycle.WEEK->"周";Cycle.YEAR->"年"}
    return "${count ?: "—"}$unit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PaymentEditor(l: Ledger,p: Plan,initialOnlyRecord: Boolean = false,close: () -> Unit,save: suspend ((Ledger) -> Ledger) -> Unit) {
    val context=LocalContext.current
    val store=remember {DraftStore(context)}
    val draftKey="payment:${p.id}"
    val benefits=l.benefits.filter {it.planId==p.id}
    val defaults=remember(p.id) {PaymentDraft(p.id,p.amount,LocalDate.now().toString(),onlyRecord=initialOnlyRecord,selected=benefits.map {it.id}.toSet(),amountEdited=false)}
    val restored=remember(p.id) {runCatching {store.read(draftKey)?.let {Book.json.decodeFromString<PaymentDraft>(it)}}.getOrNull()?.takeIf {it.planId==p.id}}
    var draft by remember(p.id) {mutableStateOf(restored?.copy(selected=restored.selected.intersect(benefits.map {it.id}.toSet())) ?: defaults)}
    var recovered by remember(p.id) {mutableStateOf(restored!=null)}
    var showExit by remember {mutableStateOf(false)}
    var showDuplicates by remember {mutableStateOf(false)}
    var duplicates by remember {mutableStateOf<List<Payment>>(emptyList())}
    var noteExpanded by remember {mutableStateOf(draft.note.isNotBlank())}
    var benefitsExpanded by remember {mutableStateOf(false)}
    var periodsExpanded by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun unchanged(entry:PaymentDraft)=entry.copy(amountEdited=defaults.amountEdited)==defaults
    fun update(next: PaymentDraft) {draft=next;if(unchanged(next)) {store.remove(draftKey);recovered=false} else store.write(draftKey,Book.json.encodeToString(next));error=null}
    fun leave() {if(saving) return; if(!unchanged(draft) || recovered) showExit=true else close()}
    BackHandler {leave()}
    val selected=draft.selected.intersect(benefits.map {it.id}.toSet())
    val expired=runCatching {benefits.filter {it.id in selected}.any {Book.expiry(it,p)<LocalDate.parse(draft.date)}}.getOrDefault(false)
    val restarting=draft.restart ?: runCatching {benefits.filter {it.id in selected}.all {Book.expiry(it,p)<LocalDate.parse(draft.date)}}.getOrDefault(false)
    fun updated(old: Ledger, entry: PaymentDraft = draft): Ledger {
        val actualSelected=entry.selected.intersect(old.benefits.filter {it.planId==p.id}.map {it.id}.toSet())
        return if(entry.onlyRecord) Book.recordPayment(old,p.id,entry.amount,LocalDate.parse(entry.date),entry.note,entry.cnyAmount.takeIf {p.currency!="CNY"})
            else Book.renew(old,p.id,entry.amount,LocalDate.parse(entry.date),actualSelected,entry.note,entry.cnyAmount.takeIf {p.currency!="CNY"},entry.restart,entry.periods.toIntOrNull() ?: throw IllegalArgumentException("本次续费期数应为1至120"))
    }
    fun commit(allowDuplicate: Boolean = false) {
        if(saving) return
        try {
            updated(l)
            val matches=Book.suspectedDuplicates(l,p.id,draft.amount,LocalDate.parse(draft.date))
            if(!allowDuplicate && matches.isNotEmpty()) {duplicates=matches;showDuplicates=true;return}
            val submitted=draft
            saving=true;error=null
            scope.launch {
                try {save {old -> updated(old,submitted)};store.remove(draftKey);close()}
                catch(e: Exception) {error=e.message ?: "保存失败，请重试";saving=false}
            }
        } catch(e: Exception) {error=if(e is java.time.format.DateTimeParseException) "请检查付款日期" else e.message ?: "请检查输入"}
    }
    // Date preview does not depend on having entered the actual settlement amount yet.
    val preview=runCatching {updated(l,draft.copy(amount=p.amount,cnyAmount=if(p.currency!="CNY") "0" else "",note=""))}.getOrNull()
    Scaffold(modifier=Modifier.imePadding(),topBar={TopAppBar(title={Text("记录付款")},navigationIcon={PageBack(back=::leave)},windowInsets=WindowInsets(0,0,0,0))},
        bottomBar={Surface {Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
            Button(onClick={commit()},enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(saving) "正在保存…" else "确认付款")}
        }}},contentWindowInsets=WindowInsets(0,0,0,0)) {padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(p.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            if(recovered) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复付款草稿",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={store.remove(draftKey);draft=defaults;recovered=false;noteExpanded=false}) {Text("丢弃草稿")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!draft.onlyRecord,{update(draft.copy(onlyRecord=false))},label={Text("续费")},enabled=!saving)
                FilterChip(draft.onlyRecord,{update(draft.copy(onlyRecord=true))},label={Text("仅记账")},enabled=!saving)
            }
            MoneyField("本次实付总额 ${if(p.currency=="CNY") "¥" else p.currency}",draft.amount,{update(draft.copy(amount=it,amountEdited=true))})
            Field("付款日期",draft.date,{update(draft.copy(date=it,restart=null))},dateField=true)
            if(p.currency!="CNY") MoneyField("人民币实付金额",draft.cnyAmount,{update(draft.copy(cnyAmount=it))})
            if(!draft.onlyRecord) {
                TextButton(onClick={periodsExpanded=!periodsExpanded},contentPadding=PaddingValues(0.dp)) {Text("本次续费 ${renewalDuration(p,draft.periods)}");Icon(Icons.Outlined.ExpandMore,null)}
                if(periodsExpanded) OutlinedTextField(draft.periods,{update(draft.withPeriods(p,it))},label={Text("续费期数")},
                    suffix={Text("每期${renewalDuration(p,"1")}")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                if(expired) {
                    Row(Modifier.fillMaxWidth().selectable(restarting,role=Role.RadioButton,onClick={update(draft.copy(restart=true))}),verticalAlignment=Alignment.CenterVertically) {RadioButton(restarting,null);Text("从付款日重新开通")}
                    Row(Modifier.fillMaxWidth().selectable(!restarting,role=Role.RadioButton,onClick={update(draft.copy(restart=false))}),verticalAlignment=Alignment.CenterVertically) {RadioButton(!restarting,null);Text("补交原周期账单")}
                }
                if(benefits.size>1) {
                    TextButton(onClick={benefitsExpanded=!benefitsExpanded},contentPadding=PaddingValues(0.dp)) {Text("续费权益 ${selected.size}/${benefits.size} 项");Icon(Icons.Outlined.ExpandMore,null)}
                    if(benefitsExpanded) benefits.forEach {b -> Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(b.id in selected,{checked -> update(draft.copy(selected=if(checked) selected+b.id else selected-b.id,restart=null))});Text(b.name)
                    }}
                }
                preview?.let {result ->
                    val next=result.plans.single {it.id==p.id}
                    val renewed=result.benefits.filter {it.id in selected}
                    if(renewed.isNotEmpty()) {
                        if(renewed.size==1 || benefitsExpanded) renewed.forEach {Text("${it.name} → ${Book.expiry(it,next)}",style=MaterialTheme.typography.bodyMedium)}
                        else Text("续费后到期 ${renewed.minOf {Book.expiry(it,next)}} — ${renewed.maxOf {Book.expiry(it,next)}}",style=MaterialTheme.typography.bodyMedium)
                    }
                    if(p.autoRenew) Text("下次扣款 ${Book.nextCharge(next,LocalDate.parse(draft.date))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick={noteExpanded=!noteExpanded},contentPadding=PaddingValues(0.dp)) {Text("付款备注");Icon(Icons.Outlined.ExpandMore,null)}
            if(noteExpanded) Field("付款备注",draft.note,{update(draft.copy(note=it))})
        }
    }
    if(showExit) AlertDialog(onDismissRequest={showExit=false},title={Text("保留付款草稿？")},
        confirmButton={TextButton(onClick={store.write(draftKey,Book.json.encodeToString(draft));showExit=false;close()}) {Text("保留草稿")}},
        dismissButton={TextButton(onClick={store.remove(draftKey);showExit=false;close()}) {Text("放弃修改")}})
    if(showDuplicates) AlertDialog(onDismissRequest={showDuplicates=false},title={Text("发现相似付款")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        duplicates.forEach {payment -> Text("${payment.date}  ${if(payment.currency=="CNY") "¥" else payment.currency+" "}${payment.amount}");if(payment.note.isNotBlank()) Text(payment.note)}
    }},confirmButton={TextButton(onClick={showDuplicates=false;commit(true)}) {Text("仍然记录")}},dismissButton={TextButton(onClick={showDuplicates=false}) {Text("返回检查")}})
}
