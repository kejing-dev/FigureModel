package io.embodit.figuremodel.ui.fm

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.figuremodel.platform.FmLog
import io.embodit.figuremodel.ui.fm.components.FmStage
import io.embodit.figuremodel.ui.fm.components.LocalFmResources
import io.embodit.figuremodel.ui.fm.components.localizedResources
import io.embodit.figuremodel.ui.fm.debug.FmProbe
import io.embodit.figuremodel.ui.fm.scene.FmFrameSystem
import io.embodit.figuremodel.ui.fm.scene.FmScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the ViewModel holds
 * the state, the [FmScene] turns state into entities and reports taps, drags, the stick, the dwell disc, the
 * floor and the head, and [FmStage] declares the panels.
 */
@Composable
fun FmScreen() {
    val context = LocalContext.current
    val viewModel: FmViewModel = viewModel(factory = FmViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        FmProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { FmScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<FmFrameSystem>()
        FmProbe.host =
            object : FmProbe.Host {
                override fun dispatch(event: FmEvent) = viewModel.onEvent(event)

                override fun uiState(): FmUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): FmProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = FmProbe.click(name)
            }
        FmLog.i("STAGE enter")
        onDispose {
            FmProbe.host = null
            unregisterSystem<FmFrameSystem>()
            scene.detach()
            FmLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            scene.onBackground()
                            viewModel.onEvent(FmEvent.WentBackground)
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            viewModel.onEvent(FmEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: FmEffect ->
            when (effect) {
                FmEffect.ExitApp -> {
                    FmLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { FmLog.e("STAGE close failed: $it", it) }
                }
                is FmEffect.Sound ->
                    runCatching {
                        // system sounds stand in until CC0 sounds are added (design `rules.audio`)
                        when (effect.kind) {
                            SoundKind.TICK -> audio.playSystem(SpatialSoundEffect.OpClick)
                            SoundKind.CHANGE -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.SNAP -> audio.playSystem(SpatialSoundEffect.OpDragEnd)
                            SoundKind.FAIL -> audio.playSystem(SpatialSoundEffect.StateFailure)
                        }
                    }.onFailure { FmLog.w("SOUND failed: $it") }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalFmResources provides resources) {
        FmStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
