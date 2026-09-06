package cn.renewboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.math.RoundingMode

private val Ink = Color(0xFF203B32)
private val Paper = Color(0xFFF8F7F2)
private val Leaf = Color(0xFF22634E)
private val Amber = Color(0xFF936015)
private val Palette = lightColorScheme(primary=Leaf, onPrimary=Color.White, background=Paper, surface=Paper, surfaceVariant=Color(0xFFEEEFE7), secondary=Amber, onBackground=Ink,onSurface=Ink, primaryContainer=Color(0xFFDCEADF),onPrimaryContainer=Ink,secondaryContainer=Color(0xFFE3E9DF),onSecondaryContainer=Ink,tertiary=Amber,tertiaryContainer=Color(0xFFF1E7D2),onTertiaryContainer=Ink,surfaceTint=Leaf,surfaceContainer=Color(0xFFF0F1E9),surfaceContainerHigh=Color(0xFFEAEDE3),surfaceContainerHighest=Color(0xFFE4E8DD),surfaceContainerLow=Color(0xFFF4F5EE),surfaceContainerLowest=Paper)
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme(colorScheme=Palette) { RenewBoard() } } }
}
@Composable private fun Title(text: String, sub: String? = null) { Column(Modifier.padding(vertical=12.dp)) { Text(text,fontSize=28.sp,fontWeight=FontWeight.Bold); if(sub!=null) Text(sub,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) } }
@Composable private fun Section(text: String) { Text(text,fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=20.dp,bottom=8.dp)) }
@Composable private fun Field(label: String, value: String, change: (String)->Unit, modifier: Modifier = Modifier, secret: Boolean=false) {
    if(secret) OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
    else {
        val c = LocalContext.current
        OutlinedTextField(value,change,label={Text(label)},modifier=modifier.fillMaxWidth(),singleLine=true,
            trailingIcon=if(label.contains("YYYY-MM-DD")) {{ IconButton(onClick={
                val date=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
                android.app.DatePickerDialog(c,{_,y,m,d->change(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
            }) { Icon(Icons.Outlined.CalendarMonth,contentDescription="选择日期") } }} else null)
    }
}
@Composable private fun Hint(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp,modifier=Modifier.padding(vertical=8.dp)) }
private fun displayTime(value: String) = runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault(value)
private fun money(totals: Map<String,BigDecimal>) = if(totals.isEmpty()) "暂无费用" else totals.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value.setScale(2,RoundingMode.HALF_UP).toPlainString()}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RenewBoard() {
    val c = LocalContext.current; val repo = remember { c.repository() }
    val ledger by repo.flow.collectAsStateWithLifecycle(initialValue=Ledger())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    fun message(s: String) { scope.launch { snack.showSnackbar(s) } }
    fun change(f: (Ledger)->Ledger) { scope.launch { try { repo.update(f) } catch(e: Exception) { message(e.message ?: "未能保存，请检查输入") } } }
    Scaffold(snackbarHost={SnackbarHost(snack)}, bottomBar={ if(!creating && editId==null && detailId==null) NavigationBar(containerColor=Paper) {
        listOf("到期","订阅","账本","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(listOf(Icons.Outlined.Event,Icons.Outlined.Bookmarks,Icons.Outlined.ReceiptLong,Icons.Outlined.Settings)[i],null)},label={Text(s)}) }
    } },floatingActionButton={if((tab==0 || tab==1) && !creating && editId==null && detailId==null) ExtendedFloatingActionButton(onClick={creating=true},modifier=Modifier.semantics { contentDescription="记一笔订阅" },icon={Icon(Icons.Outlined.Add,null)},text={Text("记一笔订阅")})}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp).imePadding().verticalScroll(rememberScrollState()).padding(bottom=96.dp)) {
            if(creating || editId!=null) {
                PlanEditor(ledger,ledger.plans.find { it.id==editId },onClose={creating=false;editId=null}) { p,bs,initial ->
                    scope.launch { try {
                        repo.update { old -> old.copy(plans=old.plans.filterNot { it.id==p.id }+p, benefits=old.benefits.filterNot { it.planId==p.id }+bs, payments=old.payments+listOfNotNull(initial)) }
                        creating=false; editId=null; message("已保存")
                    } catch(e: Exception) { message(e.message ?: "输入无效") } }
                }
            } else if(detailId!=null) {
                val p = ledger.plans.find { it.id==detailId }
                if(p==null) { detailId=null } else Detail(ledger,p,onBack={detailId=null},onEdit={editId=p.id;detailId=null},change=::change,onDeleted={detailId=null})
            } else when(tab) {
                0 -> Overview(ledger) { detailId=it }
                1 -> Subscriptions(ledger) { detailId=it }
                2 -> Receipts(ledger)
                3 -> SettingsScreen(ledger,::change,::message)
            }
        }
    }
}
@Composable private fun Overview(l: Ledger, open: (String)->Unit) {
    val today=LocalDate.now(); val upcoming=l.benefits.filter { b-> l.plans.any { it.id==b.planId && !it.archived } }.sortedBy { Book.expiry(it,l.plans.single { p->p.id==it.planId }) }
    Title("订阅簿", "${today.monthValue} 月 ${today.dayOfMonth} 日 · 把每一份权益记清楚")
    Card(colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("未来 30 天 · 预计扣款",color=Color.White.copy(alpha=.8f),fontSize=14.sp)
            Text(money(Book.forecast(l,today,today.plusDays(30))),fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(vertical=12.dp))
            Text("仅包含自动续费，尚未发生的付款",color=Color.White.copy(alpha=.8f),fontSize=12.sp)
            val forecast=Book.forecast(l,today,today.plusDays(30))
            if(forecast.keys.any { it!="CNY" }) Book.estimate(forecast,l.settings.rates)?.let { Text("手动汇率估算 ≈ ¥${it.setScale(2,RoundingMode.HALF_UP)}",color=Color.White.copy(alpha=.8f),fontSize=12.sp,modifier=Modifier.padding(top=8.dp)) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("使用中的订阅",fontSize=13.sp); Text("${l.plans.count { !it.archived }}",fontSize=25.sp,fontWeight=FontWeight.SemiBold) }
        Column { Text("7 天内到期权益",fontSize=13.sp); Text("${upcoming.count { ChronoUnit.DAYS.between(today,Book.expiry(it,l.plans.single { p->p.id==it.planId })) in 0..7 }}",fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Amber) }
    }
    Section("到期时间轴")
    if(upcoming.isEmpty()) { Text("还没有需要记挂的到期日",fontWeight=FontWeight.SemiBold); Hint("点右下角记第一笔。单项订阅或联合会员，都能放在这里。") }
    upcoming.forEach { b -> val p=l.plans.single { it.id==b.planId }; val d=Book.expiry(b,p); val days=ChronoUnit.DAYS.between(today,d)
        Row(Modifier.fillMaxWidth().clickable { open(p.id) }.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.width(66.dp)) { Text("${d.monthValue}/${d.dayOfMonth}",fontWeight=FontWeight.Bold,fontSize=20.sp); Text("${d.year}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f)) { Text(b.name,fontWeight=FontWeight.SemiBold); Text(p.name,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if(days<0) "已到期" else if(days==0L) "今天" else "$days 天后",color=if(days<=3) Amber else Leaf,fontSize=13.sp)
        }
        HorizontalDivider(color=Color(0xFFE3E6DE))
    }
}
@Composable private fun Subscriptions(l: Ledger, open: (String)->Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    Title("我的订阅","一个套餐，一份账；每项权益各有期限。")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(!archived,{archived=false},label={Text("使用中")}); FilterChip(archived,{archived=true},label={Text("已归档")}) }
    val plans=l.plans.filter { it.archived==archived }
    if(plans.isEmpty()) Hint(if(archived) "没有归档的订阅" else "从第一笔订阅开始，建立自己的权益清单。")
    plans.forEach { p -> Card(onClick={open(p.id)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFEEEFE7))) {
        Column(Modifier.padding(20.dp)) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(p.name,fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Text("${p.currency} ${p.amount}",fontWeight=FontWeight.SemiBold) }; Hint(if(p.autoRenew) "每 ${p.interval} ${p.cycle.label}自动续费" else "一次性 / 手动续费"); Text(l.benefits.filter { it.planId==p.id }.joinToString(" · ") { it.name },fontSize=13.sp) }
    } }
}
@Composable private fun Receipts(l: Ledger) {
    val today=LocalDate.now(); val month=today.withDayOfMonth(1)
    Title("付款账本","已付是真实记录，预计是未来计划。")
    Section("本月已付"); Text(money(Book.paid(l,month,month.plusMonths(1))),fontSize=26.sp,fontWeight=FontWeight.Bold)
    Section("累计已付"); Text(money(Book.paid(l)),fontSize=22.sp)
    val estimate=Book.estimate(Book.paid(l),l.settings.rates)
    Hint(if(estimate==null) "人民币估算：请在设置补充全部外币的手动汇率" else "按手动汇率估算人民币 ≈ ¥${estimate.setScale(2,RoundingMode.HALF_UP)}，非实际结算金额")
    Section("付款历史")
    if(l.payments.isEmpty()) Hint("尚无实际付款。预计续费不会自动生成付款记录。")
    l.payments.sortedByDescending { it.date }.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(p.planName,fontWeight=FontWeight.SemiBold); Hint("${p.date} · ${p.benefitIds.size} 项权益"); if(p.note.isNotBlank()) Text(p.note,fontSize=13.sp) }
            Text("${p.currency} ${p.amount}",fontWeight=FontWeight.SemiBold)
        }; HorizontalDivider()
    }
}

@Composable private fun PlanEditor(l: Ledger, existing: Plan?, onClose: ()->Unit, save: (Plan,List<Benefit>,Payment?)->Unit) {
    val id=remember { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }; var amount by rememberSaveable { mutableStateOf(existing?.amount ?: "") }
    var currency by rememberSaveable { mutableStateOf(existing?.currency ?: "CNY") }; var cycle by remember { mutableStateOf(existing?.cycle ?: Cycle.MONTH) }
    var interval by rememberSaveable { mutableStateOf((existing?.interval ?: 1).toString()) }; var auto by rememberSaveable { mutableStateOf(existing?.autoRenew ?: true) }
    var anchor by rememberSaveable { mutableStateOf(existing?.billingAnchor ?: LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }; var paid by rememberSaveable { mutableStateOf(existing==null) }
    var error by remember { mutableStateOf("") }
    var benefits by remember { mutableStateOf(l.benefits.filter { it.planId==id }.ifEmpty { listOf(Benefit(planId=id,name="",anchor=LocalDate.now().plusMonths(1).toString())) }) }
    TextButton(onClose) { Text("返回") }; Title(if(existing==null) "记一笔订阅" else "编辑订阅")
    Hint("快捷填入名称后，核对金额和到期日即可。")
    if(existing==null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("视频会员","云存储","联合会员").forEach { s -> SuggestionChip(onClick={name=s;benefits=benefits.mapIndexed { i,b->if(i==0)b.copy(name=s) else b }},label={Text(s)}) } }
    Field("订阅 / 套餐名称",name,{name=it}); Field("套餐价格（不分摊给权益）",amount,{amount=it}); Field("币种代码，如 CNY / USD / HKD",currency,{currency=it.uppercase()})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Cycle.entries.forEach { v -> FilterChip(cycle==v,{cycle=v},label={Text(v.label)}) } }
    Field("周期倍数",interval,{interval=it})
    Row(verticalAlignment=Alignment.CenterVertically) { Switch(auto,{auto=it}); Text("自动续费",Modifier.padding(start=12.dp)) }; Hint("关闭后作为一次性会员 / 手动续费管理，不计入未来预计。")
    Field("原始扣款日期 YYYY-MM-DD",anchor,{anchor=it}); Hint("保留原始日，例如 1 月 31 日按月续费为 2 月末、3 月 31 日。修改周期仅影响未来；付款历史不变。")
    Section("包含的权益")
    benefits.forEachIndexed { i,b ->
        key(b.id) { Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) { Column(Modifier.padding(16.dp)) {
            Field("权益 ${i+1} 名称",b.name,{v->benefits=benefits.map { if(it.id==b.id) it.copy(name=v) else it }})
            val p=existing?.copy(cycle=cycle,interval=interval.toIntOrNull()?.coerceIn(1,120) ?: 1)
            val date=if(p!=null) runCatching { Book.expiry(b,p).toString() }.getOrDefault(b.anchor) else b.anchor
            Field("到期日期 YYYY-MM-DD",date,{v->benefits=benefits.map { if(it.id==b.id) it.copy(anchor=v,renewals=0,giftDays=0) else it }})
            if(benefits.size>1) TextButton({benefits=benefits.filterNot { it.id==b.id }}) { Text("移除此权益") }
        } } }
    }
    TextButton({benefits=benefits+Benefit(planId=id,name="",anchor=LocalDate.now().plusMonths(1).toString())}) { Text("＋ 添加联合权益") }
    if(existing==null) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(paid,{paid=it}); Text("同时记录一笔实际付款（原始扣款日）",fontSize=13.sp) }
    Field("备注",note,{note=it})
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    Button(onClick={ try {
        val p=Plan(id,name.trim(),amount,currency,cycle,interval.toInt(),auto,anchor,existing?.paidCycles ?: if(paid) 1 else 0,existing?.archived ?: false,note)
        val bs=benefits.map { b ->
            val named = if(b.name.isBlank() && benefits.size==1) b.copy(name=name.trim()) else b
            if(existing==null && named.renewals==0 && LocalDate.parse(named.anchor)==Book.advance(LocalDate.parse(anchor),cycle,p.interval.toLong())) named.copy(anchor=anchor,renewals=1) else named
        }
        val receipt=if(existing==null && paid) Payment(planId=id,planName=p.name,amount=amount,currency=currency,date=anchor,note="首次付款",benefitIds=bs.map { it.id }) else null
        Book.validate(l.copy(plans=l.plans.filterNot { it.id==id }+p,benefits=l.benefits.filterNot { it.planId==id }+bs,payments=l.payments+listOfNotNull(receipt)))
        save(p,bs,receipt)
    } catch(e: Exception) { error="请检查名称、金额、周期和日期：${e.message ?: "格式错误"}" } },modifier=Modifier.fillMaxWidth().padding(top=16.dp)) { Text("保存订阅") }
}

@Composable private fun Detail(l: Ledger,p: Plan,onBack:()->Unit,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit) {
    var renewing by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf(false) }
    var giftId by remember { mutableStateOf<String?>(null) }; var gift by remember { mutableStateOf("7") }
    TextButton(onBack) { Text("返回") }; Title(p.name,if(p.autoRenew) "每 ${p.interval} ${p.cycle.label} · ${p.currency} ${p.amount}" else "一次性 / 手动续费 · ${p.currency} ${p.amount}")
    if(p.autoRenew) Hint("下次预计扣款 ${Book.nextCharge(p,LocalDate.now())}；实际扣款后请记入账本。")
    l.benefits.filter { it.planId==p.id }.forEach { b -> Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) { Column(Modifier.padding(20.dp)) { Text(b.name,fontSize=20.sp,fontWeight=FontWeight.SemiBold); Text("${Book.expiry(b,p)} 到期",modifier=Modifier.padding(top=8.dp)); if(b.giftDays>0) Hint("含赠送 ${b.giftDays} 天"); TextButton({giftId=b.id}) { Text("增加赠送时长") } } } }
    if(p.note.isNotBlank()) Hint(p.note)
    Button({renewing=true},Modifier.fillMaxWidth()) { Text("记录付款 / 提前续费") }
    OutlinedButton(onEdit,Modifier.fillMaxWidth()) { Text("编辑订阅与到期日") }
    TextButton({change { it.copy(plans=it.plans.map { x->if(x.id==p.id)x.copy(archived=!x.archived) else x }) }}) { Text(if(p.archived) "恢复使用" else "归档订阅") }
    TextButton({deleting=true}) { Text("删除订阅",color=MaterialTheme.colorScheme.error) }
    Section("此套餐已付记录")
    l.payments.filter { it.planId==p.id }.sortedByDescending { it.date }.forEach { Hint("${it.date}   ${it.currency} ${it.amount}   ${it.note}") }
    if(renewing) RenewalDialog(l,p,{renewing=false},change)
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("删除 ${p.name}？")},text={Text("移除订阅和权益，保留真实付款历史。此操作不能撤销，可先导出备份。")},confirmButton={TextButton({change { Book.delete(it,p.id) };deleting=false;onDeleted()}) { Text("删除") }},dismissButton={TextButton({deleting=false}){Text("取消")}})
    if(giftId!=null) AlertDialog(onDismissRequest={giftId=null},title={Text("赠送时长")},text={Column { Field("增加天数",gift,{gift=it}); Hint("仅延长此项权益，不生成付款，也不改变套餐扣款日。") }},confirmButton={TextButton({ val n=gift.toIntOrNull(); if(n!=null && n in 1..36500) { val id=giftId;change { it.copy(benefits=it.benefits.map { b->if(b.id==id)b.copy(giftDays=b.giftDays+n) else b }) }; giftId=null } }){Text("增加")}},dismissButton={TextButton({giftId=null}){Text("取消")}})
}
@Composable private fun RenewalDialog(l: Ledger,p: Plan,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    var amount by remember { mutableStateOf(p.amount) }; var date by remember { mutableStateOf(LocalDate.now().toString()) }; var note by remember { mutableStateOf("续费") }
    var selected by remember { mutableStateOf(l.benefits.filter { it.planId==p.id }.map { it.id }.toSet()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("记录实际付款")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Field("付款金额 ${p.currency}",amount,{amount=it}); Field("付款日期 YYYY-MM-DD",date,{date=it}); Field("付款备注",note,{note=it})
        Hint("勾选本次续费权益，每项延长 ${p.interval} ${p.cycle.label}。提前续费从原到期日延长；已过期从付款日开始。套餐只记录一笔付款。")
        l.benefits.filter { it.planId==p.id }.forEach { b->Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(b.id in selected,{selected=if(it)selected+b.id else selected-b.id}); Text(b.name) } }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try { val d=LocalDate.parse(date); Book.renew(l,p.id,amount,d,selected,note); change { Book.renew(it,p.id,amount,d,selected,note) };close() }catch(e:Exception){error="请检查日期、金额与权益选择"}}){Text("确认付款")}},dismissButton={TextButton(close){Text("取消")}})
}

@Composable private fun SettingsScreen(l: Ledger,change:((Ledger)->Ledger)->Unit,message:(String)->Unit) {
    val c=LocalContext.current; val scope=rememberCoroutineScope(); val config=remember { CredentialsStore(c) }
    var url by remember { mutableStateOf(config.url) }; var user by remember { mutableStateOf(config.user) }; var password by remember { mutableStateOf("") }
    var reminder by remember(l.settings.reminderDays) { mutableStateOf(l.settings.reminderDays.joinToString(",")) }
    var rates by remember(l.settings.rates) { mutableStateOf(l.settings.rates.entries.joinToString(",") { "${it.key}=${it.value}" }) }
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf(config.status) }; var success by remember { mutableStateOf(config.lastSuccess) }
    var preview by remember { mutableStateOf<Backup?>(null) }; var remote by remember { mutableStateOf<List<String>?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> message(if(allowed) "已允许通知" else "通知未授权，订阅和备份仍可使用"); Jobs.schedule(c) }
    fun operation(block:suspend ()->Unit) { scope.launch { busy=true; try { block() } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; message(if(e is DavException) e.message ?: "WebDAV 失败" else "操作失败，请检查文件、网络或应用密码") } finally { busy=false;status=config.status;success=config.lastSuccess } } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { val text=Book.encode(c.repository().read()); c.contentResolver.openOutputStream(uri,"wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入") };message("已导出，不包含 WebDAV 密码")
    } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) operation {
        preview=withContext(Dispatchers.IO) { val input=c.contentResolver.openInputStream(uri) ?: error("无法读取"); input.use { val bytes=it.readBytesLimited(); Book.decode(String(bytes,Charsets.UTF_8)) } }
    } }
    Title("设置","数据保存在本机，备份由你掌握。")
    Section("到期提醒")
    Field("提前天数，用逗号分隔；0 表示当天",reminder,{reminder=it})
    Hint("默认提前 3 天和当天。同一到期事件当天最多提醒一次；系统省电和调度可能延迟提醒。清空天数可关闭。")
    OutlinedButton({if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else message("请在系统应用设置中管理通知授权")}) { Text("允许到期通知") }
    Section("手动汇率")
    Field("1 单位外币折合人民币，如 USD=7.2,HKD=0.92",rates,{rates=it})
    Hint("各币种分别记账，汇率仅用于标明为估算的人民币总额。")
    Button({try {
        val days=if(reminder.isBlank()) emptyList() else reminder.replace('，',',').split(',').map { it.trim().toInt() }
        val map=if(rates.isBlank()) emptyMap() else rates.replace('，',',').split(',').associate { val v=it.trim().split('=');require(v.size==2);v[0].trim().uppercase() to v[1].trim() }
        val next=l.copy(settings=Settings(days,map));Book.validate(next);change { it.copy(settings=next.settings) };message("提醒与汇率已保存")
    }catch(e:Exception){message("请检查提醒天数和汇率格式")}}) { Text("保存提醒与汇率") }
    Section("备份与恢复")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton({export.launch("订阅簿-${LocalDate.now()}.json")},enabled=!busy) { Text("导出 JSON") }; OutlinedButton({import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy){Text("从文件恢复") } }
    Section("坚果云 · WebDAV")
    Hint("使用坚果云专用应用密码。保存后在线合并变更自动备份；远端 RenewBoard 目录仅保留最近 10 份成功自动备份，手动备份不自动删除。备份含业务数据，密码仅加密存于本机。")
    Field("WebDAV 地址",url,{url=it});Field("坚果云账号",user,{user=it});Field(if(config.configured) "应用密码（留空保留）" else "专用应用密码",password,{password=it},secret=true)
    Button({try { config.save(url.trim(),user.trim(),password);password="";Jobs.backup(c);message("已保存，将在联网后自动备份") }catch(e:Exception){message(e.message ?: "配置无效")}},enabled=!busy) { Text("保存并启用自动备份") }
    Text("最后成功：${displayTime(success)}",fontSize=13.sp,modifier=Modifier.padding(top=8.dp)); Hint(status)
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({operation { withContext(Dispatchers.IO) { try { config.client().upload(c.repository().read(),false);config.result() } catch(e:Exception){config.result("手动备份失败，请检查网络与应用密码");throw e} };message("手动备份成功，已下载核验") }},enabled=!busy) { Text("立即备份") }
        OutlinedButton({operation { remote=withContext(Dispatchers.IO) { config.client().list() } }},enabled=!busy) { Text("选择云端备份") }
    }
    TextButton({disconnect=true},enabled=!busy) { Text("断开 WebDAV") }
    TextButton({ operation { licenseText=withContext(Dispatchers.IO) { c.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name -> name+"\n"+c.assets.open("licenses/$name").bufferedReader().use { it.readText() } } } } }) { Text("开源许可证") }
    if(licenseText!=null) AlertDialog(onDismissRequest={licenseText=null},title={Text("开源许可证")},text={Text(licenseText!!,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton({licenseText=null}){Text("关闭")}})
    Hint("订阅簿 1.0 · 原创代码 MIT\n不处理付款、不做多设备合并。真实坚果云连接需由你在本机配置。")
    if(remote!=null) AlertDialog(onDismissRequest={remote=null},title={Text("选择备份")},text={Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
        if(remote!!.isEmpty()) Text("专用目录中没有备份")
        remote!!.forEach { name->TextButton({remote=null;operation { preview=withContext(Dispatchers.IO){config.client().download(name)} }}) { Text(name,fontSize=12.sp) } }
    }},confirmButton={TextButton({remote=null}) { Text("关闭") }})
    if(preview!=null) { val b=preview!!;AlertDialog(onDismissRequest={preview=null},title={Text("确认替换本机数据？")},text={Text("备份日期：${displayTime(b.createdAt)}\n${b.data.plans.size} 个订阅 · ${b.data.benefits.size} 项权益 · ${b.data.payments.size} 笔付款\n\n将整体替换本机账本、提醒与汇率。WebDAV 凭据不变。可取消后先导出本机备份。")},confirmButton={TextButton({preview=null;operation { c.repository().restore(b);message("恢复完成") }}){Text("确认替换")}},dismissButton={TextButton({preview=null}){Text("取消")}}) }
    if(disconnect) AlertDialog(onDismissRequest={disconnect=false},title={Text("断开 WebDAV？")},text={Text("删除本机保存的账号和应用密码，停止后续自动备份。本机账本和远端备份不删除。")},confirmButton={TextButton({config.disconnect();password="";user="";disconnect=false;status=config.status;success=config.lastSuccess;message("已断开")}){Text("断开")}},dismissButton={TextButton({disconnect=false}){Text("取消")}})
}
private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) { val n=read(buffer);if(n<0)break;result.write(buffer,0,n);require(result.size()<=16*1024*1024) }
    return result.toByteArray()
}
