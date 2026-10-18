package cn.renewboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/** Year and month have separate, always-visible entry points. Changes apply only on confirmation. */
@Composable internal fun CalendarDateDialog(label: String, value: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    val initial = remember(value) { runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now()).let {
        if(it.year in 1900..2200) it else LocalDate.now()
    } }
    var selected by rememberSaveable { mutableStateOf(initial.toString()) }
    var panel by rememberSaveable { mutableStateOf("day") }
    val date = LocalDate.parse(selected)
    val month = YearMonth.from(date)
    fun setMonth(year: Int, month: Int) {
        if(year !in 1900..2200) return
        val target = YearMonth.of(year, month)
        selected = target.atDay(date.dayOfMonth.coerceAtMost(target.lengthOfMonth())).toString()
        panel = "day"
    }
    AlertDialog(onDismissRequest=dismiss, title={ Text("选择$label") }, text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick={panel="year"},modifier=Modifier.weight(1.2f).semantics {contentDescription="选择年份"},shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=10.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=MaterialTheme.colorScheme.onSurface)) {
                    Text("${date.year} 年",fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,softWrap=false); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
                }
                FilledTonalButton(onClick={panel="month"},modifier=Modifier.weight(1f).semantics {contentDescription="选择月份"},shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=10.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=MaterialTheme.colorScheme.onSurface)) {
                    Text("${date.monthValue} 月",fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,softWrap=false); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={val previous=month.minusMonths(1);setMonth(previous.year,previous.monthValue)},enabled=month>YearMonth.of(1900,1)) {Icon(Icons.Outlined.ChevronLeft,"上个月")}
                TextButton(onClick={selected=LocalDate.now().toString();panel="day"}) {Text("今天")}
                IconButton(onClick={val next=month.plusMonths(1);setMonth(next.year,next.monthValue)},enabled=month<YearMonth.of(2200,12)) {Icon(Icons.Outlined.ChevronRight,"下个月")}
            }
            when(panel) {
                "year" -> {
                    val state = rememberLazyGridState(initialFirstVisibleItemIndex=((date.year-1900)/3)*3)
                    LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(294.dp),state=state) {
                        items((1900..2200).toList()) { year ->
                            TextButton(onClick={setMonth(year,date.monthValue)},modifier=Modifier.heightIn(min=48.dp).padding(2.dp),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=2.dp,vertical=8.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(year==date.year) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,contentColor=if(year==date.year) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {Text("$year 年",fontWeight=if(year==date.year) FontWeight.Bold else FontWeight.Normal,maxLines=1,softWrap=false)}
                        }
                    }
                }
                "month" -> LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(294.dp)) {
                    items((1..12).toList()) { number ->
                        TextButton(onClick={setMonth(date.year,number)},modifier=Modifier.heightIn(min=64.dp).padding(2.dp),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(number==date.monthValue) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,contentColor=if(number==date.monthValue) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {Text("$number 月",fontWeight=if(number==date.monthValue) FontWeight.Bold else FontWeight.Normal)}
                    }
                }
                else -> Column {
                    Row { listOf("一","二","三","四","五","六","日").forEach { day ->
                        Box(Modifier.weight(1f).height(36.dp),contentAlignment=Alignment.Center) {Text(day,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}
                    } }
                    val offset = month.atDay(1).dayOfWeek.value-1
                    repeat((offset+month.lengthOfMonth()+6)/7) { week -> Row {
                        repeat(7) { weekday ->
                            val number=week*7+weekday-offset+1
                            Box(Modifier.weight(1f).height(48.dp),contentAlignment=Alignment.Center) {
                                if(number in 1..month.lengthOfMonth()) Surface(
                                    modifier=Modifier.size(38.dp).semantics {contentDescription="${date.year}年${date.monthValue}月${number}日"}.clickable {selected=month.atDay(number).toString()},
                                    shape=CircleShape,
                                    color=if(number==date.dayOfMonth) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    border=if(month.atDay(number)==LocalDate.now() && number!=date.dayOfMonth) BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant) else null,
                                    contentColor=if(number==date.dayOfMonth) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ) { Box(contentAlignment=Alignment.Center) {Text(number.toString(),modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,fontSize=14.sp,lineHeight=18.sp,maxLines=1,softWrap=false)} }
                            }
                        }
                    } }
                }
            }
        }
    }, confirmButton={TextButton(onClick={confirm(selected)}) {Text("确定")}}, dismissButton={TextButton(onClick=dismiss) {Text("取消")}})
}
