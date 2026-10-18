package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.platform.testTag
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
private data class ReceiptRange(val title:String,val from:LocalDate?,val until:LocalDate?,val planName:String?=null,val planId:String?=null,val refundOf:String?=null,val missingOnly:Boolean=false)

@Composable internal fun LedgerScreen(l:Ledger,change:((Ledger)->Ledger)->Unit,openServices:()->Unit={},onSubpageChange:(Boolean)->Unit={}) {
    val today=LocalDate.now()
    val month=YearMonth.from(today)
    val overviewScroll=rememberScrollState()
    var selectedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableStateOf(TrendRange.SIX) }
    var rangeMenuOpen by remember { mutableStateOf(false) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    val trendScroll=rememberSaveable(range,year,saver=ScrollState.Saver) { ScrollState(Int.MAX_VALUE) }
    var expandedCash by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ReceiptRange?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val expenses=Prepaid.expenses(l)
    val colorIndices=expenses.map {it.planName}.distinct().sorted().withIndex().associate {it.value to it.index}
    val buckets=LedgerStats.buckets(expenses,range,today,year)
    val rangePayments=if(buckets.isEmpty()) emptyList() else expenses.filter { within(it,buckets.first().from,buckets.last().until) }
    val rangeSum=LedgerStats.summary(rangePayments)
    val selectedBucket=buckets.find { it.from.toString()==selectedFrom }
    val rankedPayments=selectedBucket?.let { bucket -> rangePayments.filter { within(it,bucket.from,bucket.until) } } ?: rangePayments
    val rankSum=LedgerStats.summary(rankedPayments)
    val rangePeriod=when(range) {
        TrendRange.YEAR -> "$year 年"
        else -> range.label
    }
    val rankPeriod=selectedBucket?.let { bucket ->
        when {
            range==TrendRange.MONTH -> "${bucket.from.year}年${bucket.from.monthValue}月${bucket.from.dayOfMonth}日"
            '/' in bucket.label -> "${bucket.from.year}年${bucket.from.monthValue}月"
            else -> "${bucket.from.year}年"
        }
    } ?: rangePeriod
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
    Column(Modifier.fillMaxSize().pageTransition("overview").verticalScroll(overviewScroll).padding(horizontal=20.dp).padding(bottom=24.dp)) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("支出概览",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        TextButton(openServices,modifier=Modifier.semantics {contentDescription="打开服务管理"}) {Text("服务管理");Icon(Icons.Outlined.ChevronRight,null)}
    }
    Heading("支出趋势")
    Box {
        OutlinedButton(onClick={rangeMenuOpen=true},modifier=Modifier.semantics {contentDescription="选择趋势范围"}) {
            Text(range.label)
            Icon(Icons.Outlined.ExpandMore,null,modifier=Modifier.padding(start=6.dp).size(20.dp))
        }
        DropdownMenu(expanded=rangeMenuOpen,onDismissRequest={rangeMenuOpen=false}) {
            TrendRange.entries.forEach { item ->
                DropdownMenuItem(
                    text={Text(item.label)},
                    onClick={range=item;selectedFrom=null;rangeMenuOpen=false},
                    trailingIcon={if(range==item) Icon(Icons.Outlined.Check,null)},
                    modifier=Modifier.semantics {contentDescription="趋势范围 ${item.label}";selected=range==item}
                )
            }
        }
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
                Text("${if(rangeSum.missing>0) "已知净支出" else "净支出"} ${cash(rangeSum.known)}",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
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
            if(rankSum.missing>0) TextButton(onClick={
                detail=ReceiptRange("$rankPeriod · 待补录人民币",selectedBucket?.from ?: buckets.firstOrNull()?.from,
                    selectedBucket?.until ?: buckets.lastOrNull()?.until,missingOnly=true)
            },contentPadding=PaddingValues(vertical=8.dp)) {
                Text("$rankPeriod · ${rankSum.missing} 笔金额待补录",color=MaterialTheme.colorScheme.tertiary,fontSize=13.sp)
                Icon(Icons.Outlined.ChevronRight,null,modifier=Modifier.size(18.dp))
            }
            if(buckets.isEmpty()) Text("记录第一笔付款后，这里会显示趋势。",modifier=Modifier.padding(vertical=30.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else TrendBars(buckets,selectedBucket?.from,trendScroll,colorIndices) { bucket ->
                selectedFrom=if(selectedFrom==bucket.from.toString()) null else bucket.from.toString()
            }
            selectedBucket?.let { bucket ->
                Text("${bucket.label} · ${if(bucket.summary.missing>0) "已知净支出" else "净支出"} ${cash(bucket.summary.known)} · ${bucket.summary.count} 笔",
                    fontSize=13.sp,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp).testTag("selected-trend-summary"))
            }
            if(buckets.any { it.negative.signum()>0 }) Text("各应用净支出在零线上方，净退款在下方",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        TextButton({detail=selectedDetail ?: ReceiptRange("全部付款",null,null)}) {Text(if(selectedDetail!=null) "所选时段明细" else "全部明细");Icon(Icons.Outlined.ChevronRight,null)}
    }
    Heading("净支出分布")
    val ranking=rankedPayments.groupBy { it.planName }.map { (name,ps)->name to LedgerStats.summary(ps) }.sortedByDescending { it.second.known }
    fun openDistribution(name:String) {
        detail=ReceiptRange("$name · $rankPeriod",selectedBucket?.from ?: buckets.firstOrNull()?.from,
            selectedBucket?.until ?: buckets.lastOrNull()?.until,name)
    }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if(ranking.isEmpty()) Text("这个时段还没有付款",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
            val positiveTotal=ranking.filter {it.second.known.signum()>0}.fold(BigDecimal.ZERO) {sum,item -> sum+item.second.known}
            val hasNegative=ranking.any { it.second.known.signum()<0 }
            if(positiveTotal.signum()>0) {
                SpendingPie(ranking,positiveTotal,!hasNegative && rankSum.missing==0,colorIndices,onOpen=::openDistribution)
                if(hasNegative || rankSum.missing>0) Text(
                    listOfNotNull(if(rankSum.missing>0) "仅显示已知人民币金额" else null,if(hasNegative) "净退款单列，不计入饼图" else null).joinToString("；"),
                    fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=10.dp))
            } else if(ranking.isNotEmpty()) Text(
                if(rankSum.missing>0) "补录人民币金额后显示饼图" else "这个时段没有正向净支出",
                color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
            // Refunds and unsettled-only amounts have no positive wedge; keep them reachable here.
            ranking.filter {it.second.known.signum()<=0}.forEach {(name,spending)->
                TextButton(onClick={openDistribution(name)},modifier=Modifier.fillMaxWidth().semantics {
                    contentDescription="$name，支出${cash(spending.known)}，净支出，待补录${spending.missing}笔"
                }) {
                    ServiceIcon(name,size=20.dp,modifier=Modifier.padding(end=6.dp))
                    Text(name,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(spending.missing>0) "待补录 ${spending.missing} 笔" else cash(spending.known))
                    Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
                }
            }
        }
    }
    TextButton(onClick={detail=ReceiptRange("本月明细",month.atDay(1),month.plusMonths(1).atDay(1))},
        modifier=Modifier.align(Alignment.End).padding(top=12.dp)) {
        Text("本月明细");Icon(Icons.Outlined.ChevronRight,null)
    }
    }
}

@Composable private fun CashPart(label:String,summary:CashSummary,modifier:Modifier=Modifier) {
    Column(modifier) {
        Text("$label ${cash(summary.known)}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(summary.missing>0) Text("${summary.missing} 笔待补录",fontSize=12.sp,color=MaterialTheme.colorScheme.tertiary)
    }
}

@Composable private fun Heading(title:String) {
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
    }
}
private val spendingColors=listOf(
    Color(0xFF7952CC),Color(0xFFCA820A),Color(0xFF008C82),Color(0xFF3478E5),
    Color(0xFFD84F6B),Color(0xFF4B8D35),Color(0xFF157CA9),Color(0xFF9B633D)
)
private fun spendingColor(index:Int):Color=if(index<spendingColors.size) spendingColors[index]
    else Color.hsv((index*137.508f)%360f,.68f,.72f)

private data class SpendingSlice(val index:Int,val name:String,val spending:CashSummary,val start:Float,val sweep:Float)

@Composable private fun SpendingPie(ranking:List<Pair<String,CashSummary>>,total:BigDecimal,showShares:Boolean,colorIndices:Map<String,Int>,onOpen:(String)->Unit) {
    val positives=ranking.filter {it.second.known.signum()>0}
    var angle=-90f
    val slices=positives.mapIndexed {i,item->
        val sweep=if(i==positives.lastIndex) 270f-angle else item.second.known.divide(total,8,RoundingMode.HALF_UP).toFloat()*360f
        SpendingSlice(colorIndices.getValue(item.first),item.first,item.second,angle,sweep).also {angle+=sweep}
    }
    var selectedName by remember(ranking) {mutableStateOf<String?>(null)}
    val selected=slices.find {it.name==selectedName}
    fun share(slice:SpendingSlice)=slice.spending.known.multiply(BigDecimal(100)).divide(total,1,RoundingMode.HALF_UP).toPlainString()+"%"
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.padding(top=8.dp,bottom=12.dp).size(184.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().testTag("ledger-spending-pie").semantics {
                contentDescription="净支出圆环图，${slices.size}项，已知正向净支出${cash(total)}"
            }.pointerInput(slices) {
                detectTapGestures {point->
                    val dx=point.x-size.width/2f;val dy=point.y-size.height/2f
                    val outer=size.width/2f-3.dp.toPx();val inner=outer-32.dp.toPx()
                    val distance=dx*dx+dy*dy
                    if(distance in inner*inner..outer*outer) {
                        val degrees=(Math.toDegrees(atan2(dy,dx).toDouble()).toFloat()+450f)%360f
                        slices.firstOrNull {degrees<it.start+90f+it.sweep}?.let {selectedName=if(selectedName==it.name) null else it.name}
                    }
                }
            }) {
                val center=Offset(size.width/2,size.height/2)
                val r=size.minDimension/2-19.dp.toPx()
                slices.forEach {slice->
                    val active=selectedName==slice.name
                    val gap=if(slices.size>1) minOf(.8f,slice.sweep*.12f) else 0f
                    drawArc(spendingColor(slice.index).copy(alpha=if(selectedName==null || active) 1f else .65f),slice.start+gap/2,slice.sweep-gap,false,
                        topLeft=center-Offset(r,r),size=Size(r*2,r*2),style=Stroke((if(active) 32 else 28).dp.toPx()))
                }
            }
            Column(Modifier.width(116.dp).then(if(selected!=null) Modifier.clickable {onOpen(selected.name)}.semantics {
                contentDescription="查看${selected.name}明细"
            } else Modifier),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(selected?.name ?: if(showShares) "净支出" else "已知正向净支出",fontSize=11.sp,lineHeight=15.sp,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                val amount=cash(selected?.spending?.known ?: total)
                Text(amount,fontSize=if(amount.length>10) 16.sp else 20.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(selected!=null) {
                    if(showShares) Text(share(selected),fontSize=12.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("查看明细 ›",fontSize=11.sp,lineHeight=15.sp,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag("ledger-spending-legend")) {
            slices.forEach {slice->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if(selectedName==slice.name) spendingColor(slice.index).copy(alpha=.1f) else Color.Transparent)
                    .clickable {onOpen(slice.name)}.heightIn(min=40.dp).padding(horizontal=4.dp,vertical=6.dp).semantics {
                        contentDescription="${slice.name}，支出${cash(slice.spending.known)}"+(if(showShares) "，占比${share(slice)}" else "，净支出")+"，待补录${slice.spending.missing}笔"
                    },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(5.dp).height(18.dp).background(spendingColor(slice.index),RoundedCornerShape(2.dp)))
                    ServiceIcon(slice.name,size=22.dp)
                    Text(slice.name,fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(cash(slice.spending.known),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                }
            }
        }
    }
}

@Composable private fun TrendBars(buckets:List<CashBucket>,selectedFrom:LocalDate?,scroll:ScrollState,colorIndices:Map<String,Int>,open:(CashBucket)->Unit) {
    val maximum=buckets.maxOf { it.positive.max(it.negative) }.max(BigDecimal.ONE)
    val hasNegative=buckets.any { it.negative.signum()>0 }
    val monthLabels=buckets.all { it.from.dayOfMonth==1 } && buckets.any { '/' in it.label }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth=(maxWidth/buckets.size.coerceAtMost(12)).coerceAtLeast(48.dp)
        Row(Modifier.testTag("ledger-trend-bars").horizontalScroll(scroll).padding(top=16.dp)) {
            buckets.forEach { bucket ->
                val negative=bucket.summary.known.signum()<0
                val active=bucket.from==selectedFrom
                Column(Modifier.width(cellWidth).clickable { open(bucket) }.semantics {
                    selected=active
                    contentDescription="${bucket.label}，支出${cash(bucket.summary.known)}，${bucket.summary.count}笔，待补录${bucket.summary.missing}笔"+if(negative) "，净退款，零线下方" else ""
                },horizontalAlignment=Alignment.CenterHorizontally) {
                    if(buckets.size<=12) Text(if(bucket.summary.missing>0) "待补" else smallAmount(bucket.summary.known),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    Box(Modifier.height(120.dp).fillMaxWidth()) {
                        val baseline=if(hasNegative) 60.dp else 112.dp
                        Box(Modifier.offset(y=baseline).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        for(below in listOf(false,true)) {
                            val amount=if(below) bucket.negative else bucket.positive
                            if(amount.signum()>0) {
                                val fraction=amount.divide(maximum,6,RoundingMode.HALF_UP).toFloat()
                                val barHeight=((if(hasNegative) 52 else 100)*fraction).dp.coerceAtLeast(2.dp)
                                val segments=bucket.byApp.entries.filter {(it.value.signum()<0)==below}.sortedBy {colorIndices.getValue(it.key)}
                                Column(Modifier.align(Alignment.TopCenter).offset(y=if(below) baseline else baseline-barHeight)
                                    .width((cellWidth*.48f).coerceAtMost(28.dp)).height(barHeight).clip(RoundedCornerShape(4.dp))) {
                                    (if(below) segments else segments.asReversed()).forEach {(name,value)->
                                        Box(Modifier.fillMaxWidth().weight(value.abs().divide(amount,java.math.MathContext.DECIMAL64).toFloat())
                                            .background(spendingColor(colorIndices.getValue(name)))
                                            .testTag("trend-segment:${bucket.from}:$name").semantics {
                                                contentDescription="$name，${if(below) "净退款" else "净支出"}${cash(value.abs())}"
                                            })
                                    }
                                }
                            }
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
            if(p.refundOf!=null) Text("退款",fontSize=12.sp,color=MaterialTheme.colorScheme.tertiary)
            else if(p.note=="话费扣费") Text("按月费记录",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(Prepaid.isTopUp(p)) Text("充值 · 不计支出",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        val known=if(p.currency=="CNY") p.amount else p.cnyAmount
        if(known==null) Column(horizontalAlignment=Alignment.End) {
            Text("${p.currency} ${p.amount}",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
            TextButton(open,contentPadding=PaddingValues(horizontal=0.dp)) {Text("补录人民币",fontSize=13.sp)}
        }
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
    var kindMenuOpen by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val scoped=l.payments.filter { p -> within(p,target.from,target.until) && (target.planName==null || p.planName==target.planName) && (target.planId==null || p.planId==target.planId) && (target.refundOf==null || p.refundOf==target.refundOf) &&
        (!target.missingOnly || (!Prepaid.isTopUp(p) && p.currency!="CNY" && p.cnyAmount==null)) }
    val visible=scoped.filter { p -> p.planName.contains(query.trim(),ignoreCase=true) && when(kind) {
        "订阅付款" -> p.refundOf==null && !Prepaid.isTopUp(p) && !UsageBalances.isTopUp(p) && p.note !in setOf("话费扣费","话费额外扣费")
        "付款" -> p.refundOf==null && !Prepaid.isTopUp(p)
        "退款" -> p.refundOf!=null
        "话费扣费" -> p.refundOf==null && p.note in setOf("话费扣费","话费额外扣费")
        "话费充值" -> Prepaid.isTopUp(p)
        "平台充值" -> UsageBalances.isTopUp(p)
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
    Column(Modifier.fillMaxSize().pageTransition("receipts")) {
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
                Surface(Modifier.fillMaxWidth().padding(top=12.dp,bottom=16.dp),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(if(query.isNotBlank() || kind!="全部") "筛选结果" else "明细汇总",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(target.missingOnly) "${visible.size} 笔待补录" else
                            "${visible.size} 笔 · ${if(target.refundOf!=null) "退款 ${cash(sum.known.abs())}" else "${if(sum.missing>0) "已知净支出" else "净支出"} ${cash(sum.known)}"}"+if(sum.missing>0) " · ${sum.missing} 笔待补录" else "",
                            fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
                        if(topups.count>0) Text("充值 ${cash(topups.known)} · 不计支出"+if(topups.missing>0) " · ${topups.missing} 笔待补录" else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(target.refundOf==null) {
                    if(target.planId==null) OutlinedTextField(query,{query=it;selected=emptySet()},label={Text("搜索应用")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
                    if(!target.missingOnly) Box {
                        val options=if(target.planId!=null) {
                            if(l.plans.find {it.id==target.planId}?.balanceAccount!=null) listOf("全部","话费扣费","话费充值","退款")
                            else if(l.plans.find {it.id==target.planId}?.usageBalance!=null) listOf("全部","平台充值","退款") else listOf("全部","付款","退款")
                        } else listOf("全部","订阅付款","平台充值","退款","话费扣费","话费充值","已删订阅")
                        OutlinedButton(onClick={kindMenuOpen=true},modifier=Modifier.semantics {contentDescription="选择付款类型"}) {
                            Text("类型：$kind")
                            Icon(Icons.Outlined.ExpandMore,null,modifier=Modifier.padding(start=6.dp).size(20.dp))
                        }
                        DropdownMenu(expanded=kindMenuOpen,onDismissRequest={kindMenuOpen=false}) {
                            options.forEach { option ->
                                DropdownMenuItem(text={Text(option)},onClick={kind=option;selected=emptySet();kindMenuOpen=false},
                                    trailingIcon={if(kind==option) Icon(Icons.Outlined.Check,null)},
                                    modifier=Modifier.semantics {contentDescription="付款类型 $option";this.selected=kind==option})
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("记录明细",fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    Text("按日期倒序",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal=16.dp),state=listState,contentPadding=PaddingValues(bottom=20.dp)) {
                    if(visible.isEmpty()) item {Text(if(target.missingOnly && scoped.isEmpty()) "当前时段的人民币金额已全部补录" else "没有符合条件的付款",modifier=Modifier.padding(vertical=32.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
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
@Composable internal fun PlanHistoryScreen(l:Ledger,planId:String,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    val plan=l.plans.find {it.id==planId}
    val name=plan?.name ?: l.payments.find {it.planId==planId}?.planName ?: "订阅"
    ReceiptList(l,ReceiptRange("$name · 全部记录",null,null,planId=planId),close,change,if(plan?.usageBalance!=null) "返回余额账户" else if(plan?.balanceAccount!=null) "返回话费详情" else "返回订阅详情")
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
    val missing=p.currency!="CNY" && p.cnyAmount==null
    var settling by rememberSaveable(p.id) {mutableStateOf(missing)}
    var continueRefund by rememberSaveable(p.id) {mutableStateOf(false)}
    BackHandler {if(refunding) refunding=false else if(editing) editing=false else close()}
    if(refunding) {RefundEditor(l,p,{refunding=false},save={context.repository().update(it)});return}
    if(editing) {ReceiptEditor(l,p,{editing=false},{editing=false;settling=false},save={context.repository().update(it)});return}
    val receiptActions:@Composable RowScope.()->Unit={
            Box {
                IconButton({more=true}) {Icon(Icons.Outlined.MoreVert,"付款更多操作")}
                DropdownMenu(more,{more=false}) {
                    if(p.refundOf==null && !Prepaid.isTopUp(p)) DropdownMenuItem(text={Text("记录退款")},onClick={more=false;if(missing) {continueRefund=true;settling=true} else refunding=true})
                    DropdownMenuItem(text={Text(if(p.refundOf==null) "更正付款" else "更正退款")},onClick={more=false;editing=true})
                    DropdownMenuItem(text={Text(if(p.refundOf==null) "删除这笔付款" else "删除这笔退款")},onClick={more=false;deleting=true})
                }
            }
    }
    if(settling) {
        ReceiptEditor(l,p,close,saved={settling=false;if(continueRefund) refunding=true else close()},save={context.repository().update(it)},
            settlementOnly=true,continueRefund=continueRefund,backDescription=backDescription,actions=receiptActions)
        if(deleting) DeletePaymentsDialog(l,setOf(p.id),{deleting=false}) {change {Book.deletePayments(it,setOf(p.id))};deleting=false;close()}
        return
    }
    Scaffold(modifier=Modifier.pageTransition("payment:${p.id}"),containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(if(p.refundOf!=null) "退款详情" else "付款详情",fontWeight=FontWeight.SemiBold)},navigationIcon={ReceiptBack(backDescription,close)},actions=receiptActions)
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
