package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable internal enum class UsageAction { TOP_UP, CALIBRATE }
@Serializable private data class UsageDraft(val planId:String,val amount:String,val date:String,val cnyAmount:String="",val affectBalance:Boolean=true)

@Composable internal fun UsageBalanceCard(p:Plan,compact:Boolean=false,open:()->Unit) {
    Card(onClick=open,modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=if(compact) Color.White else MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(if(compact) 12.dp else 16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ServiceIcon(p.name,size=if(compact) 32.dp else 48.dp)
            Column(Modifier.weight(1f)) {
                Text(p.name,fontWeight=FontWeight.SemiBold,fontSize=if(compact) 17.sp else MaterialTheme.typography.bodyLarge.fontSize)
                Text(if(p.archived) "已归档" else "${p.usageBalance!!.asOf} 更新",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(displayMoney(p.currency,p.usageBalance!!.balance),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary,fontSize=if(compact) 18.sp else MaterialTheme.typography.bodyLarge.fontSize)
                if(!compact) Text("平台余额",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable internal fun UsageBalanceDetail(l:Ledger,p:Plan,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit,
    openPayment:(String)->Unit,openHistory:()->Unit,openAction:(UsageAction)->Unit,openConnection:()->Unit={}) {
    val app=LocalContext.current.applicationContext as RenewApp
    var menu by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var disconnecting by remember {mutableStateOf(false)}
    val initialConnection=remember(p.id) {runCatching {app.platformConnections.connection(p.id)}}
    var connection by remember(p.id) {mutableStateOf(initialConnection.getOrNull())}
    val preset=servicePresetFor(p.name)
    val canConnect=connection!=null || preset==null || preset.name=="DeepSeek API"
    var refreshing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(initialConnection.exceptionOrNull()?.let {"无法读取平台连接，请重新连接"})}
    var breakdown by rememberSaveable {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    fun refresh() {
        if(refreshing) return
        refreshing=true;error=null
        scope.launch {
            try {app.platformBalances.refresh(p.id);connection=app.platformConnections.connection(p.id)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=platformBalanceError(e)}
            finally {refreshing=false}
        }
    }
    Scaffold(modifier=Modifier.imePadding(),containerColor=MaterialTheme.colorScheme.background,contentWindowInsets=WindowInsets(0,0,0,0),bottomBar={
        Surface {Button(onClick={if(p.archived) change {old->old.copy(plans=old.plans.map {if(it.id==p.id) it.copy(archived=false) else it})} else openAction(UsageAction.TOP_UP)},
            modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=48.dp)) {Text(if(p.archived) "恢复使用" else "记录充值")}}
    }) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(p.name,size=40.dp);Text(p.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Box {
                    IconButton({menu=true},enabled=!refreshing) {Icon(Icons.Outlined.MoreVert,"余额账户更多操作")}
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("编辑账户")},onClick={menu=false;onEdit()})
                        if(!p.archived && canConnect) DropdownMenuItem(text={Text(if(connection==null) "连接平台" else "更换密钥")},onClick={menu=false;openConnection()})
                        if(connection!=null) DropdownMenuItem(text={Text("断开连接")},onClick={menu=false;disconnecting=true})
                        if(!p.archived) DropdownMenuItem(text={Text("归档订阅")},onClick={menu=false;change {old->old.copy(plans=old.plans.map {if(it.id==p.id) it.copy(archived=true) else it})}})
                        DropdownMenuItem(text={Text("删除订阅")},onClick={menu=false;deleting=true})
                    }
                }
            }
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(p.archived) "已归档 · 平台余额" else "平台余额",modifier=Modifier.weight(1f))
                        if(connection!=null && !p.archived) IconButton(::refresh,enabled=!refreshing) {
                            if(refreshing) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                            else Icon(Icons.Outlined.Refresh,"刷新余额")
                        }
                    }
                    Text(displayMoney(p.currency,p.usageBalance!!.balance),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                    val snapshot=connection?.snapshot
                    if(snapshot==null) Text("${p.usageBalance.asOf} 查询",style=MaterialTheme.typography.bodySmall)
                    else {
                        Text("最近联网查询 ${snapshot.queriedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",style=MaterialTheme.typography.bodySmall)
                        if(snapshot.currency!=p.currency || java.math.BigDecimal(snapshot.totalBalance).compareTo(java.math.BigDecimal(p.usageBalance.balance))!=0 || snapshot.queriedAt.atZone(ZoneId.systemDefault()).toLocalDate().toString()!=p.usageBalance.asOf)
                            Text("${p.usageBalance.asOf} 手动更新",style=MaterialTheme.typography.bodySmall)
                        TextButton({breakdown=!breakdown},contentPadding=PaddingValues(0.dp)) {
                            Text(if(breakdown) "收起查询明细" else "查询明细")
                            Icon(if(breakdown) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)
                        }
                        if(breakdown) {
                            Text("充值余额 ${displayMoney(snapshot.currency,snapshot.toppedUpBalance)}",style=MaterialTheme.typography.bodyMedium)
                            Text("赠金 ${displayMoney(snapshot.currency,snapshot.grantedBalance)}",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(!p.archived) OutlinedButton(onClick={openAction(UsageAction.CALIBRATE)},modifier=Modifier.fillMaxWidth()) {Text("更新余额")}
            Text("充值计支出，余额变化不重复记账。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(p.note.isNotBlank()) Text(p.note)
            val records=l.payments.filter {it.planId==p.id}.sortedByDescending {it.date}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("最近充值",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                if(records.isNotEmpty()) TextButton(openHistory) {Text("全部记录")}
            }
            if(records.isEmpty()) Hint("还没有充值记录")
            records.take(3).forEach {payment->
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable {openPayment(payment.id)},verticalAlignment=Alignment.CenterVertically) {
                    Text((if(payment.refundOf!=null) "退款 · " else "")+payment.date,modifier=Modifier.weight(1f))
                    Text(payment.signedCny()?.let {displayMoney("CNY",it.toPlainString())} ?: displayMoney(payment.currency,payment.signedAmount().toPlainString()),fontWeight=FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if(disconnecting) AlertDialog(onDismissRequest={disconnecting=false},title={Text("断开 DeepSeek 连接？")},text={Text("保留当前余额和充值记录，删除本机连接密钥。")},confirmButton={TextButton({
        scope.launch {
            try {app.platformBalances.disconnect(p.id);connection=null;breakdown=false;error=null;disconnecting=false}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=platformBalanceError(e);disconnecting=false}
        }
    }) {Text("断开连接")}},dismissButton={TextButton({disconnecting=false}) {Text("取消")}})
    if(deleting) DeletePlanDialog(l,p,{deleting=false}) {include->change {Book.delete(it,p.id,include)};deleting=false;onDeleted()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun UsageBalanceEditor(l:Ledger,p:Plan,action:UsageAction,close:()->Unit,save:suspend ((Ledger)->Ledger)->Unit) {
    val context=LocalContext.current
    val store=remember {DraftStore(context)}
    val key="usage:${p.id}:$action"
    val defaults=remember(key) {UsageDraft(p.id,if(action==UsageAction.CALIBRATE) p.usageBalance!!.balance else "",LocalDate.now().toString())}
    val restored=remember(key) {runCatching {store.read(key)?.let {Book.json.decodeFromString<UsageDraft>(it)}}.getOrNull()?.takeIf {it.planId==p.id}}
    var draft by remember(key) {mutableStateOf(restored ?: defaults)}
    var exit by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun update(value:UsageDraft) {if(!saving) {draft=value;error=null;if(value==defaults) store.remove(key) else store.write(key,Book.json.encodeToString(value))}}
    fun leave() {if(!saving) {if(draft!=defaults) exit=true else close()}}
    fun updated(old:Ledger,value:UsageDraft)=if(action==UsageAction.TOP_UP) UsageBalances.topUp(old,p.id,value.amount,LocalDate.parse(value.date),value.cnyAmount.takeIf {p.currency!="CNY"},value.affectBalance)
        else UsageBalances.calibrate(old,p.id,value.amount,LocalDate.parse(value.date))
    fun commit() {
        if(saving) return
        try {
            val value=draft;updated(l,value);saving=true;error=null
            scope.launch {
                try {save {updated(it,value)};store.remove(key);close()}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {saving=false;error=e.message ?: "保存失败，请重试"}
            }
        } catch(e:Exception) {error=if(e is java.time.format.DateTimeParseException) "请检查日期" else e.message ?: "请检查金额"}
    }
    BackHandler {leave()}
    Scaffold(modifier=Modifier.imePadding(),contentWindowInsets=WindowInsets(0,0,0,0),topBar={TopAppBar(windowInsets=WindowInsets(0,0,0,0),
        title={Text(if(action==UsageAction.TOP_UP) "记录充值" else "更新余额")},navigationIcon={PageBack("返回余额账户",::leave)})},bottomBar={
        Surface {Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
            Button(::commit,enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(saving) "正在保存…" else if(action==UsageAction.TOP_UP) "保存充值" else "保存余额")}
        }}
    }) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(p.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            if(restored!=null) Text("已恢复草稿",style=MaterialTheme.typography.bodySmall)
            MoneyField(if(action==UsageAction.TOP_UP) "充值金额 ${if(p.currency=="CNY") "¥" else p.currency}" else "查询到的余额",draft.amount,{update(draft.copy(amount=it))})
            Field(if(action==UsageAction.TOP_UP) "充值日期" else "余额查询日期",draft.date,{update(draft.copy(date=it))},dateField=true)
            if(action==UsageAction.TOP_UP) {
                if(p.currency!="CNY") MoneyField("人民币实付金额",draft.cnyAmount,{update(draft.copy(cnyAmount=it))},supportingText="按充值账单填写，保存后固定")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(draft.affectBalance,{update(draft.copy(affectBalance=it))},enabled=!saving)
                    Text("同时增加余额")
                }
                Text(if(draft.affectBalance) "充值计入支出；余额增加充值金额。" else "仅补记充值支出，余额已包含这笔充值。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("填写平台当前显示的余额，不再新增支出。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(exit) AlertDialog(onDismissRequest={exit=false},title={Text("保留余额操作草稿？")},confirmButton={TextButton({store.write(key,Book.json.encodeToString(draft));close()}) {Text("保留草稿")}},dismissButton={TextButton({store.remove(key);close()}) {Text("放弃修改")}})
}
