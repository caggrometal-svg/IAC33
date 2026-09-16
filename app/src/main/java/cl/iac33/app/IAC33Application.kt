package cl.iac33.app

import android.app.Application

class IAC33Application : Application() {
    override fun onCreate() {
        super.onCreate()
        IAC33Runtime.initialize()
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
