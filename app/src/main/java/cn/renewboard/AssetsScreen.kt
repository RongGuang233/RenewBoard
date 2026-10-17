package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun assetMoney(v:BigDecimal,currency:String="CNY")=(if(currency=="CNY") "¥" else "$currency ")+v.setScale(2,RoundingMode.HALF_UP).toPlainString()
private fun assetIcon(category:AssetCategory)=when(category) {
    AssetCategory.CASH->Icons.Outlined.AccountBalanceWallet
    AssetCategory.INVESTMENT->Icons.Outlined.TrendingUp
    AssetCategory.RECEIVABLE->Icons.Outlined.CallReceived
    AssetCategory.DEBT->Icons.Outlined.CreditCard
    AssetCategory.PREPAID->Icons.Outlined.Savings
    AssetCategory.TUITION->Icons.Outlined.School
}

// Intent: quick balance check after lessons. The blue net figure leads, white account groups carry detail.
// Existing Material typography/tokens, 20dp page padding and 4dp spacing rhythm match RenewBoard.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AssetsScreen(
    ledger:Ledger,save:suspend ((Ledger)->Ledger)->Unit,openPlan:(String)->Unit,
    tuitionContent:@Composable ()->Unit,
    onSubpageChange:(Boolean)->Unit={}
) {
    var page by rememberSaveable {mutableStateOf("home")}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var hidden by rememberSaveable {mutableStateOf(false)}
    var showArchived by rememberSaveable {mutableStateOf(false)}
    val accounts=Assets.accounts(ledger)
    val account=accounts.find {it.id==selected}
    val sub=page!="home"
    LaunchedEffect(sub) {onSubpageChange(sub)}
    DisposableEffect(Unit) {onDispose {onSubpageChange(false)}}
    fun back() { page=if(page in listOf("edit","balance") && account!=null) "detail" else "home" }
    BackHandler(sub) {back()}
    Column(Modifier.fillMaxSize().pageTransition(page)) {
        if(sub) TopAppBar(title={Text(when(page) {"detail"->"账户详情";"edit"->if(selected==null) "添加账户" else "账户设置";"balance"->"更新余额";"trend"->"净资产变化";else->"学费同步"})},
            navigationIcon={PageBack(back=::back)},windowInsets=WindowInsets(0,0,0,0))
        when(page) {
            "edit" -> key(selected) {AssetEditor(account,ledger,save,onSaved={selected=it;page="detail"},onTuition={page="tuition"})}
            "balance" -> if(account!=null) AssetBalanceEditor(account,save) {page="detail"} else {page="home"}
            "detail" -> if(account!=null) AssetDetail(ledger,account,hidden,onEdit={page="edit"},onBalance={page="balance"},openPlan=openPlan,onTuition={page="tuition"}) else {page="home"}
            "trend" -> AssetTrend(ledger.assets.history,hidden)
            "tuition" -> Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {tuitionContent()}
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("我的资产",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    IconButton(onClick={hidden=!hidden}) {Icon(if(hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(hidden) "显示资产金额" else "隐藏资产金额")}
                    FilledTonalIconButton(onClick={selected=null;page="edit"}) {Icon(Icons.Outlined.Add,"添加账户")}
                }
                val totals=Assets.totals(ledger)
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(if(totals.pending>0) "已知净资产" else "净资产",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                            TextButton(onClick={page="trend"}) {Text("查看变化");Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))}
                        }
                        Text(if(hidden) "••••••" else assetMoney(totals.net),fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                            Column {Text("总资产",style=MaterialTheme.typography.labelMedium);Text(if(hidden) "••••" else assetMoney(totals.assets),fontWeight=FontWeight.SemiBold)}
                            Column {Text("总负债",style=MaterialTheme.typography.labelMedium);Text(if(hidden) "••••" else assetMoney(totals.liabilities),fontWeight=FontWeight.SemiBold)}
                        }
                        if(totals.pending>0) Text("${totals.pending} 个账户待折算或核实，暂未计入",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("账户",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    TextButton(onClick={page="tuition"}) {Icon(Icons.Outlined.Sync,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("学费同步")}
                }
                if(accounts.isEmpty()) Hint("添加银行、现金或投资账户，记录此刻的余额。以后只需更新余额。")
                AssetCategory.entries.forEach {category->
                    val group=accounts.filter {it.category==category && (showArchived || !it.archived)}
                    if(group.isNotEmpty()) {
                        val subtotal=Assets.totals(ledger,group)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(category.label,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelLarge)
                            Text(if(hidden) "••••" else "计入净值 ${assetMoney(subtotal.net)}"+(if(subtotal.pending>0) " · 待核实" else ""),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(16.dp)) {
                            group.forEachIndexed {index,a->
                                Row(Modifier.fillMaxWidth().clickable {selected=a.id;page="detail"}.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Icon(assetIcon(a.category),null,tint=MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name,fontWeight=FontWeight.SemiBold)
                                        Text(listOfNotNull(a.asOf,if(!a.included) "不计入" else null,if(a.archived) "已归档 · 仍按设置统计" else null).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Assets.value(ledger,a).pendingReason?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                                    }
                                    Column(horizontalAlignment=Alignment.End) {
                                        Text(if(hidden) "••••" else assetMoney(if(a.category==AssetCategory.TUITION) BigDecimal(a.balance).abs() else BigDecimal(a.balance),a.currency),fontWeight=FontWeight.SemiBold)
                                        if(a.category==AssetCategory.TUITION) Text(if(BigDecimal(a.balance).signum()>0) "待上课学费" else if(BigDecimal(a.balance).signum()<0) "待收学费" else "已结清",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if(index<group.lastIndex) HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
                if(accounts.any {it.archived}) TextButton(onClick={showArchived=!showArchived}) {Text(if(showArchived) "收起归档账户" else "显示归档账户（仍按设置计入）")}
            }
        }
    }
}

@Composable private fun AssetDetail(l:Ledger,a:AssetAccount,hidden:Boolean,onEdit:()->Unit,onBalance:()->Unit,openPlan:(String)->Unit,onTuition:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {Icon(assetIcon(a.category),null,tint=MaterialTheme.colorScheme.primary);Text(a.name,fontSize=24.sp,fontWeight=FontWeight.Bold)}
        Text(a.category.label+(if(a.subtype.isNotBlank()) " · ${a.subtype}" else ""),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(hidden) "••••••" else assetMoney(BigDecimal(a.balance),a.currency),fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
        Text("更新于 ${a.asOf} · "+(if(a.included) "计入净资产" else "不计入净资产"),style=MaterialTheme.typography.bodyMedium)
        Assets.value(l,a).pendingReason?.let {Hint(it)}
        if(a.category==AssetCategory.TUITION) Hint("正余额是尚未上课的预收款，计入负债；负余额是已上课未收取的学费，计入应收款。银行余额需单独更新。")
        a.cnyRate?.let {Hint("折算依据：1 ${a.currency} = $it CNY")}
        if(a.note.isNotBlank()) Text(a.note)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={when {a.planId!=null->openPlan(a.planId);a.studentId!=null->onTuition();else->onBalance()}},modifier=Modifier.weight(1f)) {Text(if(a.planId!=null) "打开来源账户" else if(a.studentId!=null) "刷新学费" else "更新余额")}
            OutlinedButton(onClick=onEdit) {Text("账户设置")}
        }
        HorizontalDivider()
        Text("余额记录",fontWeight=FontWeight.SemiBold)
        if(a.history.isEmpty()) Hint(if(a.planId!=null) "余额沿用来源账户，不重复建立账户流水。" else "暂无余额更新记录")
        a.history.asReversed().forEach {record->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text(record.kind);Text(record.date,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Text(if(hidden) "••••" else assetMoney(BigDecimal(record.balance),a.currency))
            }
        }
    }
}

@Composable private fun AssetEditor(a:AssetAccount?,l:Ledger,save:suspend ((Ledger)->Ledger)->Unit,onSaved:(String)->Unit,onTuition:()->Unit) {
    var name by rememberSaveable {mutableStateOf(a?.name.orEmpty())}
    var category by rememberSaveable {mutableStateOf(a?.category?.name ?: AssetCategory.CASH.name)}
    var subtype by rememberSaveable {mutableStateOf(a?.subtype.orEmpty())}
    var balance by rememberSaveable {mutableStateOf(a?.balance ?: "")}
    var currency by rememberSaveable {mutableStateOf(a?.currency ?: "CNY")}
    var asOf by rememberSaveable {mutableStateOf(a?.asOf ?: LocalDate.now().toString())}
    var included by rememberSaveable {mutableStateOf(a?.included ?: true)}
    var archived by rememberSaveable {mutableStateOf(a?.archived ?: false)}
    var rate by rememberSaveable {mutableStateOf(a?.cnyRate.orEmpty())}
    var eligible by rememberSaveable {mutableStateOf(a?.eligibleAmount?.takeIf {a.eligibleBasisBalance==a.balance}.orEmpty())}
    var note by rememberSaveable {mutableStateOf(a?.note.orEmpty())}
    var notesOpen by rememberSaveable {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    var typeMenu by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val cat=AssetCategory.valueOf(category)
    val api=a?.planId?.let {id->l.plans.find {it.id==id}?.usageBalance!=null}==true
    val knownPlatformAmount=a?.eligibleAmount?.takeIf {a.eligibleFromPlatform && a.eligibleBasisBalance==a.balance}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(a==null) Box {
            OutlinedButton(onClick={menu=true},modifier=Modifier.fillMaxWidth().semantics {contentDescription="选择账户分类"}) {Text(cat.label);Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(menu,onDismissRequest={menu=false}) {AssetCategory.entries.forEach {c->DropdownMenuItem(text={Text(c.label)},onClick={category=c.name;subtype="";included=Assets.defaultIncluded(c);menu=false})}}
        }
        if(cat==AssetCategory.TUITION && a==null) {
            Hint("家教学费由 LessonManager 导入，学生标识用于后续自动更新，避免重复输入。")
            Button(onClick=onTuition) {Text("连接并导入学费")}
        } else {
            val types=when(cat) {
                AssetCategory.CASH->listOf("银行卡","现金","微信","支付宝","其他")
                AssetCategory.INVESTMENT->listOf("余额宝","基金","股票","其他")
                AssetCategory.RECEIVABLE->listOf("借出款","可退押金","其他")
                AssetCategory.DEBT->listOf("信用卡","花呗","贷款","借入款","其他")
                else->emptyList()
            }
            if(types.isNotEmpty()) Box {
                OutlinedButton(onClick={typeMenu=true},modifier=Modifier.fillMaxWidth().semantics {contentDescription="选择账户类型"}) {Text(subtype.ifBlank {"账户类型（可选）"});Icon(Icons.Outlined.ExpandMore,null)}
                DropdownMenu(typeMenu,onDismissRequest={typeMenu=false}) {types.forEach {type->DropdownMenuItem(text={Text(type)},onClick={subtype=type;typeMenu=false})}}
            }
            if(cat==AssetCategory.PREPAID && a?.planId==null) {
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(subtype=="饭卡",onCheckedChange={subtype=if(it) "饭卡" else "";included=Assets.defaultIncluded(cat,subtype)});Text("这是饭卡（默认计入净资产）")}
                Hint("已有话费和 API 余额会自动出现在账户列表，请在原账户设置中选择是否计入。")
            }
            Field("账户名称",name,{name=it})
            if(a==null) {MoneyField(if(cat==AssetCategory.DEBT) "剩余欠款" else "当前余额",balance,{balance=it});CurrencyPicker(currency) {currency=it};Field("余额日期",asOf,{asOf=it},dateField=true)}
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {Text("计入净资产",modifier=Modifier.weight(1f));Switch(included,{included=it},modifier=Modifier.semantics {contentDescription="计入净资产开关"})}
            if(currency!="CNY") MoneyField("折算汇率（1 $currency = ? CNY）",rate,{rate=it},supportingText="留空保留原币余额，列为待折算账户")
            if(api && included) {
                if(knownPlatformAmount!=null) Hint("平台已确认充值余额 $knownPlatformAmount；赠送余额不计入。")
                else MoneyField("可计入的充值余额",eligible,{eligible=it},supportingText="仅填本人充值部分，排除平台赠送；留空暂不计入。余额变化后需重新确认。")
            }
            if(a!=null) Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {Column(Modifier.weight(1f)) {Text("归档账户");Text("仅收起列表，统计规则保持不变",style=MaterialTheme.typography.bodySmall)};Switch(archived,{archived=it},modifier=Modifier.semantics {contentDescription="归档账户开关"})}
            TextButton(onClick={notesOpen=!notesOpen}) {Text(if(notesOpen) "收起备注" else "添加或编辑备注")}
            if(notesOpen) Field("备注",note,{note=it})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {
                        val draft=(a ?: AssetAccount(name=name,category=cat)).copy(name=name.trim(),category=cat,subtype=subtype,balance=if(a==null) balance.trim() else a.balance,currency=currency,
                            asOf=asOf,included=included,archived=archived,cnyRate=rate.trim().ifBlank {null},note=note.trim(),
                            eligibleAmount=if(api) knownPlatformAmount ?: eligible.trim().ifBlank {null} else a?.eligibleAmount,
                            eligibleBasisBalance=if(api && (knownPlatformAmount!=null || eligible.isNotBlank())) a?.balance else null,eligibleFromPlatform=knownPlatformAmount!=null,
                            history=a?.history ?: listOf(AssetBalanceRecord(balance=balance.trim(),date=asOf,kind="初始余额")))
                        save {current->
                            val latest=if(a==null) null else Assets.accounts(current).find {it.id==a.id}
                            val merged=if(latest==null) draft else draft.copy(
                                balance=latest.balance,currency=latest.currency,asOf=latest.asOf,history=latest.history,
                                verified=latest.verified,sourceMissing=latest.sourceMissing,sourceUnverified=latest.sourceUnverified,sourceName=latest.sourceName,
                                cnyRate=draft.cnyRate.takeIf {draft.currency==latest.currency},
                                eligibleAmount=if(knownPlatformAmount!=null || latest.eligibleFromPlatform) latest.eligibleAmount else draft.eligibleAmount,
                                eligibleBasisBalance=if(knownPlatformAmount!=null || latest.eligibleFromPlatform) latest.eligibleBasisBalance else draft.eligibleBasisBalance,
                                eligibleFromPlatform=latest.eligibleFromPlatform)
                            Assets.put(current,merged)
                        }
                        onSaved(draft.id)
                    } catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "账户保存失败"} finally {busy=false}
                }
            },modifier=Modifier.fillMaxWidth()) {Text(if(busy) "正在保存…" else "保存账户")}
        }
    }
}

@Composable private fun AssetBalanceEditor(a:AssetAccount,save:suspend ((Ledger)->Ledger)->Unit,onSaved:()->Unit) {
    var amount by rememberSaveable(a.id) {mutableStateOf(a.balance)}
    var date by rememberSaveable(a.id) {mutableStateOf(LocalDate.now().toString())}
    var correction by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(a.name,fontSize=24.sp,fontWeight=FontWeight.Bold)
        Text("原余额 ${assetMoney(BigDecimal(a.balance),a.currency)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(if(a.category==AssetCategory.DEBT) "新的剩余欠款" else "新的当前余额",amount,{amount=it})
        Field("余额日期",date,{date=it},dateField=true)
        amount.toBigDecimalOrNull()?.let {Text("余额差额 ${assetMoney(it-BigDecimal(a.balance),a.currency)}")}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(correction,onCheckedChange={correction=it});Text("校正上次录入错误")}
        Hint(if(correction) "替换最近一条余额记录，净资产变化标注为校正。" else "保存本次余额观察，不生成日常收入或支出。")
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy,onClick={busy=true;scope.launch {try {save {Assets.updateBalance(it,a.id,amount.trim(),date,correction)};onSaved()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "余额保存失败"} finally {busy=false}}},modifier=Modifier.fillMaxWidth()) {Text(if(busy) "正在保存…" else "保存余额")}
    }
}

@Composable private fun AssetTrend(points:List<NetWorthPoint>,hidden:Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Hint("只展示实际记录过的余额。统计范围调整和余额校正不代表收入，未更新的日期不补造数据。")
        if(points.isEmpty()) Hint("添加账户或更新余额后，这里会出现净资产变化。")
        if(points.size>1 && !hidden) {
            val values=points.map {(BigDecimal(it.assets)-BigDecimal(it.liabilities)).toFloat()}
            val min=values.min();val max=values.max();val span=(max-min).takeIf {it>0} ?: 1f
            val times=points.map {java.time.Instant.parse(it.at).toEpochMilli()}
            val duration=(times.last()-times.first()).coerceAtLeast(1L)
            val color=MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                values.zipWithNext().forEachIndexed {i,(a,b)->drawLine(color,Offset((times[i]-times.first()).toFloat()/duration*size.width,size.height-(a-min)/span*size.height),Offset((times[i+1]-times.first()).toFloat()/duration*size.width,size.height-(b-min)/span*size.height),strokeWidth=3.dp.toPx())}
            }
            val dateFormat=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(dateFormat.format(java.time.Instant.parse(points.first().at)),style=MaterialTheme.typography.labelSmall);Text(dateFormat.format(java.time.Instant.parse(points.last().at)),style=MaterialTheme.typography.labelSmall)}
        }
        points.asReversed().forEach {p->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column {Text(p.reason);Text(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(p.at)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Column(horizontalAlignment=Alignment.End) {Text(if(hidden) "••••" else assetMoney(BigDecimal(p.assets)-BigDecimal(p.liabilities)),fontWeight=FontWeight.SemiBold);if(p.pending>0) Text("${p.pending} 个待核实",style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
