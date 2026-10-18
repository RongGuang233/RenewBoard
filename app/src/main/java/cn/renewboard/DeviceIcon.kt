package cn.renewboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceIcon(
    category: DeviceCategory,
    name: String = "",
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val foreground = MaterialTheme.colorScheme.primary
    val background = foreground.copy(alpha = .05f)
    val normalizedName = name.lowercase()
    val earbuds = category == DeviceCategory.HEADPHONES &&
        !normalizedName.contains("airpods max") &&
        !normalizedName.contains("头戴") &&
        listOf("airpods", "earbuds", "buds", "耳塞", "入耳").any(normalizedName::contains)
    Box(
        modifier.size(size).background(background, RoundedCornerShape(size * .28f)),
        contentAlignment = Alignment.Center
    ) {
        if (earbuds) {
            EarbudsIcon(foreground, MaterialTheme.colorScheme.surface, Modifier.size(size * .68f))
        } else {
            Icon(
                deviceCategoryIcon(category, normalizedName),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(size * .64f)
            )
        }
    }
}

private fun deviceCategoryIcon(category: DeviceCategory, name: String): ImageVector = when (category) {
    DeviceCategory.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceCategory.COMPUTER -> if (listOf("台式", "desktop", "imac", "mac mini", "mac studio").any(name::contains))
        Icons.Outlined.DesktopWindows else Icons.Outlined.Laptop
    DeviceCategory.TABLET -> Icons.Outlined.TabletAndroid
    DeviceCategory.HEADPHONES -> Icons.Outlined.Headphones
    DeviceCategory.MONITOR -> Icons.Outlined.Monitor
    DeviceCategory.WATCH -> Icons.Outlined.Watch
    DeviceCategory.KEYBOARD -> Icons.Outlined.Keyboard
    DeviceCategory.MOUSE -> Icons.Outlined.Mouse
    DeviceCategory.CONTROLLER -> Icons.Outlined.SportsEsports
    DeviceCategory.EREADER -> Icons.Outlined.MenuBook
    DeviceCategory.CHAIR -> Icons.Outlined.Chair
    DeviceCategory.ROUTER -> Icons.Outlined.Router
    DeviceCategory.AUDIO -> Icons.Outlined.Speaker
    DeviceCategory.CAMERA -> Icons.Outlined.PhotoCamera
    DeviceCategory.GAMING -> Icons.Outlined.VideogameAsset
    DeviceCategory.OTHER -> Icons.Outlined.Devices
}

@Composable
private fun EarbudsIcon(foreground: Color, aperture: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // Two rounded earpieces with stems remain recognizable at small sizes.
            drawRoundRect(foreground, Offset(5f, 8f), Size(3.2f, 12f), CornerRadius(1.6f))
            drawRoundRect(foreground, Offset(15.8f, 8f), Size(3.2f, 12f), CornerRadius(1.6f))
            drawRoundRect(foreground, Offset(2f, 3f), Size(8f, 8f), CornerRadius(3.8f))
            drawRoundRect(foreground, Offset(14f, 3f), Size(8f, 8f), CornerRadius(3.8f))
            drawRoundRect(aperture, Offset(3.6f, 5.6f), Size(1.6f, 3f), CornerRadius(.8f))
            drawRoundRect(aperture, Offset(18.8f, 5.6f), Size(1.6f, 3f), CornerRadius(.8f))
        }
    }
}
