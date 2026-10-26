package io.embodit.figuremodel.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.figuremodel.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FmGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
