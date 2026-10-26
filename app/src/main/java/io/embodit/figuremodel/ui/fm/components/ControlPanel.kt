package io.embodit.figuremodel.ui.fm.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.figuremodel.R
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.PoseDuration
import io.embodit.figuremodel.domain.model.Poses
import io.embodit.figuremodel.domain.model.StillLife
import io.embodit.figuremodel.ui.fm.Flash
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.FmUiState
import io.embodit.figuremodel.ui.fm.Phase
import io.embodit.figuremodel.ui.fm.debug.probe
import io.embodit.figuremodel.ui.fm.scene.PanelSpec
import io.embodit.figuremodel.ui.theme.FmColors

/**
 * The one standing panel (400 x 268 dp at 0.75, design `panels.controlPanel`): pose name + index chip + More;
 * countdown + duration segments; previous / main / next; a two-line hint led by the stick icon.
 */
@Composable
internal fun ControlPanel(state: FmUiState, filled: Boolean, onEvent: (FmEvent) -> Unit) {
    val (w, h) = PanelSpec.CONTROL_SIZE
    Glass(w.dp, h.dp, corner = 28.dp) {
        if (!filled) return@Glass
        val busy = state.phase == Phase.STARTING || state.phase == Phase.FINDING
        val blocked = state.phase == Phase.BLOCKED
        val controls = state.controlsEnabled && !state.relocating
        val zh = rs(R.string.lang_code) == "zh"
        // row 1: name, chip, more (48)
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val title =
                when {
                    busy || blocked || state.phase == Phase.NO_FLOOR -> rs(R.string.title_starting)
                    state.content == Content.STILL -> rs(R.string.title_still, CIRCLED[state.stillIndex.coerceIn(0, CIRCLED.size - 1)])
                    else -> Poses.all[state.poseIndex].let { if (zh) it.zh else it.en }
                }
            Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = FmType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (state.placed) {
                val count = if (state.content == Content.STILL) "${state.stillPosition}/${StillLife.count}" else "${state.posePosition}/${Poses.count}"
                SmallChip(count)
                if (state.size == FigureSize.DESK && state.content == Content.FIGURE) SmallChip(rs(R.string.desk_size))
            }
            Spacer(modifier = Modifier.weight(1f))
            Key("more", R.drawable.ic_more, rs(R.string.action_more), enabled = !blocked && !busy && !state.relocating) { onEvent(FmEvent.MoreClicked) }
        }
        // row 2: countdown + durations (72)
        Row(modifier = Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.width(110.dp).height(72.dp), contentAlignment = Alignment.CenterStart) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(36.dp))
                } else {
                    val text = if (state.duration == PoseDuration.INF || !state.placed) "—" else clock(state.remainingSeconds)
                    Text(text = text, color = if (state.lastFive) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelPrimary, style = FmType.countdown, maxLines = 1)
                }
            }
            Segments(
                items = listOf("dur_30" to "30 s", "dur_60" to "1 min", "dur_300" to "5 min", "dur_inf" to "∞"),
                selected = state.duration.ordinal,
                onSelect = { onEvent(FmEvent.DurationSelected(PoseDuration.entries[it])) },
                width = 250.dp,
                height = 40.dp,
                enabled = controls,
            )
        }
        // row 3: previous / main / next (56)
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("prev", R.drawable.ic_prev, rs(R.string.action_prev), enabled = controls) { onEvent(FmEvent.PrevClicked) }
            val (label, icon) =
                when {
                    state.relocating -> rs(R.string.action_cancel_move) to R.drawable.ic_close
                    state.phase == Phase.NO_FLOOR -> rs(R.string.action_retry) to R.drawable.ic_retry
                    state.phase == Phase.DRAWING -> rs(R.string.action_pause) to R.drawable.ic_pause
                    state.phase == Phase.PAUSED -> rs(R.string.action_resume) to R.drawable.ic_play
                    else -> rs(R.string.action_start) to R.drawable.ic_play
                }
            val mainEnabled = state.relocating || state.phase == Phase.NO_FLOOR || controls
            MainButton("main", label, icon, mainEnabled, Modifier.weight(1f)) { onEvent(FmEvent.StartPauseClicked) }
            Key("next", R.drawable.ic_next, rs(R.string.action_next), enabled = controls) { onEvent(FmEvent.NextClicked("panel")) }
        }
        // row 4: hint, at most two lines (36)
        HintLine(state)
    }
}

/** ①..⑤ for the still-life title. */
private val CIRCLED = listOf("①", "②", "③", "④", "⑤")

private fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun SmallChip(text: String) {
    Box(modifier = Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = FmType.chip, maxLines = 1, softWrap = false)
    }
}

/** The main button: interaction yellow fill, ink icon and text (design `spatialUiMap.mainButton`). */
@Composable
private fun MainButton(name: String, label: String, iconRes: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = FmColors.Ink
    Button(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 120.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = ink),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = FmType.title)
    }
}

/** Hint line by priority (design `rules.hintPriority`); the 2 s flashes cover it. */
@Composable
private fun HintLine(state: FmUiState) {
    val flash = state.flash
    val (text, color) =
        when {
            flash == Flash.SNAPPED -> rs(R.string.hint_snapped) to PicoTheme.colorScheme.passable
            flash == Flash.BACK_TO_FLOOR -> rs(R.string.hint_back_to_floor) to PicoTheme.colorScheme.labelSecondary
            flash == Flash.FLOOR_ONLY -> rs(R.string.hint_floor_only) to PicoTheme.colorScheme.error
            state.phase == Phase.BLOCKED -> rs(R.string.hint_s16) to PicoTheme.colorScheme.labelSecondary
            state.phase == Phase.NO_FLOOR -> rs(R.string.hint_s15) to PicoTheme.colorScheme.error
            state.phase == Phase.FINDING -> rs(R.string.hint_s2) to PicoTheme.colorScheme.labelSecondary
            state.relocating -> rs(R.string.hint_s18) to PicoTheme.colorScheme.labelSecondary
            state.dragging -> rs(R.string.hint_s8) to PicoTheme.colorScheme.labelSecondary
            state.lighting -> rs(R.string.hint_s10) to PicoTheme.colorScheme.labelSecondary
            state.turning -> rs(R.string.hint_s9) to PicoTheme.colorScheme.interaction
            state.phase == Phase.PAUSED -> rs(R.string.hint_s7) to PicoTheme.colorScheme.labelSecondary
            state.phase == Phase.DRAWING -> rs(R.string.hint_s4) to PicoTheme.colorScheme.labelSecondary
            state.phase == Phase.READY -> rs(R.string.hint_s3) to PicoTheme.colorScheme.labelSecondary
            else -> rs(R.string.hint_s1) to PicoTheme.colorScheme.labelSecondary
        }
    Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painter = painterResource(R.drawable.ic_stick), contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(16.dp), tint = color)
        Text(text = text, color = color, style = FmType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** A SpatialUI segment control; selected = interaction yellow at 26 %. Disabled = dimmed, presses ignored. */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit, width: Dp, height: Dp, enabled: Boolean = true) {
    val itemWidth = width / items.size
    SegmentControl(modifier = Modifier.width(width).height(height).alpha(if (enabled) 1f else 0.45f), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { if (enabled) onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name) { onSelect(i) },
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.26f),
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                },
            )
        }
    }
}
