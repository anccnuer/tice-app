package com.example.tice

import android.app.Application
import com.example.tice.di.AppContainer

class TiceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
