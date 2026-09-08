package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** The parent supplies navigation; payment stays reachable while reading long bundles. */
@Composable internal fun SubscriptionDetailScreen(
    l:Ledger,p:Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit,
    openPayment:(String)->Unit,openHistory:()->Unit,recordPayment:(Boolean)->Unit
) {
    var deleting by remember(p.id) { mutableStateOf(false) }
    var more by remember(p.id) { mutableStateOf(false) }
    var benefitMenu by remember(p.id) { mutableStateOf<String?>(null) }
    var giftId by remember(p.id) { mutableStateOf<String?>(null) }
    var gift by remember(p.id) { mutableStateOf("7") }
    var snoozed by remember(p.id) { mutableStateOf(false) }
    val context=LocalContext.current
    val benefits=l.benefits.filter {it.planId==p.id}
    val history=l.payments.filter {it.planId==p.id}.sortedByDescending {it.date}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,contentWindowInsets=WindowInsets(0,0,0,0),bottomBar={
        Surface(color=MaterialTheme.colorScheme.background) {
            Button(onClick={
                if(p.archived) change {ledger->ledger.copy(plans=ledger.plans.map {if(it.id==p.id) it.copy(archived=false) else it})}
                else recordPayment(false)
            },modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=48.dp)) {
                Text(if(p.archived) "恢复使用" else "记录付款 / 提前续费")
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
            Row(Modifier.padding(top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(p.name,size=40.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.name,fontSize=24.sp,fontWeight=FontWeight.Bold)
                    Text("${displayMoney(p.currency,p.amount)} / ${p.interval} ${p.cycle.label}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick={more=true}) {Icon(Icons.Outlined.MoreVert,"订阅更多操作")}
                    DropdownMenu(more,{more=false}) {
                        DropdownMenuItem(text={Text("编辑订阅与到期日")},onClick={more=false;onEdit()})
                        DropdownMenuItem(text={Text("仅补记付款")},onClick={more=false;recordPayment(true)})
                        if(!p.archived) {
                            DropdownMenuItem(text={Text("明天提醒")},onClick={more=false;Jobs.snooze(context,p.id,l);snoozed=true})
                            DropdownMenuItem(text={Text("归档订阅")},onClick={more=false;change {ledger->ledger.copy(plans=ledger.plans.map {if(it.id==p.id) it.copy(archived=true) else it})}})
                        }
                        DropdownMenuItem(text={Text("删除订阅",color=MaterialTheme.colorScheme.error)},onClick={more=false;deleting=true})
                    }
                }
            }
            if(p.archived) Text("已归档",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=12.dp))
            else {
                Hint(if(p.autoRenew) "下次预计扣款 ${Book.nextCharge(p,LocalDate.now())}" else "已标记关闭自动续费 · 有效期提醒仍保留")
                if(snoozed) Hint("已安排明天提醒")
            }
            if(benefits.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal=16.dp)) {
                    benefits.forEachIndexed {index,b->
                        if(index>0) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name,fontWeight=FontWeight.SemiBold)
                                Text("${Book.expiry(b,p)} 到期"+(if(b.giftDays>0) " · 含赠送 ${b.giftDays} 天" else ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick={benefitMenu=b.id}) {Icon(Icons.Outlined.MoreVert,"${b.name}权益更多操作")}
                                DropdownMenu(benefitMenu==b.id,{benefitMenu=null}) {
                                    DropdownMenuItem(text={Text("增加赠送时长")},onClick={benefitMenu=null;giftId=b.id;gift="7"})
                                }
                            }
                        }
                    }
                }
            }
            val missing=history.filter {it.currency!="CNY" && it.cnyAmount==null}
            if(missing.isNotEmpty()) OutlinedButton(onClick={openPayment(missing.first().id)},modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {Text("补录人民币（${missing.size} 笔）")}
            else if(p.currency!="CNY" && history.none {it.cnyAmount!=null}) OutlinedButton(onClick={recordPayment(true)},modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {Text("补记付款与人民币金额")}
            if(!p.archived) TextButton(onClick={change {ledger->ledger.copy(plans=ledger.plans.map {if(it.id==p.id) it.copy(autoRenew=!it.autoRenew) else it})}},modifier=Modifier.fillMaxWidth()) {Text(if(p.autoRenew) "标记已关闭自动续费" else "标记已开启自动续费")}
            if(p.note.isNotBlank()) Hint(p.note)
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("最近付款",fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                if(history.isNotEmpty()) TextButton(onClick=openHistory) {Text("全部记录")}
            }
            if(history.isEmpty()) Hint("还没有付款记录")
            history.take(3).forEach {receipt->
                Row(modifier=Modifier.fillMaxWidth().clickable {openPayment(receipt.id)}.heightIn(min=56.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text((if(receipt.refundOf!=null) "退款 · " else "")+receipt.date,modifier=Modifier.weight(1f))
                    Column(horizontalAlignment=Alignment.End) {
                        val cny=receipt.signedCny()
                        Text(if(cny!=null) displayMoney("CNY",cny.toPlainString()) else displayMoney(receipt.currency,receipt.signedAmount().toPlainString()),fontWeight=FontWeight.SemiBold)
                        if(receipt.currency!="CNY") Text(if(cny==null) "待补录人民币" else displayMoney(receipt.currency,receipt.signedAmount().toPlainString()),
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) {include->change {Book.delete(it,p.id,include)};deleting=false;onDeleted()}
    if(giftId!=null) AlertDialog(onDismissRequest={giftId=null},title={Text("赠送时长")},text={Field("增加天数",gift,{gift=it},keyboardType=KeyboardType.Number)},confirmButton={TextButton(onClick={
        val n=gift.toIntOrNull()
        if(n!=null && n in 1..36500) {val id=giftId;change {ledger->ledger.copy(benefits=ledger.benefits.map {if(it.id==id) it.copy(giftDays=it.giftDays+n) else it})};giftId=null}
    }) {Text("增加")}},dismissButton={TextButton(onClick={giftId=null}) {Text("取消")}})
}
