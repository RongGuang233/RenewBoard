package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private fun yuan(value: BigDecimal) = "¥${value.setScale(2,RoundingMode.HALF_UP)}"
private fun balanceHint(p: Plan, today: LocalDate): String {
    if(Prepaid.balance(p,today).signum()<0) return "余额不足，请充值"
    val date=Prepaid.rechargeDate(p) ?: return "暂无需充值日期"
    return if(date<=today) "余额不足，请充值" else "预计 $date 需充值"
}
@Composable internal fun BalanceCard(p: Plan, compact:Boolean=false, comparison:Pair<String,String>?=null, open:()->Unit) {
    val today=LocalDate.now()
    Card(onClick=open,modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Row(Modifier.padding(if(compact) 12.dp else 16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ServiceIcon(p.name,size=if(compact) 32.dp else 48.dp)
            Column(Modifier.weight(1f)) {
                Text(p.name,fontWeight=FontWeight.SemiBold,fontSize=17.sp)
                Text(if(p.archived) "已归档" else balanceHint(p,today),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(comparison?.first ?: yuan(Prepaid.balance(p,today)),fontSize=if(compact) 18.sp else 20.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                if(!compact) Text(comparison?.second ?: "估算余额",fontSize=13.sp)
            }
        }
    }
}
@Composable internal fun BalanceDetail(l: Ledger,p: Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit,openPayment:(String)->Unit={},openHistory:()->Unit={},openAction:(BalanceAction)->Unit) {
    val today=LocalDate.now()
    var deleting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ServiceIcon(p.name,size=48.dp)
        Text(p.name,fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        Box {
            IconButton({more=true}) { Icon(Icons.Outlined.MoreVert,"更多操作") }
            DropdownMenu(more,{more=false}) {
                DropdownMenuItem(text={Text("编辑账户")},onClick={more=false;onEdit()})
                if(!p.archived) DropdownMenuItem(text={Text("归档订阅")},onClick={more=false;change { Prepaid.archive(it,p.id,true,today) }})
                DropdownMenuItem(text={Text("删除订阅",color=MaterialTheme.colorScheme.error)},onClick={more=false;deleting=true})
            }
        }
    }
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(if(p.archived) "已归档 · 归档时余额" else "估算余额")
            Text(yuan(Prepaid.balance(p,today)),fontSize=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=6.dp))
            if(!p.archived) Text(balanceHint(p,today),fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text("每月 ${LocalDate.parse(p.billingAnchor).dayOfMonth} 日",style=MaterialTheme.typography.bodySmall);Text(yuan(Prepaid.feeAt(p,today)),fontWeight=FontWeight.SemiBold)}
                if(!p.archived) Column(horizontalAlignment=Alignment.End) {Text("下次扣费",style=MaterialTheme.typography.bodySmall);Text(Prepaid.nextDeduction(p,today).toString(),fontWeight=FontWeight.SemiBold)}
            }
            p.balanceAccount?.pendingFee?.takeUnless {p.archived}?.let { pending ->
                Text("${pending.effectiveDate} 起 ${yuan(BigDecimal(pending.amount))}/月",fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if(p.archived) Button({change {Prepaid.archive(it,p.id,false,today)}},Modifier.fillMaxWidth()) {Text("恢复使用")}
    else Button({openAction(BalanceAction.TOP_UP)},Modifier.fillMaxWidth()) { Text("记录充值") }
    if(!p.archived) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton({openAction(BalanceAction.CALIBRATE)},Modifier.weight(1f)) { Text("校准余额") }
        OutlinedButton({openAction(BalanceAction.MONTHLY_FEE)},Modifier.weight(1f)) { Text("修改月费") }
    }
    if(p.note.isNotBlank()) Text(p.note,modifier=Modifier.padding(vertical=12.dp))
    val history=l.payments.filter {it.planId==p.id}.sortedByDescending {it.date}
    Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("最近记录",fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        if(history.isNotEmpty()) TextButton(onClick=openHistory) {Text("全部记录")}
    }
    if(history.isEmpty()) Hint("还没有话费记录")
    history.take(3).forEach { payment ->
        Row(Modifier.fillMaxWidth().clickable {openPayment(payment.id)}.heightIn(min=56.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when {payment.refundOf!=null -> "退款";Prepaid.isTopUp(payment) -> "充值";payment.note=="话费扣费" -> "月费";payment.note=="话费额外扣费" -> "额外扣费";else -> payment.note.ifBlank {"付款"}},fontWeight=FontWeight.Medium)
                Text(payment.date,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(displayMoney(payment.currency,payment.signedAmount().toPlainString()),fontWeight=FontWeight.SemiBold)
        }
    }
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) { include -> change { Book.delete(it,p.id,include) };deleting=false;onDeleted() }
}
