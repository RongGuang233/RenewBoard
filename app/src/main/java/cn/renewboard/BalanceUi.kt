package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
@Composable internal fun BalanceCard(p: Plan, open:()->Unit) {
    val today=LocalDate.now()
    Card(onClick=open,modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ServiceIcon(p.name)
            Column(Modifier.weight(1f)) {
                Text(p.name,fontWeight=FontWeight.SemiBold,fontSize=17.sp)
                Text(balanceHint(p,today),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(yuan(Prepaid.balance(p,today)),fontSize=20.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                Text("估算余额",fontSize=13.sp)
            }
        }
    }
}
@Composable internal fun BalanceDetail(l: Ledger,p: Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit) {
    val today=LocalDate.now()
    var recharging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    ServiceIcon(p.name,size=64.dp)
    Text(p.name,fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=16.dp))
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("估算余额")
            Text(yuan(Prepaid.balance(p,today)),fontSize=36.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
            Text(balanceHint(p,today),fontWeight=FontWeight.SemiBold)
            Text("每月 ¥${p.amount} · 下次扣费 ${Prepaid.nextDeduction(p,today)}",modifier=Modifier.padding(top=12.dp))
        }
    }
    Text("按固定月费推算；额外消费或优惠后可校准余额。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
    Button({recharging=true;error=null},Modifier.fillMaxWidth()) { Text("记录充值") }
    OutlinedButton(onEdit,Modifier.fillMaxWidth()) { Text("校准余额 / 修改月费") }
    if(p.note.isNotBlank()) Text(p.note,modifier=Modifier.padding(vertical=12.dp))
    TextButton({change { it.copy(plans=it.plans.map { x->if(x.id==p.id)x.copy(archived=!x.archived) else x }) }}) { Text(if(p.archived) "恢复使用" else "归档订阅") }
    TextButton({deleting=true}) { Text("删除订阅",color=MaterialTheme.colorScheme.error) }
    Text("付款记录",fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=16.dp))
    l.payments.filter { it.planId==p.id }.sortedByDescending { it.date }.forEach {
        Text("${it.date} · ${it.currency} ${it.amount} · ${it.note}",modifier=Modifier.padding(vertical=8.dp))
    }
    if(recharging) AlertDialog(onDismissRequest={recharging=false},title={Text("记录话费充值")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Field("充值金额",amount,{amount=it})
        Field("充值日期",date,{date=it},dateField=true)
        Text("余额与月费推算同步更新，账本只记这笔充值。",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton({try {
        val day=LocalDate.parse(date)
        require(day<=today) { "充值日期不能晚于今天" }
        val creditedAmount=amount
        Prepaid.topUp(l,p.id,creditedAmount,day)
        change { Prepaid.topUp(it,p.id,creditedAmount,day) };recharging=false;amount=""
    }catch(e:Exception) { error=e.message ?: "请检查充值金额和日期" }}) { Text("确认充值") }},dismissButton={TextButton({recharging=false}) { Text("取消") }})
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) { include -> change { Book.delete(it,p.id,include) };deleting=false;onDeleted() }
}
