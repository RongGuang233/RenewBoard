package cn.renewboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex

/** One drag session shared by category headers and rows; rows stay in their category. */
internal class AssetReorderState {
    val bounds=mutableStateMapOf<String,Rect>()
    val groups=mutableMapOf<String,String>()
    var dragging by mutableStateOf<String?>(null)
    var pointerY by mutableFloatStateOf(0f)
    private var grabOffset=0f
    var onMove:(String,String)->Unit={_,_->}
    fun start(key:String,y:Float) {
        val rect=bounds[key] ?: return
        dragging=key;grabOffset=y;pointerY=rect.top+y
    }
    fun drag(delta:Float) {pointerY+=delta;moveToward(delta)}
    fun moveToward(direction:Float) {
        val key=dragging ?: return
        val current=bounds[key] ?: return
        val target=bounds.entries.firstOrNull {(id,rect)->
            id!=key && groups[id]==groups[key] && pointerY>=rect.top && pointerY<=rect.bottom &&
                ((direction>0 && rect.top>current.top) || (direction<0 && rect.top<current.top))
        }?.key ?: return
        onMove(key,target)
    }
    fun translation(key:String):Float=if(dragging==key) pointerY-grabOffset-(bounds[key]?.top ?: 0f) else 0f
    fun stop() {dragging=null}
}

@Composable internal fun AssetReorderItem(
    state:AssetReorderState,key:String,group:String,content:@Composable (Modifier)->Unit
) {
    val haptic=LocalHapticFeedback.current
    DisposableEffect(state,key,group) {
        state.groups[key]=group
        onDispose {state.bounds.remove(key);state.groups.remove(key);if(state.dragging==key) state.stop()}
    }
    Box(Modifier.fillMaxWidth().zIndex(if(state.dragging==key) 1f else 0f)
        .onGloballyPositioned {state.bounds[key]=Rect(it.positionInRoot(),Size(it.size.width.toFloat(),it.size.height.toFloat()))}) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationY=state.translation(key)
            shadowElevation=if(state.dragging==key) 6f else 0f
        }.background(if(state.dragging==key) Color.White else Color.Transparent,RoundedCornerShape(12.dp))) {
            content(Modifier.pointerInput(state,key) {
                detectDragGesturesAfterLongPress(
                    onDragStart={state.start(key,it.y);haptic.performHapticFeedback(HapticFeedbackType.LongPress)},
                    onDragEnd=state::stop,onDragCancel=state::stop,
                    onDrag={change,amount->change.consume();state.drag(amount.y)}
                )
            })
        }
    }
}
