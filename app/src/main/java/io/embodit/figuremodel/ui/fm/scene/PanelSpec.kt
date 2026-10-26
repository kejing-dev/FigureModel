package io.embodit.figuremodel.ui.fm.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val CONTROL = "controlPanel"
    const val MENU = "moreMenu"
    const val DIALOG = "blockedDialog"

    val CONTROL_SIZE = 400f to 268f
    val MENU_SIZE = 400f to 480f
    val DIALOG_SIZE = 360f to 200f

    /** Every panel is shown at 0.75 (1 dp = 0.6 mm): the control panel is 0.24 x 0.161 m. */
    const val SCALE = 0.75f

    /** The blocked dialog: 0.6 m ahead, 5 degrees below the eyes. */
    const val DIALOG_DISTANCE_M = 0.6f

    /** Gap between the control panel and the menu on its right: 8 dp. */
    const val MENU_GAP_DP = 8f

    val pool: List<String> = listOf(CONTROL, MENU, DIALOG)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CONTROL -> CONTROL_SIZE
            MENU -> MENU_SIZE
            else -> DIALOG_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float = SCALE): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
