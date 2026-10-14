package cn.renewboard

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlatformConnectionScreen(p:Plan,close:()->Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as RenewApp
    val connected=remember(p.id) {runCatching {app.platformConnections.connection(p.id)}.getOrNull()!=null}
    // An unverified key stays in memory only, never in saved state or form drafts.
    var apiKey by remember(p.id) {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var request by remember {mutableStateOf<Job?>(null)}
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun leave() {request?.cancel();apiKey="";close()}
    fun connect() {
        if(busy || apiKey.isBlank()) return
        busy=true;error=null;focus.clearFocus(force=true);keyboard?.hide()
        val value=apiKey.trim()
        request=scope.launch {
            try {app.platformBalances.connect(p.id,value);apiKey="";close()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=platformBalanceError(e);busy=false}
        }
    }
    BackHandler {leave()}
    Scaffold(modifier=Modifier.imePadding(),contentWindowInsets=WindowInsets(0,0,0,0),topBar={
        TopAppBar(windowInsets=WindowInsets(0,0,0,0),title={Text(if(connected) "更换密钥" else "连接平台")},navigationIcon={PageBack("返回余额账户",::leave)})
    },bottomBar={
        Surface {Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=8.dp))}
            Button(::connect,enabled=!busy && apiKey.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(if(busy) "正在查询余额…" else if(connected) "验证并更换" else "连接并查询余额")
            }
        }}
    }) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(p.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ServiceIcon("DeepSeek API",size=40.dp)
                Column {Text("DeepSeek",style=MaterialTheme.typography.titleMedium);Text("查询 ${p.currency} 可用余额",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Field("API 密钥",apiKey,{if(!busy) {apiKey=it;error=null}},secret=true)
            TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://platform.deepseek.com/api_keys")))}) {Text("去官网获取密钥")}
            Text("密钥仅在本机加密保存，不进入账本备份。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun platformBalanceError(error:Exception)=if(error is PlatformBalanceException) error.message ?: "余额操作失败，请重试" else "余额操作失败，请重试"
