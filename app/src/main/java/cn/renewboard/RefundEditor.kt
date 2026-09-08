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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable internal data class RefundDraft(
    val paymentId: String, val amount: String = "", val date: String,
    val cnyAmount: String = "", val note: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RefundEditor(
    l: Ledger, original: Payment, close: () -> Unit, save: suspend ((Ledger) -> Ledger) -> Unit
) {
    val context=LocalContext.current
    val store=remember {DraftStore(context)}
    val draftKey="refund:${original.id}"
    val defaults=remember(original.id) {RefundDraft(original.id,date=LocalDate.now().toString())}
    val restored=remember(original.id) {
        runCatching {store.read(draftKey)?.let {Book.json.decodeFromString<RefundDraft>(it)}}.getOrNull()?.takeIf {it.paymentId==original.id}
    }
    var draft by remember(original.id) {mutableStateOf(restored ?: defaults)}
    var recovered by remember(original.id) {mutableStateOf(restored!=null && restored!=defaults)}
    var showNote by remember(original.id) {mutableStateOf(draft.note.isNotBlank())}
    var showExit by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val remaining=Book.refundable(l,original.id)
    fun update(next:RefundDraft) {
        if(saving) return
        draft=next;error=null
        if(next==defaults) {store.remove(draftKey);recovered=false}
        else store.write(draftKey,Book.json.encodeToString(next))
    }
    fun leave() {if(!saving) {if(draft!=defaults) showExit=true else close()}}
    fun record(old:Ledger,entry:RefundDraft):Ledger = Book.recordRefund(
        old,original.id,entry.amount,LocalDate.parse(entry.date),entry.cnyAmount.takeIf {original.currency!="CNY"},entry.note
    )
    fun commit() {
        if(saving) return
        try {
            val submitted=draft
            record(l,submitted)
            saving=true;error=null
            scope.launch {
                try {
                    save {record(it,submitted)}
                    store.remove(draftKey)
                    close()
                } catch(e:Exception) {error=e.message ?: "保存失败，请重试";saving=false}
            }
        } catch(e:Exception) {
            error=if(e is java.time.format.DateTimeParseException) "请检查退款日期" else e.message ?: "请检查退款金额和日期"
        }
    }
    BackHandler {leave()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text("记录退款",fontWeight=FontWeight.SemiBold)},navigationIcon={PageBack("返回付款详情",::leave)})
    },bottomBar={
        Surface(shadowElevation=4.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
                Button(onClick=::commit,enabled=!saving && remaining.amount.signum()>0,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(saving) "正在保存…" else "保存退款")
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(original.planName,style=MaterialTheme.typography.titleLarge)
            if(recovered) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复退款草稿",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={update(defaults);showNote=false},enabled=!saving) {Text("丢弃草稿")}
            }
            Text("原付款 ${original.date} · ${displayMoney(original.currency,original.amount)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("剩余可退 ${displayMoney(original.currency,remaining.amount.toPlainString())}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(original.note in setOf("话费扣费","话费额外扣费")) "退款只调整账本，余额可在话费账户校准。" else "退款冲减退款当期支出，权益到期日不变。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyField("退款金额 ${if(original.currency=="CNY") "¥" else original.currency}",draft.amount,{update(draft.copy(amount=it))})
            Field("退款日期",draft.date,{update(draft.copy(date=it))},dateField=true)
            if(original.currency!="CNY") {
                MoneyField("人民币实退金额",draft.cnyAmount,{update(draft.copy(cnyAmount=it))})
                Text("按退款到账账单填写实际人民币金额。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(original.cnyAmount==null) Text("请先返回详情补录原付款的人民币实付金额。",color=MaterialTheme.colorScheme.error)
            }
            TextButton({showNote=!showNote},enabled=!saving) {Text(if(showNote) "收起备注" else "添加备注")}
            if(showNote) Field("退款备注",draft.note,{update(draft.copy(note=it))})
        }
    }
    if(showExit) AlertDialog(onDismissRequest={showExit=false},title={Text("保留退款草稿？")},
        confirmButton={TextButton(onClick={store.write(draftKey,Book.json.encodeToString(draft));showExit=false;close()}) {Text("保留草稿")}},
        dismissButton={TextButton(onClick={store.remove(draftKey);showExit=false;close()}) {Text("放弃修改")}})
}
