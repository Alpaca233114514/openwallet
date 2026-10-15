package io.openwallet.demo

import android.app.Application
import io.openwallet.demo.export.SheetsScheduler

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SheetsScheduler.restore(this)
    }
}
