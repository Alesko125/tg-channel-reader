package ua.tgreader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any activity: the theme must be applied when the first screen inflates.
        Settings.init(this)
    }
}
