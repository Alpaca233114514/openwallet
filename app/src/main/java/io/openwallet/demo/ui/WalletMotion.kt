package io.openwallet.demo.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Material 3 duration/easing tokens and Android full-screen predictive-back motion. */
internal object WalletMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val PredictiveBack = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
    const val Duration = 300
    private const val FadeOutDuration = 105 // Fade-through boundary: 35% of 300 ms.

    fun fadeThrough(back: Boolean = false): ContentTransform {
        val enter = fadeIn(tween(Duration - FadeOutDuration, FadeOutDuration, Standard)) +
            scaleIn(tween(Duration, easing = if (back) PredictiveBack else EmphasizedDecelerate),
                initialScale = if (back) 1.1f else 0.92f)
        val exit = fadeOut(tween(FadeOutDuration, easing = EmphasizedAccelerate)) +
            if (back) scaleOut(tween(Duration, easing = PredictiveBack), targetScale = 0.9f)
            else ExitTransition.None
        return ContentTransform(enter, exit, sizeTransform = null)
    }
}

/** Both destinations use the same seekable transition for gestures and ordinary back. */
@Composable
internal fun WalletNavigation(
    settingsOpen: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    val state = remember { SeekableTransitionState(settingsOpen) }
    val transition = rememberTransition(state, label = "wallet-navigation")
    val savedState = rememberSaveableStateHolder()
    val currentOnBack by rememberUpdatedState(onBack)
    var handlingBack by remember { mutableStateOf(false) }

    LaunchedEffect(settingsOpen) { state.animateTo(settingsOpen) }
    // Keep registration order stable. At the ledger root the OS owns back-to-home.
    PredictiveBackHandler(enabled = settingsOpen && backEnabled) { events ->
        if (handlingBack) {
            events.collect { }
            return@PredictiveBackHandler
        }
        handlingBack = true
        try {
            // A gesture may begin while the entrance animation is still running.
            state.snapTo(true)
            events.collect { state.seekTo(it.progress.coerceIn(0f, 1f), false) }
            state.animateTo(false)
            currentOnBack()
        } catch (cancelled: CancellationException) {
            // Cancellation must restore the screen, without popping navigation or losing UI state.
            withContext(NonCancellable) { state.animateTo(true) }
            throw cancelled
        } finally {
            handlingBack = false
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { WalletMotion.fadeThrough(back = initialState && !targetState) },
        ) { showSettings ->
            savedState.SaveableStateProvider(showSettings) { content(showSettings) }
        }
    }
}

@Composable
internal fun WalletTabContent(tab: Int, content: @Composable (Int) -> Unit) {
    val savedState = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = tab,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { WalletMotion.fadeThrough() },
        label = "wallet-tabs",
    ) { selected ->
        savedState.SaveableStateProvider(selected) { content(selected) }
    }
}
