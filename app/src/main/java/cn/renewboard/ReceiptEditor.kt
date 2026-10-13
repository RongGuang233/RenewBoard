package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable internal data class ReceiptDraft(
    val paymentId:String, val amount:String, val date:String, val note:String, val cnyAmount:String
)

/** Corrections and settlement supplementation both edit an existing receipt, never its benefits or balance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReceiptEditor(
    l:Ledger, p:Payment, close:()->Unit, saved:()->Unit, save:suspend ((Ledger)->Ledger)->Unit,
    settlementOnly:Boolean=false, continueRefund:Boolean=false, backDescription:String="返回付款详情",
    actions:@Composable RowScope.()->Unit={}
) {
    val context=LocalContext.current
    val store=remember {DraftStore(context)}
    val draftKey="receipt:${if(settlementOnly) "settlement" else "correction"}:${p.id}"
    val defaults=remember(draftKey) {ReceiptDraft(p.id,p.amount,p.date,p.note,p.cnyAmount.orEmpty())}
    val restored=remember(draftKey) {runCatching {store.read(draftKey)?.let {Book.json.decodeFromString<ReceiptDraft>(it)}}.getOrNull()?.takeIf {it.paymentId==p.id}}
    var draft by remember(draftKey) {mutableStateOf(restored ?: defaults)}
    var recovered by remember(draftKey) {mutableStateOf(restored!=null && restored!=defaults)}
    var showNote by remember(draftKey) {mutableStateOf(draft.note.isNotBlank())}
    var showExit by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val fixedType=Prepaid.isTopUp(p) || p.note in setOf("话费扣费","话费额外扣费")
    val kind=if(p.refundOf==null) "付款" else "退款"
    fun update(next:ReceiptDraft) {
        if(saving) return
        draft=next;error=null
        if(next==defaults) {store.remove(draftKey);recovered=false} else store.write(draftKey,Book.json.encodeToString(next))
    }
    fun leave() {if(!saving) {if(draft!=defaults) showExit=true else close()}}
    fun revised(old:Ledger,entry:ReceiptDraft):Ledger {
        val current=old.payments.single {it.id==p.id}
        return Book.editPayment(old,p.id,if(settlementOnly) current.amount else entry.amount,
            LocalDate.parse(if(settlementOnly) current.date else entry.date),if(settlementOnly) current.note else entry.note,
            entry.cnyAmount.takeIf {current.currency!="CNY"})
    }
    fun commit() {
        if(saving) return
        try {
            val submitted=draft
            revised(l,submitted)
            saving=true;error=null
            scope.launch {
                try {save {revised(it,submitted)};store.remove(draftKey);saved()}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {error=e.message ?: "保存失败，请重试";saving=false}
            }
        } catch(e:Exception) {error=if(e is java.time.format.DateTimeParseException) "请检查日期" else e.message ?: "请检查金额和日期"}
    }
    BackHandler {leave()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(
            if(continueRefund) "补录原付款" else if(settlementOnly) "补录实付金额" else "更正$kind",fontWeight=FontWeight.SemiBold)},
            navigationIcon={PageBack(backDescription,::leave)},actions={if(!saving) actions()})
    },bottomBar={
        Surface {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=20.dp,vertical=12.dp)) {
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
                Button(onClick=::commit,enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(saving) "正在保存…" else if(continueRefund) "保存并继续退款" else if(settlementOnly) "保存金额" else "保存更正")
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(p.planName,size=40.dp);Text(p.planName,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            }
            if(recovered) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复${if(settlementOnly) "补录" else "更正"}草稿",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={update(defaults);showNote=defaults.note.isNotBlank()},enabled=!saving) {Text("丢弃草稿")}
            }
            if(settlementOnly) {
                Text("${p.date} · ${displayMoney(p.currency,p.amount)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(continueRefund) Text("先补齐原付款金额，保存后继续记录退款。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(if(fixedType) "仅修改账目，话费余额不变。" else "仅修改账目，权益到期日不变。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyField("${kind}金额 ${if(p.currency=="CNY") "¥" else p.currency}",draft.amount,{update(draft.copy(amount=it))})
                Field("${kind}日期",draft.date,{update(draft.copy(date=it))},dateField=true)
            }
            if(p.currency!="CNY") MoneyField(if(p.refundOf==null) "人民币实付金额" else "人民币实退金额",draft.cnyAmount,{update(draft.copy(cnyAmount=it))},supportingText="按当日账单填写，保存后固定")
            if(!settlementOnly && !fixedType) {
                TextButton(onClick={showNote=!showNote},enabled=!saving,contentPadding=PaddingValues(horizontal=0.dp)) {Text(if(showNote) "收起备注" else "添加备注")}
                if(showNote) Field("${kind}备注",draft.note,{update(draft.copy(note=it))})
            }
        }
    }
    if(showExit) AlertDialog(onDismissRequest={showExit=false},title={Text("保留${if(settlementOnly) "补录" else "更正"}草稿？")},
        confirmButton={TextButton(onClick={store.write(draftKey,Book.json.encodeToString(draft));showExit=false;close()}) {Text("保留草稿")}},
        dismissButton={TextButton(onClick={store.remove(draftKey);showExit=false;close()}) {Text("放弃修改")}})
}
