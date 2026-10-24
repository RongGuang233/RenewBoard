package cn.renewboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

// The app has a light palette; white cards separate groups from its existing cool gray canvas.
@Composable internal fun AssetCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),colors=CardDefaults.cardColors(containerColor=Color.White),content=content)
}

@Composable internal fun AssetSummaryAmount(label:String,amount:BigDecimal,hidden:Boolean,modifier:Modifier=Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(hidden) "••••" else assetMoney(amount),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable internal fun AssetAccountIcon(category:AssetCategory,type:String,name:String="") {
    val resource=(if(type=="现金") R.drawable.asset_cash else assetBrandIcon(type,name)) ?: if(category==AssetCategory.PREPAID) servicePresetFor(name)?.iconRes else null
    if(resource!=null) {
        Image(painterResource(resource),contentDescription=null,modifier=Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        return
    }
    val identity="$type $name"
    val color=when(category) {
        AssetCategory.PREPAID->Color(0xFFCB843F)
        AssetCategory.INVESTMENT->Color(0xFF6B66B2)
        AssetCategory.RECEIVABLE->Color(0xFF368D84)
        AssetCategory.DEBT->Color(0xFFB36B67)
        AssetCategory.TUITION->Color(0xFF697DAB)
        else->Color(0xFF547AC5)
    }
    val icon=when {
        "饭卡" in identity->Icons.Outlined.Restaurant
        "现金" in identity->Icons.Outlined.Payments
        "银行卡" in identity->Icons.Outlined.AccountBalance
        "话费" in identity->Icons.Outlined.PhoneAndroid
        "API" in identity->Icons.Outlined.Code
        else->assetIcon(category)
    }
    Box(Modifier.size(36.dp).background(color,CircleShape),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(21.dp),tint=Color.White)
    }
}

internal fun tuitionBalanceLabel(balance:String,archived:Boolean=false)=when(BigDecimal(balance).signum()) {
    1->if(archived) "剩余学费" else "待上课"; -1->"待收款"; else->"已结清"
}

// Group balances represent actual account balances, not the amount eligible for net worth.
// Different currencies remain separate; never add USD directly to CNY.
internal fun assetGroupBalances(accounts:List<AssetAccount>):String = accounts.groupBy {it.currency}.entries
    .sortedBy {if(it.key=="CNY") "" else it.key}.joinToString("\n") {(currency,items)->
        assetMoney(items.fold(BigDecimal.ZERO) {total,a->total+BigDecimal(a.balance)},currency)
    }

internal data class BorrowingBalances(val borrowed:String,val lent:String)

// This is a view of outstanding loans, independent of each account's net-worth setting.
internal fun assetBorrowingBalances(accounts:List<AssetAccount>):BorrowingBalances {
    fun balance(category:AssetCategory,subtype:String):String = assetGroupBalances(accounts.filter {
        it.category==category && it.subtype==subtype
    }).ifEmpty {assetMoney(BigDecimal.ZERO)}
    return BorrowingBalances(balance(AssetCategory.DEBT,"借入款"),balance(AssetCategory.RECEIVABLE,"借出款"))
}

@Composable internal fun AssetBorrowingSummary(accounts:List<AssetAccount>,hidden:Boolean,onBorrowed:()->Unit,onLent:()->Unit) {
    val balances=assetBorrowingBalances(accounts)
    AssetCard {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
            BorrowingAmount("总借入",balances.borrowed,true,hidden,Modifier.weight(1f).clickable(onClick=onBorrowed))
            VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
            BorrowingAmount("总借出",balances.lent,false,hidden,Modifier.weight(1f).clickable(onClick=onLent))
        }
    }
}

@Composable private fun BorrowingAmount(label:String,amount:String,incoming:Boolean,hidden:Boolean,modifier:Modifier) {
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).border(2.dp,muted.copy(alpha=.65f),RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center) {
            Icon(if(incoming) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,null,Modifier.size(20.dp),tint=muted.copy(alpha=.8f))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.bodyMedium,color=muted)
            Text(if(hidden) "••••" else amount,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable internal fun AssetAccountRow(ledger:Ledger,a:AssetAccount,hidden:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().clickable(onClick=onClick)
        .heightIn(min=64.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        AssetAccountIcon(a.category,a.subtype,a.name)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(a.name,style=MaterialTheme.typography.bodyLarge)
            if(!a.included && a.category!=AssetCategory.PREPAID) Text("不计入",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val value=Assets.value(ledger,a)
            // An excluded API balance need not prompt for an amount that won't enter the statistics.
            val warning=value.pendingReason?.takeUnless {!a.included && it in listOf("待折算","请确认可计入的充值余额")}
            warning?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)}
        }
        Column(horizontalAlignment=Alignment.End,modifier=Modifier.widthIn(max=180.dp)) {
            Text(if(hidden) "••••" else assetMoney(if(a.category==AssetCategory.TUITION) BigDecimal(a.balance).abs() else BigDecimal(a.balance),a.currency),style=MaterialTheme.typography.bodyLarge)
            if(a.category==AssetCategory.TUITION) Text(tuitionBalanceLabel(a.balance,a.archived),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun AssetGroup(ledger:Ledger,category:AssetCategory,accounts:List<AssetAccount>,hidden:Boolean,collapsed:Boolean,onToggle:()->Unit,onAccount:(AssetAccount)->Unit,headerModifier:Modifier=Modifier,accountContent:(@Composable (AssetAccount)->Unit)?=null) {
    val totals=Assets.totals(ledger,accounts)
    val partial=accounts.any {!it.included || (it.included && (Assets.value(ledger,it).cny==null || (it.eligibleAmount!=null && BigDecimal(it.eligibleAmount).compareTo(BigDecimal(it.balance).abs())!=0)))}
    AssetCard {
        Column(headerModifier.fillMaxWidth().clickable(onClick=onToggle).semantics {contentDescription="${if(collapsed) "展开" else "收起"}${category.label}"}.padding(horizontal=16.dp,vertical=14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(category.label,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                Text(if(hidden) "••••" else assetGroupBalances(accounts),fontWeight=FontWeight.SemiBold)
                Icon(if(collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.KeyboardArrowDown,null,Modifier.size(20.dp))
            }
            if(partial && category!=AssetCategory.PREPAID) Text(if(hidden) "其中计入 ••••" else if(category==AssetCategory.DEBT) "其中计入负债 ${assetMoney(totals.liabilities)}" else "其中计入净资产 ${assetMoney(totals.net)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        if(!collapsed) accounts.forEach {a->
            key(a.id) {
                HorizontalDivider(Modifier.padding(start=64.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.3f))
                accountContent?.invoke(a) ?: AssetAccountRow(ledger,a,hidden,onClick={onAccount(a)})
            }
        }
    }
}

@Composable internal fun TuitionOverview(ledger:Ledger,accounts:List<AssetAccount>,hidden:Boolean,onRefresh:()->Unit,onStudents:()->Unit,headerModifier:Modifier=Modifier) {
    val prepaid=accounts.filter {!it.archived && it.verified && BigDecimal(it.balance).signum()>0}.fold(BigDecimal.ZERO) {sum,a->sum+BigDecimal(a.balance)}
    val issues=accounts.count {Assets.value(ledger,it).pendingReason!=null}
    AssetCard {
        Row(headerModifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("学费",fontWeight=FontWeight.SemiBold)
            TextButton(onClick=onStudents) {Icon(Icons.Outlined.PeopleOutline,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("查看学生")}
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onRefresh) {Icon(Icons.Outlined.Sync,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("刷新")}
        }
        Row(Modifier.fillMaxWidth().clickable(onClick=onStudents).heightIn(min=64.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AssetAccountIcon(AssetCategory.TUITION,"待上课")
            Text("待上课",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
            Text(if(hidden) "••••" else assetMoney(prepaid),style=MaterialTheme.typography.bodyLarge)
        }
        if(issues>0) Row(Modifier.padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Info,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.error)
            Text("$issues 个学生账户待核实",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun AssetBankPicker(onSelect:(String)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        AssetCard {
            commonAssetBanks.forEach {bank->
                Row(Modifier.fillMaxWidth().clickable {onSelect(bank)}.heightIn(min=64.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    AssetAccountIcon(AssetCategory.CASH,bank)
                    Text(bank,modifier=Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TextButton(onClick={onSelect("其他银行卡")}) {Text("其他银行卡")}
    }
}

@Composable internal fun AssetTypePicker(includeTuition:Boolean=true,onSelect:(AssetCategory,String)->Unit) {
    val types=listOf(
        AssetCategory.CASH to listOf("银行卡","微信","支付宝","现金","其他"),
        AssetCategory.INVESTMENT to listOf("余额宝","基金","股票","其他"),
        AssetCategory.RECEIVABLE to listOf("借出款","可退押金","其他"),
        AssetCategory.DEBT to listOf("信用卡","花呗","贷款","借入款","其他"),
        AssetCategory.PREPAID to listOf("饭卡","会员卡","其他充值"),
        AssetCategory.TUITION to listOf("导入学费")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        types.filter {includeTuition || it.first!=AssetCategory.TUITION}.forEach {(category,items)->
            Text(category.label,fontWeight=FontWeight.SemiBold)
            AssetCard {
                items.chunked(3).forEach {row->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach {type->
                            Column(Modifier.weight(1f).clickable {onSelect(category,type)}.padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                AssetAccountIcon(category,type);Text(type,style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                        repeat(3-row.size) {Spacer(Modifier.weight(1f))}
                    }
                }
            }
        }
    }
}
