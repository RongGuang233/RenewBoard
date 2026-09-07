package cn.renewboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        val target = YearMonth.of(year, month)
        selected = target.atDay(date.dayOfMonth.coerceAtMost(target.lengthOfMonth())).toString()
        panel = "day"
    }
    AlertDialog(onDismissRequest=dismiss, title={ Text("选择$label") }, text={
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={panel="year"},modifier=Modifier.weight(1f).semantics {contentDescription="选择年份"},contentPadding=PaddingValues(8.dp)) {
                    Text("${date.year} 年"); Icon(Icons.Outlined.ExpandMore,null)
                }
                OutlinedButton(onClick={panel="month"},modifier=Modifier.weight(1f).semantics {contentDescription="选择月份"},contentPadding=PaddingValues(8.dp)) {
                    Text("${date.monthValue} 月"); Icon(Icons.Outlined.ExpandMore,null)
                }
            }
            Spacer(Modifier.height(12.dp))
            when(panel) {
                "year" -> {
                    val state = rememberLazyGridState(initialFirstVisibleItemIndex=((date.year-1900)/3)*3)
                    LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(294.dp),state=state) {
                        items((1900..2200).toList()) { year ->
                            TextButton(onClick={setMonth(year,date.monthValue)},modifier=Modifier.height(48.dp)) {Text("$year 年",fontWeight=if(year==date.year) FontWeight.Bold else FontWeight.Normal)}
                        }
                    }
                }
                "month" -> LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(294.dp)) {
                    items((1..12).toList()) { number ->
                        TextButton(onClick={setMonth(date.year,number)},modifier=Modifier.height(64.dp)) {Text("$number 月",fontWeight=if(number==date.monthValue) FontWeight.Bold else FontWeight.Normal)}
                    }
                }
                else -> Column(Modifier.height(294.dp)) {
                    Row { listOf("一","二","三","四","五","六","日").forEach { day ->
                        Box(Modifier.weight(1f).height(36.dp),contentAlignment=Alignment.Center) {Text(day,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    } }
                    val offset = month.atDay(1).dayOfWeek.value-1
                    repeat(6) { week -> Row {
                        repeat(7) { weekday ->
                            val number=week*7+weekday-offset+1
                            Box(Modifier.weight(1f).height(42.dp),contentAlignment=Alignment.Center) {
                                if(number in 1..month.lengthOfMonth()) Surface(
                                    modifier=Modifier.size(38.dp).semantics {contentDescription="${date.year}年${date.monthValue}月${number}日"}.clickable {selected=month.atDay(number).toString()},
                                    shape=CircleShape,
                                    color=if(number==date.dayOfMonth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor=if(number==date.dayOfMonth) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ) { Box(contentAlignment=Alignment.Center) {Text(number.toString())} }
                            }
                        }
                    } }
                }
            }
        }
    }, confirmButton={TextButton(onClick={confirm(selected)}) {Text("确定")}}, dismissButton={TextButton(onClick=dismiss) {Text("取消")}})
}
