package io.embodit.figuremodel.ui.fm.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.FmUiState
import io.embodit.figuremodel.ui.fm.debug.LocalProbePanel
import io.embodit.figuremodel.ui.fm.scene.FmScene
import io.embodit.figuremodel.ui.fm.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popup (design): the control panel stands; the More menu and the blocked
 * dialog pop up. No WindowContainer, no planar window.
 */
@Composable
internal fun FmStage(state: FmUiState, scene: FmScene, onEvent: (FmEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONTROL) {
                PoolPanel(PanelSpec.CONTROL, scene, refresh = listOf(state.language, state.phase)) { inView -> ControlPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, refresh = listOf(state.language, state.menuOpen)) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene, refresh = state.blockReason) { inView -> BlockedDialog(reason = if (inView) state.blockReason else null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled panel must draw
 * its content only then (what it draws while parked out of sight stays broken on the emulator). On the idle
 * emulator a panel can keep a half-recorded first frame (glass cut off, black below): the content is composed
 * afresh 1.5, 4 and 8 s after the panel comes into view or [refresh] changes (factory finding on bianzhong).
 */
@Composable
private fun PoolPanel(id: String, scene: FmScene, refresh: Any? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(inView, refresh) {
        if (!inView) return@LaunchedEffect
        for (wait in REFRESH_STEPS_MS) {
            delay(wait)
            gen++
        }
    }
    CompositionLocalProvider(LocalProbePanel provides id) { key(gen) { content(inView) } }
}

private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L)
