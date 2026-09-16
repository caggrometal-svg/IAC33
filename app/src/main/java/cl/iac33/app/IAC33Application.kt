package cl.iac33.app

import android.app.Application
import android.content.Context

class IAC33Application : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        IAC33Runtime.initialize()
    }

    companion object {
        @Volatile
        private var context: Context? = null

        fun contextOrNull(): Context? = context
    }
}

object IAC33Runtime {
    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) initialized = true
        }
    }
}
