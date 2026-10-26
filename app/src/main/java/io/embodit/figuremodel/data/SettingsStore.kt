package io.embodit.figuremodel.data

import android.content.Context
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.LanguagePref
import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.domain.model.PoseDuration
import io.embodit.figuremodel.domain.model.Settings

/** Settings only (design `persistence`): no positions, no progress, no artwork. SharedPreferences, main thread. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("figure_model", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        fun <T : Enum<T>> enumOf(key: String, values: Array<T>, fallback: T): T =
            prefs.getString(key, null)?.let { s -> values.firstOrNull { it.name == s } } ?: fallback
        return Settings(
            duration = enumOf("duration", PoseDuration.entries.toTypedArray(), d.duration),
            lightPreset = prefs.getString("lightPreset", d.lightPreset?.name)?.let { s -> LightPreset.entries.firstOrNull { it.name == s } },
            lightAzDeg = prefs.getFloat("lightAz", d.lightAzDeg),
            lightElDeg = prefs.getFloat("lightEl", d.lightElDeg),
            orbShown = prefs.getBoolean("orbShown", d.orbShown),
            shuffle = prefs.getBoolean("shuffle", d.shuffle),
            content = enumOf("content", Content.entries.toTypedArray(), d.content),
            size = enumOf("size", FigureSize.entries.toTypedArray(), d.size),
            sound = prefs.getBoolean("sound", d.sound),
            language = enumOf("language", LanguagePref.entries.toTypedArray(), d.language),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("duration", s.duration.name)
            .putString("lightPreset", s.lightPreset?.name ?: "")
            .putFloat("lightAz", s.lightAzDeg)
            .putFloat("lightEl", s.lightElDeg)
            .putBoolean("orbShown", s.orbShown)
            .putBoolean("shuffle", s.shuffle)
            .putString("content", s.content.name)
            .putString("size", s.size.name)
            .putBoolean("sound", s.sound)
            .putString("language", s.language.name)
            .apply()
    }
}
