package io.openwallet.demo

import android.app.Application
import io.openwallet.demo.export.SheetsScheduler
import io.openwallet.demo.export.DriveScheduler
import io.openwallet.demo.export.ExcelScheduler

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SheetsScheduler.restore(this)
        ExcelScheduler.restore(this)
        ExcelScheduler.changed(this)
        DriveScheduler.retire(this)
        io.openwallet.demo.capture.NotificationPoster.get(this).schedule()
    }
}
