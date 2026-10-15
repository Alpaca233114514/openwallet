package io.openwallet.demo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WalletViewModel(app: Application, private val store: WalletStore) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, WalletStore.get(app))
    private val mutableState = MutableStateFlow(WalletState())
    val state = mutableState.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<List<ImportRow>?>(null)
    val busy = MutableStateFlow(false)
    init { viewModelScope.launch { store.revision.collect { mutableState.value = withContext(Dispatchers.IO) { store.snapshot() } } } }
    private fun work(block: () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { message.value = withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { message.value = e.message ?: "操作失败，请重试" }
            finally { busy.value = false }
        }
    }
    fun demo() = work { store.seedDemo(); "已载入示例账本" }
    fun account(name: String, amount: String, id: Long? = null) = work { store.saveAccount(name, Money.parse(amount), id); "当前余额已保存" }
    fun add(entry: Entry) = work { val n = store.insert(listOf(entry), allowPossible = true); if (n == 0) "该流水号已存在" else "已记一笔" }
    fun import(uri: Uri) = work {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val n = stream.read(chunk); if (n == -1) break; require(buffer.size() + n <= 2_000_000) { "CSV 文件不能超过 2 MB" }; buffer.write(chunk, 0, n) }
            buffer.toByteArray()
        } ?: error("无法读取文件")
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        preview.value = store.preview(LedgerCsv.parse(text, store.snapshot().accounts)); "请核对导入预览"
    }
    fun importSelected(rows: List<ImportRow>) = work {
        val n = store.insert(rows.map { it.entry }, approvedPossible = rows.filter { it.match == Match.POSSIBLE }.map { it.entry }.toSet())
        preview.value = null; "已导入 $n 条，跳过 ${rows.size - n} 条重复流水"
    }
    fun export(uri: Uri) = work {
        val s = store.snapshot()
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(LedgerCsv.export(s.entries, s.accounts).toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件")
        "CSV 已导出（不含待确认通知）"
    }
    fun resolve(entry: Entry, accept: Boolean, accountId: Long, allow: Boolean) = work {
        store.resolve(entry.id, accept, accountId, allow); if (accept) "通知已入账" else "已忽略通知"
    }
    fun reviewNotification(entry: Entry) = work { store.moveNotificationToReview(entry.id); "已移回待确认，余额已恢复" }
}
