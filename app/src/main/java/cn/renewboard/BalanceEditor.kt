package cn.renewboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

@Serializable internal enum class BalanceAction(val title:String,val saveLabel:String) {
    TOP_UP("记录话费充值","确认充值"), CALIBRATE("校准余额","确认校准"), MONTHLY_FEE("修改月费","保存月费")
}

@Serializable internal data class BalanceDraft(
    val planId:String, val action:BalanceAction, val amount:String="", val date:String,
    val historyAffectsBalance:Boolean?=null, val recordExpense:Boolean=false,
    val feeTiming:String="下月", val feeMonth:String
)

internal fun balanceDraftKey(planId:String,action:BalanceAction)="balance:$planId:${action.name}"
private fun balanceMoney(value:BigDecimal)="¥${value.setScale(2,RoundingMode.HALF_UP)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BalanceEditor(
    l:Ledger,p:Plan,action:BalanceAction,close:()->Unit,save:suspend ((Ledger)->Ledger)->Unit
) {
    val context=LocalContext.current
    val store=remember {DraftStore(context)}
    val draftKey=balanceDraftKey(p.id,action)
    val today=LocalDate.now()
    val defaults=remember(draftKey) {
        BalanceDraft(p.id,action,amount=if(action==BalanceAction.MONTHLY_FEE) p.balanceAccount?.pendingFee?.amount ?: p.amount else "",
            date=today.toString(),feeTiming=if(action==BalanceAction.MONTHLY_FEE && p.balanceAccount?.pendingFee!=null) "指定月份" else "下月",
            feeMonth=p.balanceAccount?.pendingFee?.effectiveDate?.take(7) ?: YearMonth.from(today).plusMonths(1).toString())
    }
    val restored=remember(draftKey) {
        runCatching {store.read(draftKey)?.let {Book.json.decodeFromString<BalanceDraft>(it)}}.getOrNull()
            ?.takeIf {it.planId==p.id && it.action==action}
    }
    var draft by remember(draftKey) {mutableStateOf(restored ?: defaults)}
    var recovered by remember(draftKey) {mutableStateOf(restored!=null)}
    var showExit by remember(draftKey) {mutableStateOf(false)}
    var showExplanation by remember(draftKey) {mutableStateOf(false)}
    var choosingMonth by remember(draftKey) {mutableStateOf(false)}
    var saving by remember(draftKey) {mutableStateOf(false)}
    var error by remember(draftKey) {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun update(next:BalanceDraft) {
        if(saving) return
        draft=next;error=null
        if(next==defaults) {store.remove(draftKey);recovered=false}
        else store.write(draftKey,Book.json.encodeToString(next))
    }
    fun leave() {if(!saving) {if(draft!=defaults || recovered) showExit=true else close()}}
    fun updated(old:Ledger,entry:BalanceDraft):Ledger = when(action) {
        BalanceAction.TOP_UP -> {
            val day=LocalDate.parse(entry.date)
            require(day<=today) {"充值日期不能晚于今天"}
            val account=requireNotNull(old.plans.single {it.id==p.id}.balanceAccount)
            val historical=day<LocalDate.parse(account.asOf)
            val affects=if(historical) requireNotNull(entry.historyAffectsBalance) {"请选择这笔充值是否已包含在当前余额中"} else true
            Prepaid.topUp(old,p.id,entry.amount,day,affects)
        }
        BalanceAction.CALIBRATE -> Prepaid.calibrate(old,p.id,entry.amount,today,entry.recordExpense)
        BalanceAction.MONTHLY_FEE -> {
            val effective=when(entry.feeTiming) {
                "立即" -> today
                "下月" -> YearMonth.from(today).plusMonths(1).atDay(1)
                else -> YearMonth.parse(entry.feeMonth).atDay(1)
            }
            Prepaid.changeMonthlyFee(old,p.id,entry.amount,effective,today)
        }
    }
    fun commit(cancelPending:Boolean=false) {
        if(saving) return
        try {
            val submitted=draft
            val transform:(Ledger)->Ledger=if(cancelPending) ({Prepaid.cancelMonthlyFeeChange(it,p.id,today)}) else ({updated(it,submitted)})
            transform(l)
            saving=true;error=null
            scope.launch {
                try {save(transform);store.remove(draftKey);close()}
                catch(e:Exception) {error=e.message ?: "保存失败，请重试";saving=false}
            }
        } catch(e:Exception) {
            error=if(e is java.time.format.DateTimeParseException) "请检查日期或生效月份" else e.message ?: "请检查输入"
        }
    }
    val historical=runCatching {LocalDate.parse(draft.date)<LocalDate.parse(p.balanceAccount!!.asOf)}.getOrDefault(false)
    val preview=if(action==BalanceAction.TOP_UP) runCatching {
        val next=updated(l,draft).plans.single {it.id==p.id}
        balanceMoney(Prepaid.balance(next,today))
    }.getOrNull() else null
    BackHandler {leave()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(action.title,fontWeight=FontWeight.SemiBold)},navigationIcon={PageBack("返回话费详情",::leave)})
    },bottomBar={
        Surface(shadowElevation=4.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
                Button(onClick={commit()},enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(saving) "正在保存…" else action.saveLabel)
                }
            }
        }
    }) {padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(p.name,style=MaterialTheme.typography.titleLarge)
            if(recovered) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("已恢复${action.title}草稿",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={update(defaults);recovered=false},enabled=!saving) {Text("丢弃草稿")}
            }
            when(action) {
                BalanceAction.TOP_UP -> {
                    MoneyField("充值金额",draft.amount,{update(draft.copy(amount=it))})
                    Field("充值日期",draft.date,{update(draft.copy(date=it,historyAffectsBalance=null))},dateField=true)
                    if(historical) {
                        Text("这笔充值是否已包含在当前余额中？")
                        BalanceChoice("已包含，仅补记充值记录",draft.historyAffectsBalance==false,!saving) {update(draft.copy(historyAffectsBalance=false))}
                        BalanceChoice("未包含，同时增加余额",draft.historyAffectsBalance==true,!saving) {update(draft.copy(historyAffectsBalance=true))}
                    }
                    preview?.let {Text("充值后估算余额 $it",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)}
                }
                BalanceAction.CALIBRATE -> {
                    Text("今天的估算余额 ${balanceMoney(Prepaid.balance(p,today))}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyField("实际余额",draft.amount,{update(draft.copy(amount=it,recordExpense=false))})
                    val difference=runCatching {Prepaid.balance(p,today)-BigDecimal(draft.amount)}.getOrNull()
                    BalanceChoice("仅校正余额",!draft.recordExpense,!saving) {update(draft.copy(recordExpense=false))}
                    if(difference!=null && difference.signum()>0) BalanceChoice("差额 ${balanceMoney(difference)} 记为额外支出",draft.recordExpense,!saving) {update(draft.copy(recordExpense=true))}
                }
                BalanceAction.MONTHLY_FEE -> {
                    MoneyField("新月费",draft.amount,{update(draft.copy(amount=it))})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("立即","下月","指定月份").forEach {timing ->
                            FilterChip(selected=draft.feeTiming==timing,onClick={update(draft.copy(feeTiming=timing))},enabled=!saving,label={Text(timing)})
                        }
                    }
                    if(draft.feeTiming=="指定月份") OutlinedButton(onClick={choosingMonth=true},enabled=!saving,modifier=Modifier.fillMaxWidth().semantics {contentDescription="选择生效月份"}) {
                        Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
                        val month=YearMonth.parse(draft.feeMonth)
                        Text("${month.year}年${month.monthValue}月起生效")
                    }
                    if(draft.feeTiming=="立即") Text("从今天起，已记录扣费不变。",style=MaterialTheme.typography.bodySmall)
                    if(p.balanceAccount?.pendingFee!=null) TextButton(onClick={commit(cancelPending=true)},enabled=!saving) {Text("取消待生效调整")}
                }
            }
            TextButton(onClick={showExplanation=!showExplanation},enabled=!saving) {Text(if(showExplanation) "收起说明" else "查看说明")}
            if(showExplanation) Text(when(action) {
                BalanceAction.TOP_UP -> "余额与月费推算同步更新，充值不计支出，月费扣除时计入账本。早于余额日期的充值，需要确认是否已包含在当前余额中。"
                BalanceAction.CALIBRATE -> "按今天实际查询到的余额校准。仅校正余额不会新增支出；实际余额低于估算余额时，可将差额记为额外支出。"
                BalanceAction.MONTHLY_FEE -> "月费按每月扣费日从余额中扣除。立即调整不改变已记录扣费；选择下月或指定月份，将在该月起使用新月费。"
            },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(choosingMonth) MonthPickerDialog(draft.feeMonth,
        minimum=YearMonth.from(today).let {if(today.dayOfMonth==1) it else it.plusMonths(1)},
        dismiss={choosingMonth=false},confirm={update(draft.copy(feeMonth=it));choosingMonth=false})
    if(showExit) AlertDialog(onDismissRequest={showExit=false},title={Text("保留${action.title}草稿？")},
        confirmButton={TextButton(onClick={store.write(draftKey,Book.json.encodeToString(draft));showExit=false;close()}) {Text("保留草稿")}},
        dismissButton={TextButton(onClick={store.remove(draftKey);showExit=false;close()}) {Text("放弃修改")}})
}

@Composable private fun BalanceChoice(text:String,selected:Boolean,enabled:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected,null,enabled=enabled);Text(text)
    }
}
