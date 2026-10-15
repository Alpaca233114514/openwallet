package io.openwallet.demo.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingNavigationBar(
    selectedTab: Int,
    onTab: (Int) -> Unit,
    backdrop: GraphicsLayer,
    backdropOrigin: Offset,
) {
    val shape = RoundedCornerShape(28.dp)
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val glass = rememberGraphicsLayer()
    val blurRadius = with(LocalDensity.current) { 22.dp.toPx() }
    val blurEffect = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val backdropColor = MaterialTheme.colorScheme.background
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .shadow(18.dp, shape, clip = false, ambientColor = Color(0xFF164E3F), spotColor = Color(0xFF164E3F))
            .clip(shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .35f))), shape)) {
            // Blur only a replay of the page behind the bar, keeping icons and labels sharp.
            // RenderEffect blur is available on Android 12+; older devices use a denser translucent surface.
            if (supportsBlur) Box(Modifier.matchParentSize()
                .drawWithContent {
                    val offset = backdropOrigin - origin
                    glass.renderEffect = blurEffect
                    glass.clip = true
                    glass.record {
                        drawRect(backdropColor)
                        translate(offset.x, offset.y) { drawLayer(backdrop) }
                    }
                    drawLayer(glass)
                })
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (supportsBlur) .76f else .95f),
                MaterialTheme.colorScheme.surface.copy(alpha = if (supportsBlur) .60f else .92f),
            ))))
            NavigationBar(
                modifier = Modifier.height(72.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { onTab(0) },
                    icon = { Icon(Icons.Rounded.AccountBalanceWallet, null) }, label = { Text("资产") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { onTab(1) },
                    icon = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null) }, label = { Text("收支") })
            }
        }
    }
}
