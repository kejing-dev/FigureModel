package io.embodit.figuremodel.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [FigureTheme]; the other eleven
 * inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials only (wood, rings, orb, disc).
 */
object FmColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: last five seconds
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable: snapped
    val Interaction = Color(0xFFFFD24A) // design-style: fixed-figma-color interaction: ring, dwell progress, selection

    // ---- brand tokens (design `palette.brandTokens`)
    val Wood = Color(0xFFD8B07A) // design-style: fixed-figma-color wood
    val WoodDark = Color(0xFFB88A52) // design-style: fixed-figma-color woodDark: joint balls
    val Charcoal = Color(0xFF2B2622) // design-style: fixed-figma-color charcoal: disc, ring track
    val Ink = Color(0xFF3A332D) // design-style: fixed-figma-color ink: timer arc, text on yellow
    val Gypsum = Color(0xFFEDEAE4) // design-style: fixed-figma-color gypsum: still-life solids
    val LightWarm = Color(0xFFFFD98A) // design-style: fixed-figma-color lightWarm: orb
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white: disc icon

    /** Light colour of the directional light: warm white, a little less saturated than the orb. */
    val LampLight = Color(0xFFFFF1DA) // design-style: fixed-figma-color lampLight
}
