package io.openwallet.demo.update

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateViewModel(app: Application, private val checker: UpdateChecker) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GitHubUpdateChecker())
    @Suppress("DEPRECATION")
    private val info: PackageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
    val versionName: String = info.versionName ?: "未知"
    @Suppress("DEPRECATION")
    val versionCode: Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private val mutableResult = MutableStateFlow<UpdateResult>(UpdateResult.Idle)
    val result = mutableResult.asStateFlow()

    fun check() {
        if (mutableResult.value == UpdateResult.Checking) return
        mutableResult.value = UpdateResult.Checking
        viewModelScope.launch {
            mutableResult.value = withContext(Dispatchers.IO) { checker.check(versionName) }
        }
    }
}
