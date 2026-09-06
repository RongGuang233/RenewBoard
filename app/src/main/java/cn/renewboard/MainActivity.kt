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

private val Ink = Color(0xFF152B49)
private val Paper = Color(0xFFF5F7FC)
private val Leaf = Color(0xFF245BD6)
private val Amber = Color(0xFFAD501F)
private val Palette = lightColorScheme(primary=Leaf, onPrimary=Color.White, background=Paper, surface=Paper, surfaceVariant=Color(0xFFE8EEF9), secondary=Amber, onBackground=Ink,onSurface=Ink, primaryContainer=Color(0xFFE2EBFF),onPrimaryContainer=Ink,secondaryContainer=Color(0xFFE2EBFF),onSecondaryContainer=Ink,tertiary=Amber,tertiaryContainer=Color(0xFFF1E7D2),onTertiaryContainer=Ink,surfaceTint=Leaf,surfaceContainer=Color(0xFFF0F3FA),surfaceContainerHigh=Color(0xFFEAF0FB),surfaceContainerHighest=Color(0xFFE2EAF8),surfaceContainerLow=Color(0xFFF4F6FC),surfaceContainerLowest=Paper)
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme(colorScheme=Palette) { RenewBoard() } } }
}
@Composable private fun Title(text: String, sub: String? = null, action:(()->Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(text,fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold); if(sub!=null) Text(sub,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
        if(action!=null) FilledTonalIconButton(onClick=action,modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Add,contentDescription="记一笔订阅") }
    }
}
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
@Composable private fun Hint(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp,modifier=Modifier.padding(vertical=8.dp)) }
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
    } }) { padding ->
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
                0 -> Overview(ledger,onAdd={creating=true}) { detailId=it }
                1 -> Subscriptions(ledger,onAdd={creating=true}) { detailId=it }
                2 -> Receipts(ledger,::change)
                3 -> SettingsScreen(ledger,::change,::message)
            }
        }
    }
}
@Composable private fun Overview(l: Ledger, onAdd:()->Unit, open: (String)->Unit) {
    val today=LocalDate.now()
    val upcoming=l.benefits.filter { b->l.plans.any { it.id==b.planId && !it.archived } }.sortedBy { Book.expiry(it,l.plans.single { p->p.id==it.planId }) }
    val soon=upcoming.count { ChronoUnit.DAYS.between(today,Book.expiry(it,l.plans.single { p->p.id==it.planId })) in 0..7 }
    Title("订阅簿", "${today.monthValue} 月 ${today.dayOfMonth} 日",onAdd)
    Card(colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("未来 30 天预计扣款",color=Color.White.copy(alpha=.8f),fontSize=15.sp)
            Text(Book.forecastCny(l,today,today.plusDays(30))?.let { "¥${it.setScale(2,RoundingMode.HALF_UP)}" } ?: "待补录人民币",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(vertical=16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${l.plans.count { !it.archived }} 个订阅",color=Color.White.copy(alpha=.8f))
                Text("自动续费",color=Color.White.copy(alpha=.8f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text("最近到期",fontSize=22.sp,fontWeight=FontWeight.Bold)
        if(soon>0) Surface(color=Color(0xFFFFE9D8),shape=RoundedCornerShape(12.dp)) { Text("本周 $soon 项",color=Amber,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp)) }
    }
    if(upcoming.isEmpty()) { Text("还没有需要记挂的到期日",fontWeight=FontWeight.SemiBold); Hint("添加订阅，开始记录。") }
    upcoming.forEach { benefit ->
        val plan=l.plans.single { it.id==benefit.planId }; val date=Book.expiry(benefit,plan); val days=ChronoUnit.DAYS.between(today,date)
        Card(onClick={open(plan.id)},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(benefit.name)
                Column(Modifier.weight(1f)) {
                    Text(benefit.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    Text("${date.monthValue} 月 ${date.dayOfMonth} 日" + if(benefit.name!=plan.name) " · ${plan.name}" else "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text(if(days<0) "已到期" else if(days==0L) "今天" else "$days",fontSize=if(days>0) 26.sp else 18.sp,fontWeight=FontWeight.Bold,color=if(days<=7) Amber else Leaf)
                    if(days>0) Text("天后",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable private fun Subscriptions(l: Ledger, onAdd:()->Unit, open: (String)->Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    Title("我的订阅",action=onAdd)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(!archived,{archived=false},label={Text("使用中")}); FilterChip(archived,{archived=true},label={Text("已归档")}) }
    val plans=l.plans.filter { it.archived==archived }
    if(plans.isEmpty()) Hint(if(archived) "没有归档的订阅" else "添加你的第一项会员。")
    plans.forEach { plan ->
        val expiry=l.benefits.filter { it.planId==plan.id }.minOfOrNull { Book.expiry(it,plan) }
        Card(onClick={open(plan.id)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(plan.name)
                Column(Modifier.weight(1f)) { Text(plan.name,fontSize=17.sp,fontWeight=FontWeight.SemiBold); Text(expiry?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日到期" } ?: "",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp)) }
                Column(horizontalAlignment=Alignment.End) { Text("${plan.currency} ${plan.amount}",fontSize=17.sp,fontWeight=FontWeight.Bold); Text(if(plan.autoRenew) "每 ${plan.interval} ${plan.cycle.label}" else "手动续费",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
@Composable private fun Receipts(l: Ledger, change:((Ledger)->Ledger)->Unit) {
    val month=LocalDate.now().withDayOfMonth(1)
    var completing by remember { mutableStateOf<Payment?>(null) }
    var cny by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    Title("付款账本")
    Card(colors=CardDefaults.cardColors(containerColor=Leaf),shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text("本月实付",color=Color.White.copy(alpha=.8f),fontSize=15.sp)
            Text(Book.paidCny(l,month,month.plusMonths(1))?.let { "¥${it.setScale(2,RoundingMode.HALF_UP)}" } ?: "待补录人民币金额",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(vertical=16.dp))
            Text(Book.paidCny(l)?.let { "累计 ¥${it.setScale(2,RoundingMode.HALF_UP)}" } ?: "历史外币付款待补录",color=Color.White.copy(alpha=.8f),fontSize=15.sp)
        }
    }
    Section("付款历史")
    if(l.payments.isEmpty()) Hint("还没有付款记录。")
    l.payments.sortedByDescending { it.date }.forEach { payment ->
        Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ServiceIcon(payment.planName,size=44.dp)
            Column(Modifier.weight(1f)) { Text(payment.planName,fontWeight=FontWeight.SemiBold,fontSize=17.sp); Text(payment.date,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp)) }
            Column(horizontalAlignment=Alignment.End) {
                val frozen=if(payment.currency=="CNY") payment.amount else payment.cnyAmount
                if(frozen!=null) Text("¥$frozen",fontWeight=FontWeight.Bold,fontSize=19.sp)
                else TextButton({completing=payment;cny="";error=""}) { Text("补录人民币") }
                if(payment.currency!="CNY") Text("${payment.currency} ${payment.amount}",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
    }
    if(completing!=null) AlertDialog(onDismissRequest={completing=null},title={Text("补录付款日实付金额")},text={Column {
        Text("${completing!!.date} · ${completing!!.currency} ${completing!!.amount}")
        Field("人民币实付金额",cny,{cny=it}); Hint("按当日账单填写，保存后固定。")
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try {
        val id=completing!!.id
        fun update(old:Ledger)=old.copy(payments=old.payments.map { if(it.id==id && it.cnyAmount==null) it.copy(cnyAmount=cny.trim()) else it })
        Book.validate(update(l));change(::update);completing=null
    }catch(e:Exception){error="请输入有效的人民币金额"}}){Text("保存金额")}},dismissButton={TextButton({completing=null}){Text("取消")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ServicePicker(close:()->Unit, select:(ServicePreset)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    AlertDialog(onDismissRequest=close,title={Text("选择会员")},text={Column {
        Field("搜索会员",query,{query=it})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (listOf("全部")+servicePresets.map { it.category }.distinct()).forEach { group ->
                FilterChip(category==group,{category=group},label={Text(group,fontSize=12.sp)})
            }
        }
        Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
            val matches=servicePresets.filter { p -> (category=="全部" || p.category==category) && (listOf(p.name,p.category)+p.aliases).any { it.contains(query.trim(),ignoreCase=true) } }
            if(matches.isEmpty()) Hint("没有找到，可返回自定义名称。")
            matches.groupBy { it.category }.forEach { (category,items) ->
                Section(category)
                items.forEach { preset -> Row(Modifier.fillMaxWidth().clickable { select(preset) }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ServiceIcon(preset.name,size=40.dp); Text(preset.name,fontSize=16.sp)
                } }
            }
        }
    }},confirmButton={TextButton(close){Text("自定义 / 返回")}})
}

@Composable private fun PlanEditor(l: Ledger, existing: Plan?, onClose: ()->Unit, save: (Plan,List<Benefit>,Payment?)->Unit) {
    val id=remember { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }; var amount by rememberSaveable { mutableStateOf(existing?.amount ?: "") }
    var currency by rememberSaveable { mutableStateOf(existing?.currency ?: "CNY") }; var cycle by remember { mutableStateOf(existing?.cycle ?: Cycle.MONTH) }
    var interval by rememberSaveable { mutableStateOf((existing?.interval ?: 1).toString()) }; var auto by rememberSaveable { mutableStateOf(existing?.autoRenew ?: true) }
    var anchor by rememberSaveable { mutableStateOf(existing?.billingAnchor ?: LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }; var paid by rememberSaveable { mutableStateOf(existing==null) }
    var error by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var cnyAmount by rememberSaveable { mutableStateOf("") }
    var benefits by remember { mutableStateOf(l.benefits.filter { it.planId==id }.ifEmpty { listOf(Benefit(planId=id,name="",anchor=LocalDate.now().plusMonths(1).toString())) }) }
    TextButton(onClose) { Text("返回") }; Title(if(existing==null) "记一笔订阅" else "编辑订阅")
    if(existing==null) {
        OutlinedButton({picker=true},Modifier.fillMaxWidth()) { Icon(Icons.Outlined.GridView,null); Spacer(Modifier.width(8.dp)); Text("选择常见会员") }
        if(picker) ServicePicker({picker=false}) { preset-> name=preset.name;currency=preset.currency;benefits=benefits.mapIndexed { i,b->if(i==0)b.copy(name=preset.name) else b };picker=false }
    }
    if(name.isNotBlank()) ServiceIcon(name,modifier=Modifier.padding(vertical=12.dp),size=56.dp)
    Field("订阅 / 套餐名称",name,{name=it}); Field("套餐价格",amount,{amount=it}); Field("币种，如 CNY / USD",currency,{currency=it.trim().uppercase()})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Cycle.entries.forEach { v -> FilterChip(cycle==v,{cycle=v},label={Text(v.label)}) } }
    Field("周期倍数",interval,{interval=it})
    Row(verticalAlignment=Alignment.CenterVertically) { Switch(auto,{auto=it}); Text("自动续费",Modifier.padding(start=12.dp)) }
    Field("原始扣款日期 YYYY-MM-DD",anchor,{anchor=it})
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
    if(existing==null) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(paid,{paid=it}); Text("同时记录首次付款") }
    if(existing==null && paid && currency!="CNY") { Field("人民币实付金额",cnyAmount,{cnyAmount=it}); Hint("填写付款当天的人民币金额，保存后固定。") }
    Field("备注",note,{note=it})
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    Button(onClick={ try {
        val p=Plan(id,name.trim(),amount,currency,cycle,interval.toInt(),auto,anchor,existing?.paidCycles ?: if(paid) 1 else 0,existing?.archived ?: false,note)
        val bs=benefits.map { b ->
            val named = if(b.name.isBlank() && benefits.size==1) b.copy(name=name.trim()) else b
            if(existing==null && named.renewals==0 && LocalDate.parse(named.anchor)==Book.advance(LocalDate.parse(anchor),cycle,p.interval.toLong())) named.copy(anchor=anchor,renewals=1) else named
        }
        if(existing==null && paid && currency!="CNY") require(cnyAmount.isNotBlank()) { "请填写付款当天的人民币实付金额" }
        val receipt=if(existing==null && paid) Payment(planId=id,planName=p.name,amount=amount,currency=currency,date=anchor,note="首次付款",benefitIds=bs.map { it.id },cnyAmount=if(currency=="CNY") null else cnyAmount.trim()) else null
        Book.validate(l.copy(plans=l.plans.filterNot { it.id==id }+p,benefits=l.benefits.filterNot { it.planId==id }+bs,payments=l.payments+listOfNotNull(receipt)))
        save(p,bs,receipt)
    } catch(e: Exception) { error="请检查名称、金额、周期和日期：${e.message ?: "格式错误"}" } },modifier=Modifier.fillMaxWidth().padding(top=16.dp)) { Text("保存订阅") }
}

@Composable private fun Detail(l: Ledger,p: Plan,onBack:()->Unit,onEdit:()->Unit,change:((Ledger)->Ledger)->Unit,onDeleted:()->Unit) {
    var renewing by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf(false) }
    var giftId by remember { mutableStateOf<String?>(null) }; var gift by remember { mutableStateOf("7") }
    TextButton(onBack) { Text("返回") }; ServiceIcon(p.name,size=64.dp); Title(p.name,if(p.autoRenew) "每 ${p.interval} ${p.cycle.label} · ${p.currency} ${p.amount}" else "一次性 / 手动续费 · ${p.currency} ${p.amount}")
    if(p.autoRenew) Hint("下次预计扣款 ${Book.nextCharge(p,LocalDate.now())}")
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
    var cnyAmount by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(l.benefits.filter { it.planId==p.id }.map { it.id }.toSet()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("记录实际付款")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Field("付款金额 ${p.currency}",amount,{amount=it}); Field("付款日期 YYYY-MM-DD",date,{date=it}); Field("付款备注",note,{note=it})
        if(p.currency!="CNY") { Field("人民币实付金额",cnyAmount,{cnyAmount=it}); Hint("以付款当天账单为准，保存后固定。") }
        Hint("选择续费权益 · 延长 ${p.interval} ${p.cycle.label}")
        l.benefits.filter { it.planId==p.id }.forEach { b->Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(b.id in selected,{selected=if(it)selected+b.id else selected-b.id}); Text(b.name) } }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try { val d=LocalDate.parse(date); Book.renew(l,p.id,amount,d,selected,note,cnyAmount.takeIf { p.currency!="CNY" }); change { Book.renew(it,p.id,amount,d,selected,note,cnyAmount.takeIf { p.currency!="CNY" }) };close() }catch(e:Exception){error=e.message ?: "请检查日期、金额与权益选择"}}){Text("确认付款")}},dismissButton={TextButton(close){Text("取消")}})
}

@Composable private fun SettingsScreen(l: Ledger,change:((Ledger)->Ledger)->Unit,message:(String)->Unit) {
    val c=LocalContext.current; val scope=rememberCoroutineScope(); val config=remember { CredentialsStore(c) }
    var url by remember { mutableStateOf(config.url) }; var user by remember { mutableStateOf(config.user) }; var password by remember { mutableStateOf("") }
    var reminder by remember(l.settings.reminderDays) { mutableStateOf(l.settings.reminderDays.joinToString(",")) }
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf(config.status) }; var success by remember { mutableStateOf(config.lastSuccess) }
    var preview by remember { mutableStateOf<Backup?>(null) }; var remote by remember { mutableStateOf<List<String>?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> message(if(allowed) "已允许通知" else "通知未授权，订阅和备份仍可使用"); Jobs.schedule(c) }
    fun operation(block:suspend ()->Unit) { scope.launch { busy=true; try { block() } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; message(if(e is DavException) e.message ?: "WebDAV 失败" else "操作失败，请检查文件、网络或应用密码") } finally { busy=false;status=config.status;success=config.lastSuccess } } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { val text=Book.encode(c.repository().read()); c.contentResolver.openOutputStream(uri,"wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入") };message("已导出，不包含 WebDAV 密码")
    } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) operation {
        preview=withContext(Dispatchers.IO) { val input=c.contentResolver.openInputStream(uri) ?: error("无法读取"); input.use { val bytes=it.readBytesLimited(); Book.decode(String(bytes,Charsets.UTF_8)) } }
    } }
    Title("设置")
    Section("到期提醒")
    Field("提前天数，用逗号分隔；0 表示当天",reminder,{reminder=it})
    Hint("清空可关闭提醒；系统省电可能延迟通知。")
    OutlinedButton({if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else message("请在系统应用设置中管理通知授权")}) { Text("允许到期通知") }
    Button({try {
        val days=if(reminder.isBlank()) emptyList() else reminder.replace('，',',').split(',').map { it.trim().toInt() }
        val next=l.copy(settings=l.settings.copy(reminderDays=days));Book.validate(next);change { it.copy(settings=it.settings.copy(reminderDays=days)) };message("提醒已保存")
    }catch(e:Exception){message("请检查提醒天数")}}) { Text("保存提醒") }
    Section("备份与恢复")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton({export.launch("订阅簿-${LocalDate.now()}.json")},enabled=!busy) { Text("导出 JSON") }; OutlinedButton({import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy){Text("从文件恢复") } }
    Section("坚果云 · WebDAV")
    Hint("使用专用应用密码。自动保留最近 10 份备份，手动备份长期保留。")
    Field("WebDAV 地址",url,{url=it});Field("坚果云账号",user,{user=it});Field(if(config.configured) "应用密码（留空保留）" else "专用应用密码",password,{password=it},secret=true)
    Button({try { config.save(url.trim(),user.trim(),password);password="";Jobs.backup(c);message("已保存，将在联网后自动备份") }catch(e:Exception){message(e.message ?: "配置无效")}},enabled=!busy) { Text("保存并启用自动备份") }
    Text("最后成功：${displayTime(success)}",fontSize=13.sp,modifier=Modifier.padding(top=8.dp)); Hint(status)
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({operation { withContext(Dispatchers.IO) { try { config.client().upload(c.repository().read(),false);config.result() } catch(e:Exception){config.result("手动备份失败，请检查网络与应用密码");throw e} };message("手动备份成功，已下载核验") }},enabled=!busy) { Text("立即备份") }
        OutlinedButton({operation { remote=withContext(Dispatchers.IO) { config.client().list() } }},enabled=!busy) { Text("选择云端备份") }
    }
    TextButton({disconnect=true},enabled=!busy) { Text("断开 WebDAV") }
    Section("应用更新")
    Text("当前版本 ${BuildConfig.VERSION_NAME}",fontSize=15.sp)
    OutlinedButton(onClick={
        scope.launch {
            checkingUpdate=true;updateError=null
            try { update=withContext(Dispatchers.IO) { Updates.check(BuildConfig.VERSION_NAME) } }
            catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; updateError=if(e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "无法连接 GitHub，请检查网络后重试" else e.message ?: "检查失败，请稍后重试" }
            finally { checkingUpdate=false }
        }
    },enabled=!checkingUpdate) { if(checkingUpdate) { CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp)) }; Text(if(checkingUpdate) "正在检查…" else "检查更新") }
    if(updateError!=null) Text(updateError!!,color=MaterialTheme.colorScheme.error)
    if(update!=null) { val result=update!!
        AlertDialog(onDismissRequest={update=null},title={Text(if(result.newer) "发现新版本 ${result.version}" else "当前已是最新版本")},text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
            Text("当前 ${BuildConfig.VERSION_NAME} · 最新发布 ${result.version}")
            if(result.newer) { Spacer(Modifier.height(12.dp));Text(result.notes.ifBlank { "前往发布页查看更新内容。" });Hint("下载 APK 后按系统提示覆盖安装，保留本机数据。") }
        }},confirmButton={TextButton({
            if(result.newer) runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(Updates.RELEASE_PAGE))) }.onFailure { message("无法打开浏览器") }
            update=null
        }) { Text(if(result.newer) "前往下载" else "知道了") }},dismissButton={if(result.newer) TextButton({update=null}) { Text("稍后") }})
    }
    TextButton({ operation { licenseText=withContext(Dispatchers.IO) { c.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name -> name+"\n"+c.assets.open("licenses/$name").bufferedReader().use { it.readText() } } } } }) { Text("开源许可证") }
    if(licenseText!=null) AlertDialog(onDismissRequest={licenseText=null},title={Text("开源许可证")},text={Text(licenseText!!,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton({licenseText=null}){Text("关闭")}})
    Hint("订阅簿 1.1 · MIT")
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
