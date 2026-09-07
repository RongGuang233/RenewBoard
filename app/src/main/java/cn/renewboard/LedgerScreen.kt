package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

private fun cash(value: BigDecimal) = "¥${value.setScale(2,RoundingMode.HALF_UP).toPlainString()}"
private fun smallAmount(value: BigDecimal): String = when {
    value >= BigDecimal("10000") -> "${value.divide(BigDecimal("10000"),1,RoundingMode.HALF_UP)}万"
    else -> value.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
private fun within(p: Payment, from: LocalDate?, until: LocalDate?) =
    (from==null || LocalDate.parse(p.date)>=from) && (until==null || LocalDate.parse(p.date)<until)
private data class ReceiptRange(val title:String,val from:LocalDate?,val until:LocalDate?)

@Composable internal fun LedgerScreen(l:Ledger,change:((Ledger)->Ledger)->Unit,onSubpageChange:(Boolean)->Unit={}) {
    val today=LocalDate.now()
    val month=YearMonth.from(today)
    val overviewScroll=rememberScrollState()
    var selectedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableStateOf(TrendRange.SIX) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var expandedRank by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ReceiptRange?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val expenses=Prepaid.expenses(l)
    val monthly=expenses.filter { within(it,month.atDay(1),minOf(month.plusMonths(1).atDay(1),today.plusDays(1))) }
    val buckets=LedgerStats.buckets(expenses,range,today,year)
    val rangePayments=if(buckets.isEmpty()) emptyList() else expenses.filter { within(it,buckets.first().from,buckets.last().until) }
    val rangeSum=LedgerStats.summary(rangePayments)
    val selectedBucket=buckets.find { it.from.toString()==selectedFrom }
    val rankedPayments=selectedBucket?.let { bucket -> rangePayments.filter { within(it,bucket.from,bucket.until) } } ?: rangePayments
    val rankSum=LedgerStats.summary(rankedPayments)
    val rankPeriod=selectedBucket?.let { bucket ->
        when {
            range==TrendRange.MONTH -> "${bucket.from.year}年${bucket.from.monthValue}月${bucket.from.dayOfMonth}日"
            '/' in bucket.label -> "${bucket.from.year}年${bucket.from.monthValue}月"
            else -> "${bucket.from.year}年"
        }
    } ?: when(range) {
        TrendRange.MONTH -> "近30天"
        TrendRange.THREE -> "近3个月"
        TrendRange.SIX -> "近6个月"
        TrendRange.TWELVE -> "近12个月"
        TrendRange.YEAR -> "$year 年"
        TrendRange.FIVE -> "近5年"
        TrendRange.ALL -> "全部"
    }
    val firstMonth=expenses.minOfOrNull { YearMonth.from(LocalDate.parse(it.date)) } ?: YearMonth.from(today)
    val subpageOpen=detail!=null || payment!=null
    LaunchedEffect(subpageOpen) {onSubpageChange(subpageOpen)}
    DisposableEffect(Unit) {onDispose {onSubpageChange(false)}}
    BackHandler(subpageOpen) {if(payment!=null) payment=null else detail=null}
    payment?.let { selected ->
        l.payments.find {it.id==selected.id}?.let { PaymentDetailScreen(l,it,{payment=null},change,"返回概览") }
        return
    }
    detail?.let { target -> ReceiptList(l,target,{detail=null},change);return }
    Column(Modifier.fillMaxSize().verticalScroll(overviewScroll).padding(horizontal=20.dp).padding(bottom=24.dp)) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("支出概览",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        TextButton({detail=ReceiptRange("全部付款",null,null)}) { Text("全部明细");Icon(Icons.Outlined.ChevronRight,null) }
    }
    Heading("支出趋势", "截至 ${today.monthValue}月${today.dayOfMonth}日")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        TrendRange.entries.forEach { item -> FilterChip(selected=range==item,onClick={range=item;selectedFrom=null;expandedRank=false},label={Text(item.label)},modifier=Modifier.semantics { contentDescription="趋势范围 ${item.label}" }) }
    }
    if(range==TrendRange.YEAR) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        IconButton({year--;selectedFrom=null},enabled=year>minOf(firstMonth.year,today.year)) {Icon(Icons.Outlined.ChevronLeft,"上一年")}
        Text("$year 年",fontWeight=FontWeight.SemiBold)
        IconButton({year++;selectedFrom=null},enabled=year<today.year) {Icon(Icons.Outlined.ChevronRight,"下一年")}
    }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("范围支出 ${cash(rangeSum.known)}",fontWeight=FontWeight.SemiBold)
                Text("${rangeSum.count} 笔",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
            }
            if(rangeSum.missing>0) Text("${rangeSum.missing} 笔金额待补录",color=MaterialTheme.colorScheme.tertiary,fontSize=13.sp)
            if(buckets.isEmpty()) Text("记录第一笔付款后，这里会显示趋势。",modifier=Modifier.padding(vertical=30.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else TrendBars(buckets,selectedBucket?.from) { bucket ->
                selectedFrom=if(selectedFrom==bucket.from.toString()) null else bucket.from.toString()
                expandedRank=false
            }
        }
    }
    Heading("钱花在哪里", "$rankPeriod · 按应用")
    val ranking=rankedPayments.groupBy { it.planName }.map { (name,ps)->name to LedgerStats.summary(ps) }.sortedByDescending { it.second.known }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if(ranking.isEmpty()) Text("这个时段还没有付款",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
            val largest=ranking.maxOfOrNull { it.second.known }?.takeIf { it.signum()>0 } ?: BigDecimal.ONE
            ranking.take(if(expandedRank) ranking.size else 5).forEachIndexed { i,(name,spending) ->
                val fraction=spending.known.divide(largest,6,RoundingMode.HALF_UP).toFloat()
                val share=if(rankSum.known.signum()>0) spending.known.multiply(BigDecimal(100)).divide(rankSum.known,1,RoundingMode.HALF_UP).toPlainString() else "0.0"
                Row(Modifier.fillMaxWidth().padding(vertical=7.dp).semantics {
                    contentDescription="$name，支出${cash(spending.known)}，占比$share%，待补录${spending.missing}笔"
                },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ServiceIcon(name,size=24.dp,modifier=Modifier.padding(top=16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name+if(spending.missing>0) " · 待补录 ${spending.missing} 笔" else " · $share%",
                            fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp).padding(top=5.dp)) {
                            // Every row uses the same zero and scale; label space is outside the plotted width.
                            val plotWidth=(maxWidth-82.dp).coerceAtLeast(0.dp)
                            val barWidth=plotWidth*fraction
                            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            if(fraction>0f) Box(Modifier.width(barWidth).fillMaxHeight().clip(RoundedCornerShape(topEnd=4.dp,bottomEnd=4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha=(1f-i*.07f).coerceAtLeast(.55f))))
                            Text(cash(spending.known),fontSize=13.sp,fontWeight=FontWeight.SemiBold,
                                modifier=Modifier.offset(x=barWidth+8.dp).align(Alignment.CenterStart),maxLines=1)
                        }
                    }
                }
            }
            if(ranking.size>5) TextButton({expandedRank=!expandedRank},Modifier.align(Alignment.CenterHorizontally)) {Text(if(expandedRank) "收起" else "展开全部 ${ranking.size} 项")}
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=20.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("最近付款",fontSize=20.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        TextButton({detail=ReceiptRange("${month.monthValue} 月付款",month.atDay(1),month.plusMonths(1).atDay(1))}) {Text("查看本月明细")}
    }
    monthly.sortedByDescending { it.date }.take(3).groupBy {it.date}.forEach { (date,rows) ->
        ReceiptDate(date)
        rows.forEach {p -> PaymentRow(p,open={payment=p})}
    }
    if(monthly.isEmpty()) Text("本月暂无付款记录",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=14.dp))
    }
}

@Composable private fun Heading(title:String,subtitle:String) {
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TrendBars(buckets:List<CashBucket>,selectedFrom:LocalDate?,open:(CashBucket)->Unit) {
    val maximum=buckets.maxOf { it.summary.known }.max(BigDecimal.ONE)
    val monthLabels=buckets.all { it.from.dayOfMonth==1 } && buckets.any { '/' in it.label }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth=(maxWidth/buckets.size.coerceAtMost(12)).coerceAtLeast(30.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=16.dp)) {
            buckets.forEach { bucket ->
                val fraction=bucket.summary.known.divide(maximum,6,RoundingMode.HALF_UP).toFloat()
                val active=bucket.from==selectedFrom
                Column(Modifier.width(cellWidth).clickable { open(bucket) }.semantics {
                    selected=active
                    contentDescription="${bucket.label}，支出${cash(bucket.summary.known)}，${bucket.summary.count}笔，待补录${bucket.summary.missing}笔"
                },horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.height(135.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            if(buckets.size<=12) Text(if(bucket.summary.missing>0) "待补" else smallAmount(bucket.summary.known),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width((cellWidth*.48f).coerceAtMost(28.dp)).height(if(fraction>0) (100*fraction).dp.coerceAtLeast(2.dp) else 2.dp)
                                .clip(RoundedCornerShape(topStart=5.dp,topEnd=5.dp)).background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer))
                        }
                    }
                    Text(if(monthLabels) "${bucket.from.monthValue}月" else bucket.label,fontSize=10.sp,color=if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,modifier=Modifier.padding(top=8.dp,bottom=4.dp))
                }
            }
        }
    }
}
@Composable private fun PaymentRow(p:Payment,open:()->Unit,selected:Boolean?=null,toggle:()->Unit={}) {
    Row(Modifier.fillMaxWidth().clickable(onClick=if(selected!=null) toggle else open).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(selected!=null) Checkbox(selected,{toggle()})
        ServiceIcon(p.planName,size=40.dp)
        Column(Modifier.weight(1f)) {
            Text(p.planName,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(Prepaid.isTopUp(p)) Text("充值 · 不计支出",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        val known=if(p.currency=="CNY") p.amount else p.cnyAmount
        if(known==null) TextButton(open) {Text("补录人民币",fontSize=13.sp)}
        else Column(horizontalAlignment=Alignment.End) {
            Text(cash(BigDecimal(known)),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            if(p.currency!="CNY") Text("${p.currency} ${p.amount}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReceiptList(l:Ledger,target:ReceiptRange,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val visible=l.payments.filter { p -> within(p,target.from,target.until) && p.planName.contains(query.trim(),ignoreCase=true) && when(kind) {
        "订阅付款" -> !Prepaid.isTopUp(p)
        "话费扣费" -> p.note in setOf("话费扣费","话费额外扣费")
        "话费充值" -> Prepaid.isTopUp(p)
        "已删订阅" -> l.plans.none { it.id==p.planId }
        else -> true
    } }.sortedByDescending { it.date }
    val effective=selected.intersect(visible.map { it.id }.toSet())
    val sum=LedgerStats.summary(visible.filterNot(Prepaid::isTopUp))
    val topups=LedgerStats.summary(visible.filter(Prepaid::isTopUp))
    BackHandler {if(payment!=null) payment=null else close()}
    payment?.let { selectedPayment ->
        l.payments.find {it.id==selectedPayment.id}?.let { PaymentDetailScreen(l,it,{payment=null},change,"返回明细") }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(target.title,fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack("返回概览",close)},actions={
            TextButton({managing=!managing;selected=emptySet()}) {Text(if(managing) "完成" else "管理")}
        })},bottomBar={if(managing) Surface(shadowElevation=4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton({selected=if(effective.size==visible.size) emptySet() else visible.map { it.id }.toSet()}) {Text(if(effective.size==visible.size && visible.isNotEmpty()) "取消全选" else "全选")}
                Text("已选 ${effective.size} 笔",modifier=Modifier.weight(1f),fontSize=14.sp)
                Button({deleting=effective},enabled=effective.isNotEmpty(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) {Text("删除所选")}
            }
        }}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
                OutlinedTextField(query,{query=it;selected=emptySet()},label={Text("搜索应用")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("全部","订阅付款","话费扣费","话费充值","已删订阅").forEach { option -> FilterChip(kind==option,{kind=option;selected=emptySet()},label={Text(option)}) }
                }
                Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${visible.size} 笔 · 支出 ${cash(sum.known)}"+if(sum.missing>0) " · ${sum.missing} 笔待补录" else "",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(topups.count>0) Text("充值 ${cash(topups.known)} · 不计支出"+if(topups.missing>0) " · ${topups.missing} 笔待补录" else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    if(visible.isEmpty()) item {Text("没有符合条件的付款",modifier=Modifier.padding(vertical=32.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    visible.groupBy { it.date }.forEach { (date,payments) ->
                        item(key="date-$date") {ReceiptDate(date)}
                        items(payments,key={it.id}) { p -> PaymentRow(p,{payment=p},if(managing) p.id in effective else null) { selected=if(p.id in selected) selected-p.id else selected+p.id } }
                    }
                }
            }
        }
        deleting?.let { ids -> DeletePaymentsDialog(l,ids,{deleting=null}) { change { Book.deletePayments(it,ids) };deleting=null;selected=emptySet() } }
    }
}
@Composable private fun DeletePaymentsDialog(l:Ledger,ids:Set<String>,close:()->Unit,confirm:()->Unit) {
    val records=l.payments.filter { it.id in ids }
    val sum=LedgerStats.summary(records)
    AlertDialog(onDismissRequest=close,title={Text("删除 ${records.size} 笔付款？")},text={Column {
        Text("合计 ${cash(sum.known)}"+if(sum.missing>0) "，另有 ${sum.missing} 笔人民币金额待补录" else "")
        Text("删除后统计会更新；已续费权益和话费余额不变。此操作不能撤销。",modifier=Modifier.padding(top=12.dp))
    }},confirmButton={TextButton(confirm) {Text("确认删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(close) {Text("取消")}})
}
@Composable private fun ReceiptDate(date:String) {
    Text(date,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=18.dp,bottom=4.dp))
}
@Composable private fun ReceiptBack(description:String,close:()->Unit) {
    Box(Modifier.padding(start=8.dp)) {PageBack(description,close)}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PaymentDetailScreen(l:Ledger,p:Payment,close:()->Unit,change:((Ledger)->Ledger)->Unit,backDescription:String="返回") {
    var deleting by remember { mutableStateOf(false) }
    var editing by rememberSaveable(p.id) { mutableStateOf(false) }
    var cny by rememberSaveable(p.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val missing=p.currency!="CNY" && p.cnyAmount==null
    BackHandler {if(editing) editing=false else close()}
    if(editing) {PaymentEditor(l,p,{editing=false},change);return}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(if(missing) "补录实付金额" else "付款详情",fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack(backDescription,close)})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ServiceIcon(p.planName,size=48.dp)
                    Text(p.planName,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(missing) "${p.currency} ${p.amount}" else cash(BigDecimal(if(p.currency=="CNY") p.amount else p.cnyAmount!!)),fontSize=36.sp,fontWeight=FontWeight.Bold)
                    if(!missing && p.currency!="CNY") Text("原币 ${p.currency} ${p.amount}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.date,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(Prepaid.isTopUp(p)) Text("充值 · 不计支出",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else if(p.note.isNotBlank() && p.note!="订阅付款") Text(p.note,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(p.note=="话费扣费") Text("按设置的月费记录，非运营商账单。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(l.plans.none { it.id==p.planId }) Text("关联订阅已删除",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(missing) {
                MoneyField("人民币实付金额",cny,{cny=it})
                Text("按付款当天账单填写，保存后固定。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Button({
                    try {
                        val settled=cny.trim()
                        Book.editPayment(l,p.id,p.amount,LocalDate.parse(p.date),p.note,settled)
                        change {Book.editPayment(it,p.id,p.amount,LocalDate.parse(p.date),p.note,settled)};close()
                    } catch(e:Exception) {error="请输入有效的人民币金额"}
                },modifier=Modifier.fillMaxWidth()) {Text("保存金额")}
            }
            OutlinedButton({editing=true},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(6.dp));Text("更正付款")}
            TextButton({deleting=true},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(6.dp));Text("删除这笔付款",color=MaterialTheme.colorScheme.error)}
        }
    }
    if(deleting) DeletePaymentsDialog(l,setOf(p.id),{deleting=false}) {change {Book.deletePayments(it,setOf(p.id))};deleting=false;close()}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PaymentEditor(l:Ledger,p:Payment,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    var amount by rememberSaveable(p.id) {mutableStateOf(p.amount)}
    var date by rememberSaveable(p.id) {mutableStateOf(p.date)}
    var note by rememberSaveable(p.id) {mutableStateOf(p.note)}
    var cny by rememberSaveable(p.id) {mutableStateOf(p.cnyAmount.orEmpty())}
    var error by remember {mutableStateOf<String?>(null)}
    val fixedType=Prepaid.isTopUp(p) || p.note in setOf("话费扣费","话费额外扣费")
    BackHandler(onBack=close)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text("更正付款",fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack("返回付款详情",close)})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(p.planName,size=40.dp);Text(p.planName,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            }
            Text("只更正账目，不改变权益到期日和话费余额。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyField("付款金额 ${p.currency}",amount,{amount=it})
            Field("付款日期",date,{date=it},dateField=true)
            if(p.currency!="CNY") {
                MoneyField("人民币实付金额",cny,{cny=it})
                Text("填写当日实际结算金额，保存后固定。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(fixedType) Text("记录类型：${p.note}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Field("付款备注",note,{note=it})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button({try {
                val day=LocalDate.parse(date)
                val settlement=cny.takeIf {p.currency!="CNY"}
                Book.editPayment(l,p.id,amount,day,note,settlement)
                change {Book.editPayment(it,p.id,amount,day,note,settlement)};close()
            } catch(e:Exception) {error=e.message ?: "请检查金额和日期"}},modifier=Modifier.fillMaxWidth()) {Text("保存更正")}
        }
    }
}
@Composable internal fun DeletePlanDialog(l:Ledger,p:Plan,close:()->Unit,confirm:(Boolean)->Unit) {
    var includePayments by remember { mutableStateOf(false) }
    val receipts=l.payments.filter { it.planId==p.id }
    AlertDialog(onDismissRequest=close,title={Text("删除 ${p.name}？")},text={Column {
        Text("选择是否保留历史付款。删除不能撤销。")
        Row(Modifier.fillMaxWidth().clickable {includePayments=false},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(!includePayments,{includePayments=false});Text("仅删除订阅，保留付款")
        }
        Row(Modifier.fillMaxWidth().clickable {includePayments=true},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(includePayments,{includePayments=true});Text("同时删除付款记录（${receipts.size} 笔）")
        }
        if(includePayments) Text("将移除 ${cash(LedgerStats.summary(receipts.filterNot(Prepaid::isTopUp)).known)} 已记录支出，统计随之更新。",fontSize=13.sp)
    }},confirmButton={TextButton({confirm(includePayments)}) {Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(close) {Text("取消")}})
}
