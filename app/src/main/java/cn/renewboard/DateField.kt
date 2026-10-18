package cn.renewboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Keep the stored/editable ISO value; present a readable date when the field is at rest. */
@Composable internal fun DateField(label:String,value:String,change:(String)->Unit,modifier:Modifier=Modifier,
    keyboardType:KeyboardType=KeyboardType.Text,supportingText:String?=null) {
    var choosing by remember {mutableStateOf(false)}
    var focused by remember {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val shape=RoundedCornerShape(14.dp)
    TextField(value,change,label={Text(label,fontSize=12.sp)},singleLine=true,
        modifier=modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused}
            .border(1.dp,if(focused) MaterialTheme.colorScheme.primary.copy(alpha=.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f),shape),
        shape=shape,textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,fontWeight=FontWeight.Medium),
        placeholder={Text("YYYY-MM-DD",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)},
        keyboardOptions=KeyboardOptions(keyboardType=keyboardType),
        visualTransformation=if(focused) VisualTransformation.None else ReadableDate,
        supportingText=if(supportingText!=null) {{Text(supportingText)}} else null,
        colors=TextFieldDefaults.colors(
            focusedContainerColor=Color.White,unfocusedContainerColor=Color.White,
            focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,
            focusedLabelColor=MaterialTheme.colorScheme.primary,unfocusedLabelColor=MaterialTheme.colorScheme.onSurfaceVariant),
        trailingIcon={IconButton(onClick={focus.clearFocus();keyboard?.hide();choosing=true}) {
            Icon(Icons.Outlined.CalendarMonth,"选择日期",Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
        }})
    if(choosing) CalendarDateDialog(label,value,{choosing=false}) {change(it);choosing=false}
}

private object ReadableDate:VisualTransformation {
    override fun filter(text:AnnotatedString):TransformedText {
        val raw=text.text
        if(!raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || runCatching {LocalDate.parse(raw)}.isFailure)
            return TransformedText(text,OffsetMapping.Identity)
        val display=raw.substring(0,4)+"年"+raw.substring(5,7)+"月"+raw.substring(8,10)+"日"
        return TransformedText(AnnotatedString(display),object:OffsetMapping {
            override fun originalToTransformed(offset:Int)=offset
            override fun transformedToOriginal(offset:Int)=offset.coerceAtMost(raw.length)
        })
    }
}
