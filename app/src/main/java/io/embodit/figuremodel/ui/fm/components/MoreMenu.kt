package io.embodit.figuremodel.ui.fm.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.figuremodel.R
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.LanguagePref
import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.FmUiState
import io.embodit.figuremodel.ui.fm.scene.PanelSpec

/**
 * The More menu (400 x 480 dp at 0.75, right of the control panel; design `panels.moreMenu`): light presets
 * (two-layer row 72), then eight rows of 48: orb, order, content, size, sound, language, move the ring, quit.
 * Rows act in place and leave the menu open; "move the ring" closes it.
 */
@Composable
internal fun MoreMenu(state: FmUiState, filled: Boolean, onEvent: (FmEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, padding = 12.dp, spacing = 0.dp) {
        if (!filled) return@Glass
        val s = state.settings
        val canPlace = state.placed
        // 1: light, two layers (72)
        Column(modifier = Modifier.fillMaxWidth().height(72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowIcon(R.drawable.ic_light)
                Text(text = rs(R.string.menu_light), color = PicoTheme.colorScheme.labelPrimary, style = FmType.label, maxLines = 1)
            }
            Segments(
                items =
                    listOf(
                        "light_left" to rs(R.string.light_left),
                        "light_right" to rs(R.string.light_right),
                        "light_front" to rs(R.string.light_front),
                        "light_back" to rs(R.string.light_back),
                        "light_top" to rs(R.string.light_top),
                    ),
                selected = s.lightPreset?.ordinal ?: -1,
                onSelect = { onEvent(FmEvent.LightPresetSelected(LightPreset.entries[it])) },
                width = 376.dp,
                height = 40.dp,
            )
        }
        MenuRow(R.drawable.ic_orb, rs(R.string.menu_orb)) {
            Segments(listOf("orb_show" to rs(R.string.orb_show), "orb_hide" to rs(R.string.orb_hide)), if (s.orbShown) 0 else 1, { onEvent(FmEvent.OrbShownSelected(it == 0)) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_shuffle, rs(R.string.menu_order)) {
            Segments(listOf("order_shuffle" to rs(R.string.order_shuffle), "order_seq" to rs(R.string.order_seq)), if (s.shuffle) 0 else 1, { onEvent(FmEvent.ShuffleSelected(it == 0)) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_figure, rs(R.string.menu_content)) {
            Segments(listOf("content_figure" to rs(R.string.content_figure), "content_still" to rs(R.string.content_still)), s.content.ordinal, { onEvent(FmEvent.ContentSelected(Content.entries[it])) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_size, rs(R.string.menu_size)) {
            Segments(listOf("size_full" to rs(R.string.size_full), "size_desk" to rs(R.string.size_desk)), s.size.ordinal, { onEvent(FmEvent.SizeSelected(FigureSize.entries[it])) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_sound, rs(R.string.menu_sound)) {
            Segments(listOf("sound_on" to rs(R.string.sound_on), "sound_off" to rs(R.string.sound_off)), if (s.sound) 0 else 1, { onEvent(FmEvent.SoundSelected(it == 0)) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_globe, rs(R.string.menu_language)) {
            val zh = rs(R.string.lang_code) == "zh"
            val selected =
                when (s.language) {
                    LanguagePref.ZH -> 0
                    LanguagePref.EN -> 1
                    LanguagePref.SYSTEM -> if (zh) 0 else 1
                }
            Segments(listOf("lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)), selected, { onEvent(FmEvent.LanguageSelected(if (it == 0) LanguagePref.ZH else LanguagePref.EN)) }, 200.dp, 40.dp)
        }
        MenuRow(R.drawable.ic_relocate, rs(R.string.menu_relocate)) {
            WordButton("menu_relocate", rs(R.string.menu_relocate), { onEvent(FmEvent.RelocateClicked) }, enabled = canPlace, minWidth = 120.dp, height = 40.dp)
        }
        MenuRow(R.drawable.ic_exit, rs(R.string.menu_exit), danger = true) {
            WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(FmEvent.ExitClicked) }, danger = true, minWidth = 120.dp, height = 40.dp)
        }
    }
}

@Composable
private fun RowIcon(iconRes: Int, danger: Boolean = false) {
    Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp), tint = if (danger) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary)
}

@Composable
private fun MenuRow(iconRes: Int, label: String, danger: Boolean = false, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RowIcon(iconRes, danger)
        Text(
            text = label,
            color = if (danger) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary,
            style = FmType.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        content()
    }
}
