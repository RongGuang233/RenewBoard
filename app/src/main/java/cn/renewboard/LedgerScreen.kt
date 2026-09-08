package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
private data class ReceiptRange(val title:String,val from:LocalDate?,val until:LocalDate?,val planName:String?=null,val planId:String?=null,val refundOf:String?=null)

@Composable internal fun LedgerScreen(l:Ledger,change:((Ledger)->Ledger)->Unit,onSubpageChange:(Boolean)->Unit={}) {
    val today=LocalDate.now()
    val month=YearMonth.from(today)
    val overviewScroll=rememberScrollState()
    var selectedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableStateOf(TrendRange.SIX) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var expandedRank by rememberSaveable { mutableStateOf(false) }
    var expandedCash by rememberSaveable { mutableStateOf(false) }
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
    val selectedDetail=selectedBucket?.let { ReceiptRange("$rankPeriod 明细",it.from,it.until) }
    val firstMonth=expenses.minOfOrNull { YearMonth.from(LocalDate.parse(it.date)) } ?: YearMonth.from(today)
    val subpageOpen=detail!=null || payment!=null
    LaunchedEffect(subpageOpen) {onSubpageChange(subpageOpen)}
    DisposableEffect(Unit) {onDispose {onSubpageChange(false)}}
    BackHandler(subpageOpen) {if(payment!=null) payment=null else detail=null}
    payment?.let { selected ->
        val current=l.payments.find {it.id==selected.id}
        if(current!=null) PaymentDetailScreen(l,current,{payment=null},change,"返回概览")
        else LaunchedEffect(selected.id) {payment=null}
        return
    }
    detail?.let { target -> ReceiptList(l,target,{detail=null},change);return }
    Column(Modifier.fillMaxSize().verticalScroll(overviewScroll).padding(horizontal=20.dp).padding(bottom=24.dp)) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("支出概览",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        TextButton({detail=selectedDetail ?: ReceiptRange("全部付款",null,null)}) { Text(if(selectedDetail!=null) "所选时段明细" else "全部明细");Icon(Icons.Outlined.ChevronRight,null) }
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
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable {expandedCash=!expandedCash}.semantics {
                contentDescription=if(expandedCash) "收起支出构成" else "展开支出构成"
            },verticalAlignment=Alignment.CenterVertically) {
                Text("净支出 ${cash(rangeSum.known)}",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                Text("${rangeSum.count} 笔",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
                Icon(if(expandedCash) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,modifier=Modifier.size(20.dp))
            }
            if(expandedCash) {
                val breakdown=LedgerStats.breakdown(rangePayments)
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    CashPart("付款",breakdown.payments,Modifier.weight(1f))
                    CashPart("退款",breakdown.refunds,Modifier.weight(1f))
                }
            }
            if(rangeSum.missing>0) Text("${rangeSum.missing} 笔金额待补录",color=MaterialTheme.colorScheme.tertiary,fontSize=13.sp)
            if(buckets.isEmpty()) Text("记录第一笔付款后，这里会显示趋势。",modifier=Modifier.padding(vertical=30.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else TrendBars(buckets,selectedBucket?.from) { bucket ->
                selectedFrom=if(selectedFrom==bucket.from.toString()) null else bucket.from.toString()
                expandedRank=false
            }
            if(buckets.any { it.summary.known.signum()<0 }) Text("零线上方为净支出，下方为净退款",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Heading("钱花在哪里", "$rankPeriod · 按应用")
    val ranking=rankedPayments.groupBy { it.planName }.map { (name,ps)->name to LedgerStats.summary(ps) }.sortedByDescending { it.second.known }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if(ranking.isEmpty()) Text("这个时段还没有付款",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
            val largest=ranking.maxOfOrNull { it.second.known.abs() }?.max(BigDecimal.ONE) ?: BigDecimal.ONE
            val hasNegative=ranking.any { it.second.known.signum()<0 }
            val showShares=!hasNegative && rankSum.missing==0 && rankSum.known.signum()>0
            ranking.take(if(expandedRank) ranking.size else 5).forEachIndexed { i,(name,spending) ->
                val fraction=spending.known.abs().divide(largest,6,RoundingMode.HALF_UP).toFloat()
                val share=if(showShares) spending.known.multiply(BigDecimal(100)).divide(rankSum.known,1,RoundingMode.HALF_UP).toPlainString() else null
                Row(Modifier.fillMaxWidth().clickable {
                    detail=ReceiptRange("$name · $rankPeriod",selectedBucket?.from ?: buckets.firstOrNull()?.from,
                        selectedBucket?.until ?: buckets.lastOrNull()?.until,name)
                }.padding(vertical=7.dp).semantics {
                    contentDescription="$name，支出${cash(spending.known)}"+(share?.let { "，占比$it%" } ?: "，净支出")+"，待补录${spending.missing}笔"
                },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ServiceIcon(name,size=24.dp)
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(name+when { spending.missing>0 -> " · 待补录 ${spending.missing} 笔"; share!=null -> " · $share%"; spending.known.signum()<0 -> " · 净退款"; else -> "" },
                                fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                            Text(cash(spending.known),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp).padding(top=5.dp)) {
                            val zero=if(hasNegative) maxWidth/2 else 0.dp
                            val barWidth=(if(hasNegative) maxWidth/2 else maxWidth)*fraction
                            Box(Modifier.offset(x=zero).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            if(fraction>0f) Box(Modifier.offset(x=if(spending.known.signum()<0) zero-barWidth else zero).width(barWidth).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                                .background(if(spending.known.signum()<0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha=(1f-i*.07f).coerceAtLeast(.55f))))
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight,null,modifier=Modifier.size(18.dp))
                }
            }
            if(ranking.size>5) TextButton({expandedRank=!expandedRank},Modifier.align(Alignment.CenterHorizontally)) {Text(if(expandedRank) "收起" else "展开全部 ${ranking.size} 项")}
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=20.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("最近付款",fontSize=20.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        TextButton({detail=selectedDetail ?: ReceiptRange("${month.monthValue} 月付款",month.atDay(1),month.plusMonths(1).atDay(1))}) {Text(if(selectedDetail!=null) "查看所选时段" else "查看本月明细")}
    }
    monthly.sortedByDescending { it.date }.take(3).groupBy {it.date}.forEach { (date,rows) ->
        ReceiptDate(date)
        rows.forEach {p -> PaymentRow(p,open={payment=p})}
    }
    if(monthly.isEmpty()) Text("本月暂无付款记录",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=14.dp))
    }
}

@Composable private fun CashPart(label:String,summary:CashSummary,modifier:Modifier=Modifier) {
    Column(modifier) {
        Text("$label ${cash(summary.known)}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(summary.missing>0) Text("${summary.missing} 笔待补录",fontSize=12.sp,color=MaterialTheme.colorScheme.tertiary)
    }
}

@Composable private fun Heading(title:String,subtitle:String) {
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TrendBars(buckets:List<CashBucket>,selectedFrom:LocalDate?,open:(CashBucket)->Unit) {
    val maximum=buckets.maxOf { it.summary.known.abs() }.max(BigDecimal.ONE)
    val hasNegative=buckets.any { it.summary.known.signum()<0 }
    val monthLabels=buckets.all { it.from.dayOfMonth==1 } && buckets.any { '/' in it.label }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth=(maxWidth/buckets.size.coerceAtMost(12)).coerceAtLeast(30.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=16.dp)) {
            buckets.forEach { bucket ->
                val fraction=bucket.summary.known.abs().divide(maximum,6,RoundingMode.HALF_UP).toFloat()
                val negative=bucket.summary.known.signum()<0
                val active=bucket.from==selectedFrom
                Column(Modifier.width(cellWidth).clickable { open(bucket) }.semantics {
                    selected=active
                    contentDescription="${bucket.label}，支出${cash(bucket.summary.known)}，${bucket.summary.count}笔，待补录${bucket.summary.missing}笔"+if(negative) "，净退款，零线下方" else ""
                },horizontalAlignment=Alignment.CenterHorizontally) {
                    if(buckets.size<=12) Text(if(bucket.summary.missing>0) "待补" else smallAmount(bucket.summary.known),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    Box(Modifier.height(120.dp).fillMaxWidth()) {
                        val baseline=if(hasNegative) 60.dp else 112.dp
                        val barHeight=((if(hasNegative) 52 else 100)*fraction).dp
                        Box(Modifier.offset(y=baseline).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        if(fraction>0f) Box(Modifier.align(Alignment.TopCenter).offset(y=if(negative) baseline else baseline-barHeight)
                            .width((cellWidth*.48f).coerceAtMost(28.dp)).height(barHeight.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp))
                            .background(if(negative) MaterialTheme.colorScheme.tertiary else if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer))
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
            if(p.refundOf!=null) Text("退款",fontSize=12.sp,color=MaterialTheme.colorScheme.tertiary)
            else if(p.note=="话费扣费") Text("按月费记录",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(Prepaid.isTopUp(p)) Text("充值 · 不计支出",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        val known=if(p.currency=="CNY") p.amount else p.cnyAmount
        if(known==null) TextButton(open) {Text("补录人民币",fontSize=13.sp)}
        else Column(horizontalAlignment=Alignment.End) {
            Text(cash(p.signedCny()!!),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            if(p.currency!="CNY") Text("${p.currency} ${p.amount}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReceiptList(l:Ledger,target:ReceiptRange,close:()->Unit,change:((Ledger)->Ledger)->Unit,backDescription:String="返回概览",openPayment:((Payment)->Unit)?=null) {
    val listState=rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val visible=l.payments.filter { p -> within(p,target.from,target.until) && (target.planName==null || p.planName==target.planName) && (target.planId==null || p.planId==target.planId) && (target.refundOf==null || p.refundOf==target.refundOf) && p.planName.contains(query.trim(),ignoreCase=true) && when(kind) {
        "订阅付款" -> !Prepaid.isTopUp(p)
        "退款" -> p.refundOf!=null
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
        val current=l.payments.find {it.id==selectedPayment.id}
        if(current!=null) PaymentDetailScreen(l,current,{payment=null},change,"返回明细")
        else LaunchedEffect(selectedPayment.id) {payment=null}
        return
    }
    Column(Modifier.fillMaxSize()) {
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(target.title,fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack(backDescription,close)},actions={
            TextButton({managing=!managing;selected=emptySet()}) {Text(if(managing) "完成" else "管理")}
        })},bottomBar={if(managing) Surface(shadowElevation=4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton({selected=if(effective.size==visible.size) emptySet() else visible.map { it.id }.toSet()}) {Text(if(effective.size==visible.size && visible.isNotEmpty()) "取消全选" else "全选")}
                Text("已选 ${effective.size} 笔",modifier=Modifier.weight(1f),fontSize=14.sp)
                Button({deleting=effective},enabled=effective.isNotEmpty(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) {Text("删除所选")}
            }
        }}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
                if(target.refundOf==null) {
                    if(target.planId==null) OutlinedTextField(query,{query=it;selected=emptySet()},label={Text("搜索应用")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        val options=if(target.planId!=null) listOf("全部","话费扣费","话费充值","退款") else listOf("全部","订阅付款","退款","话费扣费","话费充值","已删订阅")
                        options.forEach { option -> FilterChip(kind==option,{kind=option;selected=emptySet()},label={Text(option)}) }
                    }
                }
                Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${visible.size} 笔 · ${if(target.refundOf!=null) "退款 ${cash(sum.known.abs())}" else "净支出 ${cash(sum.known)}"}"+if(sum.missing>0) " · ${sum.missing} 笔待补录" else "",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(topups.count>0) Text("充值 ${cash(topups.known)} · 不计支出"+if(topups.missing>0) " · ${topups.missing} 笔待补录" else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize(),state=listState) {
                    if(visible.isEmpty()) item {Text("没有符合条件的付款",modifier=Modifier.padding(vertical=32.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    visible.groupBy { it.date }.forEach { (date,payments) ->
                        item(key="date-$date") {ReceiptDate(date)}
                        items(payments,key={it.id}) { p -> PaymentRow(p,{if(openPayment!=null) openPayment(p) else payment=p},if(managing) p.id in effective else null) { selected=if(p.id in selected) selected-p.id else selected+p.id } }
                    }
                }
            }
        }
        deleting?.let { ids -> DeletePaymentsDialog(l,ids,{deleting=null}) { change { Book.deletePayments(it,ids) };deleting=null;selected=emptySet() } }
    }
}
@Composable internal fun AccountHistoryScreen(l:Ledger,planId:String,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    val name=l.plans.find {it.id==planId}?.name ?: l.payments.find {it.planId==planId}?.planName ?: "话费"
    ReceiptList(l,ReceiptRange("$name · 全部记录",null,null,planId=planId),close,change,"返回话费详情")
}

@Composable private fun DeletePaymentsDialog(l:Ledger,ids:Set<String>,close:()->Unit,confirm:()->Unit) {
    val allIds=Book.paymentDeletionIds(l,ids)
    val records=l.payments.filter { it.id in allIds }
    val related=records.count { it.id !in ids }
    val sum=LedgerStats.summary(records)
    AlertDialog(onDismissRequest=close,title={Text("删除 ${records.size} 笔付款？")},text={Column {
        Text("合计 ${cash(sum.known)}"+if(sum.missing>0) "，另有 ${sum.missing} 笔人民币金额待补录" else "")
        if(related>0) Text("包含关联退款 $related 笔，将一并删除。",modifier=Modifier.padding(top=8.dp))
        Text("删除后统计会更新；已续费权益和话费余额不变。此操作不能撤销。",modifier=Modifier.padding(top=12.dp))
    }},confirmButton={TextButton(confirm) {Text("确认删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(close) {Text("取消")}})
}
@Composable private fun ReceiptDate(date:String) {
    Text(date,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=18.dp,bottom=4.dp))
}
@Composable private fun ReceiptBack(description:String,close:()->Unit) {
    Box(Modifier.padding(start=8.dp)) {PageBack(description,close)}
}
@Composable internal fun PaymentDetailScreen(l:Ledger,p:Payment,close:()->Unit,change:((Ledger)->Ledger)->Unit,backDescription:String="返回") {
    var path by rememberSaveable(p.id) { mutableStateOf(listOf("payment:${p.id}")) }
    val states=rememberSaveableStateHolder()
    val back:()->Unit={if(path.size>1) path=path.dropLast(1) else close()}
    val route=path.last()
    val id=route.substringAfter(':')
    val current=l.payments.find {it.id==id}
    if(current==null) {LaunchedEffect(path) {back()};return}
    states.SaveableStateProvider(path.joinToString("/")) {
        if(route.startsWith("refunds:")) ReceiptList(l,ReceiptRange("关联退款",null,null,refundOf=id),back,change,"返回付款详情",openPayment={path=path+"payment:${it.id}"})
        else PaymentDetailContent(l,current,back,change,if(path.size==1) backDescription else if(path[path.lastIndex-1].startsWith("refunds:")) "返回关联退款" else "返回退款详情",
            openOriginal={path=path+"payment:$it"},openRefunds={path=path+"refunds:$it"})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PaymentDetailContent(l:Ledger,p:Payment,close:()->Unit,change:((Ledger)->Ledger)->Unit,backDescription:String,
    openOriginal:(String)->Unit,openRefunds:(String)->Unit) {
    val context=LocalContext.current
    var deleting by remember { mutableStateOf(false) }
    var refunding by rememberSaveable(p.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var editing by rememberSaveable(p.id) { mutableStateOf(false) }
    var cny by rememberSaveable(p.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val missing=p.currency!="CNY" && p.cnyAmount==null
    BackHandler {if(refunding) refunding=false else if(editing) editing=false else close()}
    if(refunding) {RefundEditor(l,p,{refunding=false},save={context.repository().update(it)});return}
    if(editing) {PaymentEditor(l,p,{editing=false},change);return}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(if(missing) "补录实付金额" else if(p.refundOf!=null) "退款详情" else "付款详情",fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack(backDescription,close)},actions={
            Box {
                IconButton({more=true}) {Icon(Icons.Outlined.MoreVert,"付款更多操作")}
                DropdownMenu(more,{more=false}) {
                    if(p.refundOf==null && !Prepaid.isTopUp(p)) DropdownMenuItem(text={Text("记录退款")},onClick={more=false;refunding=true})
                    DropdownMenuItem(text={Text(if(p.refundOf==null) "更正付款" else "更正退款")},onClick={more=false;editing=true})
                    DropdownMenuItem(text={Text(if(p.refundOf==null) "删除这笔付款" else "删除这笔退款")},onClick={more=false;deleting=true})
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ServiceIcon(p.planName,size=48.dp)
                    Text(p.planName,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(missing) "${p.currency} ${p.amount}" else cash(p.signedCny()!!),fontSize=36.sp,fontWeight=FontWeight.Bold)
                    if(!missing && p.currency!="CNY") Text("原币 ${p.currency} ${p.amount}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.date,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(Prepaid.isTopUp(p)) Text("充值 · 不计支出",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else if(p.note.isNotBlank() && p.note !in setOf("订阅付款","话费扣费")) Text(p.note,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(p.note=="话费扣费") Text("按月费记录",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(p.refundOf!=null) {
                val original=l.payments.find { it.id==p.refundOf }
                Text("退款 · 冲减退款当期支出",color=MaterialTheme.colorScheme.tertiary)
                original?.let {
                    ReceiptLink("查看原付款", "${it.date} · ${it.signedCny()?.let(::cash) ?: "${it.currency} ${it.amount}"}") {openOriginal(it.id)}
                }
            } else if(!Prepaid.isTopUp(p)) {
                val refunds=l.payments.filter {it.refundOf==p.id}
                if(refunds.isNotEmpty()) {
                    val total=LedgerStats.breakdown(refunds).refunds
                    ReceiptLink("已退款 ${refunds.size} 笔 · ${cash(total.known)}",if(total.missing>0) "${total.missing} 笔人民币待补录" else null) {openRefunds(p.id)}
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
        }
    }
    if(deleting) DeletePaymentsDialog(l,setOf(p.id),{deleting=false}) {change {Book.deletePayments(it,setOf(p.id))};deleting=false;close()}
}
@Composable private fun ReceiptLink(title:String,subtitle:String?,open:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClick=open).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary)
            subtitle?.let {Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.primary)
    }
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
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(if(p.refundOf==null) "更正付款" else "更正退款",fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack("返回付款详情",close)})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon(p.planName,size=40.dp);Text(p.planName,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            }
            Text("只更正账目，不改变权益到期日和话费余额。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyField("${if(p.refundOf==null) "付款" else "退款"}金额 ${p.currency}",amount,{amount=it})
            Field(if(p.refundOf==null) "付款日期" else "退款日期",date,{date=it},dateField=true)
            if(p.currency!="CNY") {
                MoneyField(if(p.refundOf==null) "人民币实付金额" else "人民币实退金额",cny,{cny=it})
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
        Text("选择是否保留历史付款和关联退款。删除不能撤销。")
        Row(Modifier.fillMaxWidth().clickable {includePayments=false},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(!includePayments,{includePayments=false});Text("仅删除订阅，保留付款")
        }
        Row(Modifier.fillMaxWidth().clickable {includePayments=true},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(includePayments,{includePayments=true});Text("同时删除付款记录（${receipts.size} 笔）")
        }
        if(includePayments) Text("将移除 ${cash(LedgerStats.summary(receipts.filterNot(Prepaid::isTopUp)).known)} 已记录支出，统计随之更新。",fontSize=13.sp)
    }},confirmButton={TextButton({confirm(includePayments)}) {Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(close) {Text("取消")}})
}
