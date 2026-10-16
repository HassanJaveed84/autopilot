package com.lifeadmin.autopilot

import android.app.Application
import com.lifeadmin.autopilot.di.AppContainer

class LifeAdminApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onAppStart()
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as LifeAdminApp).container
