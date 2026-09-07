package cn.renewboard

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

@Composable internal fun LedgerScreen(l:Ledger,change:((Ledger)->Ledger)->Unit) {
    val today=LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month=YearMonth.parse(monthText)
    var range by rememberSaveable { mutableStateOf(TrendRange.SIX) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var expandedRank by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ReceiptRange?>(null) }
    var payment by remember { mutableStateOf<Payment?>(null) }
    val monthly=l.payments.filter { within(it,month.atDay(1),minOf(month.plusMonths(1).atDay(1),today.plusDays(1))) }
    val sum=LedgerStats.summary(monthly)
    val previous=LedgerStats.summary(l.payments.filter { within(it,month.minusMonths(1).atDay(1),month.atDay(1)) })
    val buckets=LedgerStats.buckets(l.payments,range,today,year)
    val rangeSum=LedgerStats.summary(if(buckets.isEmpty()) emptyList() else l.payments.filter { within(it,buckets.first().from,buckets.last().until) })
    val firstMonth=l.payments.minOfOrNull { YearMonth.from(LocalDate.parse(it.date)) } ?: YearMonth.from(today)
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("支出概览",fontSize=28.sp,fontWeight=FontWeight.Bold)
        }
        TextButton({detail=ReceiptRange("全部付款",null,null)}) { Text("全部明细");Icon(Icons.Outlined.ChevronRight,null) }
    }
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary),shape=RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("${month.year} 年 ${month.monthValue} 月",color=Color.White.copy(alpha=.85f),fontSize=16.sp,modifier=Modifier.weight(1f))
                IconButton({monthText=month.minusMonths(1).toString()},enabled=month>firstMonth) { Icon(Icons.Outlined.ChevronLeft,"上个月",tint=Color.White.copy(alpha=if(month>firstMonth) 1f else .35f)) }
                IconButton({monthText=month.plusMonths(1).toString()},enabled=month<YearMonth.from(today)) { Icon(Icons.Outlined.ChevronRight,"下个月",tint=Color.White.copy(alpha=if(month<YearMonth.from(today)) 1f else .35f)) }
            }
            Text(if(sum.missing>0) "已确认实付" else "本月实付",fontSize=14.sp,color=Color.White.copy(alpha=.8f))
            Text(cash(sum.known),fontSize=42.sp,lineHeight=52.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,modifier=Modifier.padding(top=4.dp,bottom=16.dp))
            HorizontalDivider(color=Color.White.copy(alpha=.18f))
            Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${sum.count} 笔付款",color=Color.White.copy(alpha=.85f))
                val delta=sum.known-previous.known
                Text(if(sum.missing>0 || previous.missing>0) "有金额待补录" else if(previous.known.signum()==0) "上月 ${cash(previous.known)}"
                    else "较上月全月 ${if(delta.signum()>0) "+" else if(delta.signum()<0) "−" else ""}${cash(delta.abs())}",color=Color.White.copy(alpha=.85f),fontSize=14.sp)
            }
            if(sum.missing>0) Text("${sum.missing} 笔外币待补录，未计入统计",color=Color.White,modifier=Modifier.padding(top=10.dp),fontSize=13.sp)
        }
    }
    Heading("支出趋势", "截至 ${today.monthValue}月${today.dayOfMonth}日")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        TrendRange.entries.forEach { item -> FilterChip(selected=range==item,onClick={range=item},label={Text(item.label)},modifier=Modifier.semantics { contentDescription="趋势范围 ${item.label}" }) }
    }
    if(range==TrendRange.YEAR) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        IconButton({year--},enabled=year>minOf(firstMonth.year,today.year)) {Icon(Icons.Outlined.ChevronLeft,"上一年")}
        Text("$year 年",fontWeight=FontWeight.SemiBold)
        IconButton({year++},enabled=year<today.year) {Icon(Icons.Outlined.ChevronRight,"下一年")}
    }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("范围实付 ${cash(rangeSum.known)}",fontWeight=FontWeight.SemiBold)
                Text("${rangeSum.count} 笔",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
            }
            if(rangeSum.missing>0) Text("${rangeSum.missing} 笔金额待补录",color=MaterialTheme.colorScheme.tertiary,fontSize=13.sp)
            if(buckets.isEmpty()) Text("记录第一笔付款后，这里会显示趋势。",modifier=Modifier.padding(vertical=30.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else TrendBars(buckets,month) { bucket ->
                if(range==TrendRange.THREE || range==TrendRange.SIX || range==TrendRange.TWELVE || range==TrendRange.YEAR) monthText=YearMonth.from(bucket.from).toString()
                detail=ReceiptRange(bucket.label+"付款",bucket.from,bucket.until)
            }
        }
    }
    Heading("钱花在哪里", "${month.monthValue} 月 · 按应用")
    val ranking=monthly.groupBy { it.planName }.map { (name,ps)->name to LedgerStats.summary(ps) }.sortedByDescending { it.second.known }
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if(ranking.isEmpty()) Text("这个月还没有付款",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
            val largest=ranking.maxOfOrNull { it.second.known }?.takeIf { it.signum()>0 } ?: BigDecimal.ONE
            ranking.take(if(expandedRank) ranking.size else 5).forEachIndexed { i,(name,spending) ->
                val fraction=spending.known.divide(largest,6,RoundingMode.HALF_UP).toFloat()
                val share=if(sum.known.signum()>0) spending.known.multiply(BigDecimal(100)).divide(sum.known,1,RoundingMode.HALF_UP).toPlainString() else "0.0"
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
    monthly.sortedByDescending { it.date }.take(3).forEach { p -> PaymentRow(p,open={payment=p}) }
    if(monthly.isEmpty()) Text("本月暂无付款记录",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=14.dp))
    detail?.let { target -> ReceiptList(l,target,{detail=null},change) }
    payment?.let { selected -> l.payments.find { it.id==selected.id }?.let { PaymentDialog(l,it,{payment=null},change) } }
}

@Composable private fun Heading(title:String,subtitle:String) {
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TrendBars(buckets:List<CashBucket>,month:YearMonth,open:(CashBucket)->Unit) {
    val maximum=buckets.maxOf { it.summary.known }.max(BigDecimal.ONE)
    val monthLabels=buckets.all { it.from.dayOfMonth==1 } && buckets.any { '/' in it.label }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth=(maxWidth/buckets.size.coerceAtMost(12)).coerceAtLeast(30.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=16.dp)) {
            buckets.forEach { bucket ->
                val fraction=bucket.summary.known.divide(maximum,6,RoundingMode.HALF_UP).toFloat()
                val active=YearMonth.from(bucket.from)==month
                Column(Modifier.width(cellWidth).clickable { open(bucket) }.semantics {
                    contentDescription="${bucket.label}，实付${cash(bucket.summary.known)}，${bucket.summary.count}笔，待补录${bucket.summary.missing}笔"
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
            Text("${p.date} · ${if(p.note=="话费充值") "话费充值" else "订阅付款"}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
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
        "订阅付款" -> p.note!="话费充值"
        "话费充值" -> p.note=="话费充值"
        "已删订阅" -> l.plans.none { it.id==p.planId }
        else -> true
    } }.sortedByDescending { it.date }
    val effective=selected.intersect(visible.map { it.id }.toSet())
    val sum=LedgerStats.summary(visible)
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Text(target.title,fontWeight=FontWeight.SemiBold)},navigationIcon={IconButton(close) {Icon(Icons.Outlined.ArrowBack,"返回概览")}},actions={
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
                    listOf("全部","订阅付款","话费充值","已删订阅").forEach { option -> FilterChip(kind==option,{kind=option;selected=emptySet()},label={Text(option)}) }
                }
                Text("${visible.size} 笔 · ${cash(sum.known)}"+if(sum.missing>0) " · ${sum.missing} 笔待补录" else "",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    if(visible.isEmpty()) item {Text("没有符合条件的付款",modifier=Modifier.padding(vertical=32.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    visible.groupBy { it.date }.forEach { (date,payments) ->
                        item(key="date-$date") {Text(date,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=18.dp))}
                        items(payments,key={it.id}) { p -> PaymentRow(p,{payment=p},if(managing) p.id in effective else null) { selected=if(p.id in selected) selected-p.id else selected+p.id } }
                    }
                }
            }
        }
        deleting?.let { ids -> DeletePaymentsDialog(l,ids,{deleting=null}) { change { Book.deletePayments(it,ids) };deleting=null;selected=emptySet() } }
        payment?.let { p -> l.payments.find { it.id==p.id }?.let { PaymentDialog(l,it,{payment=null},change) } }
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
@Composable private fun PaymentDialog(l:Ledger,p:Payment,close:()->Unit,change:((Ledger)->Ledger)->Unit) {
    var deleting by remember { mutableStateOf(false) }
    var cny by rememberSaveable(p.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val missing=p.currency!="CNY" && p.cnyAmount==null
    AlertDialog(onDismissRequest=close,title={Text(if(missing) "补录付款日实付金额" else "付款详情")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { ServiceIcon(p.planName);Text(p.planName,fontWeight=FontWeight.SemiBold) }
        Text(p.date,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))
        Text(if(missing) "${p.currency} ${p.amount}" else cash(BigDecimal(if(p.currency=="CNY") p.amount else p.cnyAmount!!)),fontSize=30.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
        if(!missing && p.currency!="CNY") Text("原币 ${p.currency} ${p.amount}")
        if(p.note.isNotBlank()) Text(p.note)
        if(l.plans.none { it.id==p.planId }) Text("关联订阅已删除",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
        if(missing) {
            Field("人民币实付金额",cny,{cny=it})
            Text("按付款当天账单填写，保存后固定。",fontSize=13.sp)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
        TextButton({deleting=true}) {Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(6.dp));Text("删除这笔付款",color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({
        if(missing) try {
            val settled=cny.trim()
            fun updated(old:Ledger)=old.copy(payments=old.payments.map { if(it.id==p.id && it.cnyAmount==null) it.copy(cnyAmount=settled) else it })
            Book.validate(updated(l));change(::updated);close()
        } catch(e:Exception) {error="请输入有效的人民币金额"}
        else close()
    }) {Text(if(missing) "保存金额" else "关闭")}},dismissButton={if(missing) TextButton(close) {Text("取消")}})
    if(deleting) DeletePaymentsDialog(l,setOf(p.id),{deleting=false}) {change {Book.deletePayments(it,setOf(p.id))};deleting=false;close()}
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
        if(includePayments) Text("将移除 ${cash(LedgerStats.summary(receipts).known)} 已确认支出，统计随之更新。",fontSize=13.sp)
    }},confirmButton={TextButton({confirm(includePayments)}) {Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(close) {Text("取消")}})
}
