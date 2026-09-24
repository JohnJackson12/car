package com.voicemusic.app

import android.app.Application
import android.os.StrictMode

/** Application singleton: config/db/library are created once and shared by the service and the UI. */
class App : Application() {
    lateinit var config: Config
        private set
    lateinit var db: Db
        private set
    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        TagIO.init()
        config = Config(this)
        db = Db(this)
        library = Library(this, config, db)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
