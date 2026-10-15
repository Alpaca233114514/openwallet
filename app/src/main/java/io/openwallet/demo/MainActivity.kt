package io.openwallet.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.openwallet.demo.ui.WalletApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WalletTheme { WalletApp() } }
    }
}

@Composable fun WalletTheme(content: @Composable () -> Unit) {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF216B52), onPrimary = Color.White,
                primaryContainer = Color(0xFFD8EDE1), onPrimaryContainer = Color(0xFF164D3B),
                secondary = Color(0xFF557366), secondaryContainer = Color(0xFFD8EDE1), onSecondaryContainer = Color(0xFF164D3B), background = Color(0xFFF5F7F5),
                surface = Color(0xFFF5F7F5), surfaceContainer = Color(0xFFEBF0EC),
                onSurface = Color(0xFF1B3028), onSurfaceVariant = Color(0xFF69776F), outlineVariant = Color(0xFFDFE6E0)
            ), content = content)
}
