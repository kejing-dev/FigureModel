package io.embodit.figuremodel.domain.model

import java.util.Locale

/** Why the app cannot run right now (design S16, `copy.dialog`). */
enum class BlockReason {
    NOT_IN_FULL_SPACE,
    WITHOUT_PERMISSION,
    DEVICE_NOT_SUPPORTED,
    NO_PLANES,
}

/** Language chosen in the More menu; default follows the system (design `i18n`). */
enum class LanguagePref {
    SYSTEM,
    ZH,
    EN;

    companion object {
        fun fromTag(tag: String?): LanguagePref =
            when (tag?.lowercase(Locale.US)) {
                "zh", "cn", "中" -> ZH
                "en" -> EN
                else -> SYSTEM
            }
    }
}

/** Pose duration segments [30 s | 1 min | 5 min | ∞] (design `rules.timer`). */
enum class PoseDuration(val seconds: Int?) {
    S30(30),
    M1(60),
    M5(300),
    INF(null);

    companion object {
        fun fromTag(tag: String?): PoseDuration? =
            when (tag?.lowercase(Locale.US)) {
                "30", "30s", "s30" -> S30
                "60", "1m", "1min", "m1" -> M1
                "300", "5m", "5min", "m5" -> M5
                "inf", "0", "none", "∞" -> INF
                else -> null
            }
    }
}

/** The five light presets of the More menu (design `rules.light`). */
enum class LightPreset {
    LEFT,
    RIGHT,
    FRONT,
    BACK,
    TOP,
}

/** What stands on the ring: the mannequin or a still-life set on the pedestal. */
enum class Content {
    FIGURE,
    STILL,
}

/** Life-size (1.75 m) or desk figure (1:5 = 0.35 m on the pedestal). */
enum class FigureSize {
    FULL,
    DESK,
}

/** Everything the app remembers between runs (design `persistence.what`); positions are never stored. */
data class Settings(
    val duration: PoseDuration = PoseDuration.S30,
    /** Selected light preset, or null after the orb was dragged by hand. */
    val lightPreset: LightPreset? = LightPreset.LEFT,
    /** Light azimuth, degrees: 0 = the figure's front at placement, positive towards the viewer's right. */
    val lightAzDeg: Float = -45f,
    /** Light elevation above the horizon, degrees, 10..80. */
    val lightElDeg: Float = 45f,
    val orbShown: Boolean = true,
    val shuffle: Boolean = true,
    val content: Content = Content.FIGURE,
    val size: FigureSize = FigureSize.FULL,
    val sound: Boolean = true,
    val language: LanguagePref = LanguagePref.SYSTEM,
)
