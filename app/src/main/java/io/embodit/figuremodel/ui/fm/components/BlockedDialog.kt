package io.embodit.figuremodel.ui.fm.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.figuremodel.R
import io.embodit.figuremodel.domain.model.BlockReason
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.scene.PanelSpec

/**
 * "Can't run right now" (S16; 360 x 200 dp, 0.6 m ahead): title, reason, [Retry] [Quit]. Self-built: in a Stage
 * the SpatialUI `AlertDialog` composes nothing (Spatial Ruler finding).
 */
@Composable
internal fun BlockedDialog(reason: BlockReason?, onEvent: (FmEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (reason == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(32.dp)).backgroundMaterial(enable = true, style = Material.Thick).padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(28.dp), tint = ink)
                Text(text = rs(R.string.dialog_title), color = ink, style = FmType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (reason == BlockReason.NO_PLANES) rs(R.string.dialog_no_planes) else rs(R.string.dialog_not_full_space),
                color = PicoTheme.colorScheme.labelSecondary,
                style = FmType.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("dialog_exit", rs(R.string.menu_exit), { onEvent(FmEvent.ExitClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(FmEvent.RetryClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
            }
        }
    }
}
