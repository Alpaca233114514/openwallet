package io.openwallet.demo

import android.app.Application
import io.openwallet.demo.export.SheetsScheduler
import io.openwallet.demo.export.DriveScheduler

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SheetsScheduler.restore(this)
        DriveScheduler.restore(this)
        io.openwallet.demo.capture.NotificationPoster.get(this).schedule()
    }
}
