package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun forecastYuan(value:BigDecimal)="¥${value.setScale(2,RoundingMode.HALF_UP).toPlainString()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ForecastScreen(l:Ledger,close:()->Unit,openPlan:(String)->Unit) {
    val today=LocalDate.now()
    val until=today.plusDays(30)
    val charges=Book.forecastCharges(l,today,until)
    val known=charges.fold(BigDecimal.ZERO) {total,charge -> total+(charge.cnyAmount ?: BigDecimal.ZERO)}
    val missing=charges.count {it.cnyAmount==null}
    BackHandler(onBack=close)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text("未来30天扣款",fontWeight=FontWeight.SemiBold)},
            navigationIcon={Box(Modifier.padding(start=8.dp)) {PageBack("返回首页",close)}})
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp)) {
            item {
                Column(Modifier.padding(top=12.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(forecastYuan(known),fontSize=34.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text("预计扣款 ${charges.size} 笔 · ${today.format(DateTimeFormatter.ofPattern("M月d日"))}—${until.minusDays(1).format(DateTimeFormatter.ofPattern("M月d日"))}",
                        fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(missing>0) Text("已知金额，另有 $missing 笔待补录人民币金额",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(charges.isEmpty()) item {
                Text("未来30天暂无预计扣款",modifier=Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            charges.groupBy {it.date}.forEach { (date,dayCharges) ->
                item(key="date-$date") {
                    Text(if(date==today) "今天 · $date" else date.toString(),fontSize=13.sp,fontWeight=FontWeight.SemiBold,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp,bottom=6.dp))
                }
                items(dayCharges,key={"${it.planId}:${it.date}"}) { charge ->
                    Card(onClick={openPlan(charge.planId)},modifier=Modifier.fillMaxWidth().padding(bottom=6.dp),
                        shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ServiceIcon(charge.planName,size=32.dp)
                            Text(charge.planName,modifier=Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(charge.cnyAmount?.let(::forecastYuan) ?: "${charge.currency} ${charge.amount.stripTrailingZeros().toPlainString()}",fontWeight=FontWeight.SemiBold)
                                if(charge.cnyAmount==null) Text("待补人民币金额",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
