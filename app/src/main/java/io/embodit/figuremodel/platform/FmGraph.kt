package io.embodit.figuremodel.platform

import android.content.Context
import io.embodit.figuremodel.data.SettingsStore
import io.embodit.figuremodel.platform.input.ControllerInput
import io.embodit.figuremodel.platform.sense.PlaneTracker
import io.embodit.figuremodel.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The scene starts and stops the sensing services
 * (every start has a stop: see `FmScene.attach` / `detach` / `pauseSensing`); the ViewModel only sees state.
 */
object FmGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    @Volatile private var store: SettingsStore? = null

    fun settings(context: Context): SettingsStore =
        store ?: synchronized(this) { store ?: SettingsStore(context).also { store = it } }
}
