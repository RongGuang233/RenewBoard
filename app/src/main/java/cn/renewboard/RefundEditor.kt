package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RefundEditor(l: Ledger, original: Payment, close: () -> Unit, change: ((Ledger) -> Ledger) -> Unit) {
    var amount by rememberSaveable(original.id) { mutableStateOf("") }
    var date by rememberSaveable(original.id) { mutableStateOf(LocalDate.now().toString()) }
    var cny by rememberSaveable(original.id) { mutableStateOf("") }
    var note by rememberSaveable(original.id) { mutableStateOf("") }
    var showNote by rememberSaveable(original.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining=Book.refundable(l,original.id)
    BackHandler(onBack=close)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text("记录退款",fontWeight=FontWeight.SemiBold)},navigationIcon={PageBack("返回付款详情",close)})
    },bottomBar={
        Surface(shadowElevation=4.dp) {
            Button(onClick={
                try {
                    val day=LocalDate.parse(date)
                    val settled=cny.takeIf { original.currency!="CNY" }
                    Book.recordRefund(l,original.id,amount,day,settled,note)
                    change { Book.recordRefund(it,original.id,amount,day,settled,note) }
                    close()
                } catch(e:Exception) { error=e.message ?: "请检查退款金额和日期" }
            },enabled=remaining.amount.signum()>0,modifier=Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) { Text("保存退款") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(original.planName,style=MaterialTheme.typography.titleLarge)
            Text("原付款 ${original.date} · ${displayMoney(original.currency,original.amount)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("剩余可退 ${displayMoney(original.currency,remaining.amount.toPlainString())}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(original.note in setOf("话费扣费","话费额外扣费")) "退款只调整账本，余额可在话费账户校准。" else "退款冲减退款当期支出，权益到期日不变。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyField("退款金额 ${if(original.currency=="CNY") "¥" else original.currency}",amount,{amount=it})
            Field("退款日期",date,{date=it},dateField=true)
            if(original.currency!="CNY") {
                MoneyField("人民币实退金额",cny,{cny=it})
                Text("按退款到账账单填写实际人民币金额。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(original.cnyAmount==null) Text("请先返回详情补录原付款的人民币实付金额。",color=MaterialTheme.colorScheme.error)
            }
            TextButton({showNote=!showNote}) { Text(if(showNote) "收起备注" else "添加备注") }
            if(showNote) Field("退款备注",note,{note=it})
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    }
}
