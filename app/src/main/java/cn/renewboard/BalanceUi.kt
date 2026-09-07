package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.time.YearMonth

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
    var more by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var changingFee by remember { mutableStateOf(false) }
    var feeAmount by remember { mutableStateOf("") }
    var feeTiming by remember { mutableStateOf("下月") }
    var feeMonth by remember { mutableStateOf(YearMonth.now().plusMonths(1).toString()) }
    var confirmedBalance by remember { mutableStateOf("") }
    var recordExpense by remember { mutableStateOf(false) }
    var historyAffectsBalance by remember { mutableStateOf<Boolean?>(null) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ServiceIcon(p.name,size=48.dp)
        Text(p.name,fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        Box {
            IconButton({more=true}) { Icon(Icons.Outlined.MoreVert,"更多操作") }
            DropdownMenu(more,{more=false}) {
                DropdownMenuItem(text={Text("编辑账户")},onClick={more=false;onEdit()})
                DropdownMenuItem(text={Text(if(p.archived) "恢复使用" else "归档订阅")},onClick={more=false;change { Prepaid.archive(it,p.id,!p.archived,today) }})
                DropdownMenuItem(text={Text("删除订阅",color=MaterialTheme.colorScheme.error)},onClick={more=false;deleting=true})
            }
        }
    }
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("估算余额")
            Text(yuan(Prepaid.balance(p,today)),fontSize=36.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
            Text(balanceHint(p,today),fontWeight=FontWeight.SemiBold)
            Text("每月 ${LocalDate.parse(p.billingAnchor).dayOfMonth} 日 · ${yuan(Prepaid.feeAt(p,today))}\n下次扣费 ${Prepaid.nextDeduction(p,today)}",modifier=Modifier.padding(top=12.dp))
            p.balanceAccount?.pendingFee?.let { pending ->
                Text("${pending.effectiveDate} 起 ${yuan(BigDecimal(pending.amount))}/月",fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
            }
        }
    }
    Text("月费按设置记入支出，充值不重复计算；额外消费后可校准余额。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
    Button({recharging=true;error=null;date=today.toString();historyAffectsBalance=null},Modifier.fillMaxWidth()) { Text("记录充值") }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton({calibrating=true;confirmedBalance="";recordExpense=false;error=null},Modifier.weight(1f)) { Text("校准余额") }
        OutlinedButton({
            feeAmount=p.balanceAccount?.pendingFee?.amount ?: p.amount
            feeTiming=if(p.balanceAccount?.pendingFee!=null) "指定月份" else "下月"
            feeMonth=p.balanceAccount?.pendingFee?.effectiveDate?.take(7) ?: YearMonth.now().plusMonths(1).toString()
            error=null;changingFee=true
        },Modifier.weight(1f)) { Text("修改月费") }
    }
    if(p.note.isNotBlank()) Text(p.note,modifier=Modifier.padding(vertical=12.dp))
    Text("话费记录",fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=16.dp))
    l.payments.filter { it.planId==p.id }.sortedByDescending { it.date }.forEach {
        Text("${it.date} · ${if(it.currency=="CNY") yuan(BigDecimal(it.amount)) else "${it.currency} ${it.amount}"} · ${it.note}",modifier=Modifier.padding(vertical=8.dp))
    }
    if(recharging) AlertDialog(onDismissRequest={recharging=false},title={Text("记录话费充值")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        MoneyField("充值金额",amount,{amount=it})
        Field("充值日期",date,{date=it;historyAffectsBalance=null},dateField=true)
        val historical=runCatching { LocalDate.parse(date)<LocalDate.parse(p.balanceAccount!!.asOf) }.getOrDefault(false)
        if(historical) {
            Text("这笔充值是否已包含在当前余额中？",modifier=Modifier.padding(top=12.dp))
            Row(Modifier.fillMaxWidth().selectable(historyAffectsBalance==false) {historyAffectsBalance=false},verticalAlignment=Alignment.CenterVertically) { RadioButton(historyAffectsBalance==false,null);Text("已包含，仅补记充值记录") }
            Row(Modifier.fillMaxWidth().selectable(historyAffectsBalance==true) {historyAffectsBalance=true},verticalAlignment=Alignment.CenterVertically) { RadioButton(historyAffectsBalance==true,null);Text("未包含，同时增加余额") }
        }
        Text("余额与月费推算同步更新，充值不计支出，月费扣除时计入账本。",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton({try {
        val day=LocalDate.parse(date)
        require(day<=today) { "充值日期不能晚于今天" }
        val creditedAmount=amount
        val historical=day<LocalDate.parse(p.balanceAccount!!.asOf)
        val affectBalance=if(historical) requireNotNull(historyAffectsBalance) { "请选择这笔充值是否已包含在当前余额中" } else true
        Prepaid.topUp(l,p.id,creditedAmount,day,affectBalance)
        change { Prepaid.topUp(it,p.id,creditedAmount,day,affectBalance) };recharging=false;amount=""
    }catch(e:Exception) { error=e.message ?: "请检查充值金额和日期" }}) { Text("确认充值") }},dismissButton={TextButton({recharging=false}) { Text("取消") }})
    if(changingFee) AlertDialog(onDismissRequest={changingFee=false},title={Text("修改月费")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        MoneyField("新月费",feeAmount,{feeAmount=it})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("立即","下月","指定月份").forEach { timing ->
                FilterChip(selected=feeTiming==timing,onClick={feeTiming=timing},label={Text(timing)})
            }
        }
        if(feeTiming=="指定月份") Field("生效月份",feeMonth,{feeMonth=it})
        if(feeTiming=="立即") Text("从今天起，已记录扣费不变。",fontSize=13.sp)
        p.balanceAccount?.pendingFee?.let {
            TextButton({change { ledger -> Prepaid.cancelMonthlyFeeChange(ledger,p.id,today) };changingFee=false}) {Text("取消待生效调整")}
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({try {
        val effective=when(feeTiming) {"立即" -> today;"下月" -> YearMonth.from(today).plusMonths(1).atDay(1);else -> YearMonth.parse(feeMonth).atDay(1)}
        val value=feeAmount
        Prepaid.changeMonthlyFee(l,p.id,value,effective,today)
        change {Prepaid.changeMonthlyFee(it,p.id,value,effective,today)};changingFee=false
    }catch(e:Exception) {error=e.message ?: "请检查月费与生效月份"}}) {Text("保存月费")}},dismissButton={TextButton({changingFee=false}) {Text("取消")}})
    if(calibrating) AlertDialog(onDismissRequest={calibrating=false},title={Text("校准余额")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("今天的估算余额 ${yuan(Prepaid.balance(p,today))}")
        MoneyField("实际余额",confirmedBalance,{confirmedBalance=it;recordExpense=false})
        val difference=runCatching { Prepaid.balance(p,today)-BigDecimal(confirmedBalance) }.getOrNull()
        Row(Modifier.fillMaxWidth().selectable(!recordExpense) {recordExpense=false},verticalAlignment=Alignment.CenterVertically) { RadioButton(!recordExpense,null);Text("仅校正余额") }
        if(difference!=null && difference.signum()>0) Row(Modifier.fillMaxWidth().selectable(recordExpense) {recordExpense=true},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(recordExpense,null);Text("差额 ${yuan(difference)} 记为额外支出")
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton({try {
        val value=confirmedBalance;val expense=recordExpense
        Prepaid.calibrate(l,p.id,value,today,expense)
        change { Prepaid.calibrate(it,p.id,value,today,expense) };calibrating=false
    }catch(e:Exception) { error=e.message ?: "请检查实际余额" }}) { Text("确认校准") }},dismissButton={TextButton({calibrating=false}) { Text("取消") }})
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) { include -> change { Book.delete(it,p.id,include) };deleting=false;onDeleted() }
}
