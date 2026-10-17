package cn.renewboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Animate only the current page; navigation, saved state and back handlers stay immediate. */
@Composable internal fun Modifier.pageTransition(pageKey: Any?): Modifier {
    val opacity = remember(pageKey) { Animatable(.72f) }
    LaunchedEffect(opacity) { opacity.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
    return graphicsLayer { alpha = opacity.value }
}
