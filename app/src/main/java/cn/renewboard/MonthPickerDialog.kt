package cn.renewboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.YearMonth

@Composable internal fun MonthPickerDialog(value:String,minimum:YearMonth,dismiss:()->Unit,confirm:(String)->Unit) {
    val maximum=YearMonth.of(2200,12)
    val initial=runCatching {YearMonth.parse(value)}.getOrDefault(minimum).coerceIn(minimum,maximum)
    var selected by rememberSaveable {mutableStateOf(initial.toString())}
    var years by rememberSaveable {mutableStateOf(false)}
    val month=YearMonth.parse(selected)
    fun setYear(year:Int) {selected=YearMonth.of(year,month.monthValue).coerceIn(minimum,maximum).toString();years=false}
    AlertDialog(onDismissRequest=dismiss,title={Text("选择生效月份")},text={Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            IconButton(onClick={setYear(month.year-1)},enabled=month.year>minimum.year) {Icon(Icons.Outlined.ChevronLeft,"上一年")}
            OutlinedButton(onClick={years=!years},modifier=Modifier.semantics {contentDescription="选择生效年份"}) {
                Text("${month.year} 年");Icon(Icons.Outlined.ExpandMore,null)
            }
            IconButton(onClick={setYear(month.year+1)},enabled=month.year<maximum.year) {Icon(Icons.Outlined.ChevronRight,"下一年")}
        }
        if(years) {
            val state=rememberLazyGridState(initialFirstVisibleItemIndex=((month.year-minimum.year)/3)*3)
            LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(240.dp),state=state) {
                items((minimum.year..maximum.year).toList()) {year->TextButton(onClick={setYear(year)},modifier=Modifier.heightIn(min=48.dp)) {Text("$year 年")}}
            }
        } else LazyVerticalGrid(GridCells.Fixed(3),Modifier.height(240.dp)) {
            items((1..12).toList()) {number->
                val candidate=YearMonth.of(month.year,number)
                TextButton(onClick={selected=candidate.toString()},enabled=candidate>=minimum,
                    colors=ButtonDefaults.textButtonColors(containerColor=if(candidate==month) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier=Modifier.padding(4.dp).heightIn(min=48.dp).semantics {contentDescription="生效月份 ${month.year}年${number}月"}) {Text("$number 月")}
            }
        }
        Text("${month.year}年${month.monthValue}月起生效",modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp))
    }},confirmButton={TextButton(onClick={confirm(selected)}) {Text("确定")}},dismissButton={TextButton(onClick=dismiss) {Text("取消")}})
}
