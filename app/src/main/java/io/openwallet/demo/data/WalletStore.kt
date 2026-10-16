package io.openwallet.demo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.openwallet.demo.domain.*
import io.openwallet.demo.capture.CaptureResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WalletStore internal constructor(context: Context, databaseName: String = "openwallet.db", private val onChanged: () -> Unit = {}) : SQLiteOpenHelper(context, databaseName, null, 2) {
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    private fun changed() { changes.value++; runCatching { onChanged() } }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, baseline INTEGER NOT NULL, as_of INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES accounts(id), occurred_at INTEGER NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), direction TEXT NOT NULL CHECK(direction IN ('INCOME','EXPENSE')), merchant TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL, external_id TEXT NOT NULL DEFAULT '', pending INTEGER NOT NULL DEFAULT 0, auto_post_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX external_identity ON entries(account_id, external_id) WHERE external_id <> ''")
        db.execSQL("CREATE INDEX account_time ON entries(account_id, occurred_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "需要数据库迁移 $oldVersion → $newVersion" }
        db.execSQL("ALTER TABLE entries ADD COLUMN auto_post_at INTEGER NOT NULL DEFAULT 0")
    }
    @Synchronized fun snapshot(): WalletState {
        val entries = readableDatabase.rawQuery("SELECT * FROM entries WHERE pending <> 2 ORDER BY occurred_at DESC,id DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), Direction.valueOf(c.getString(4)), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getInt(9) == 1, c.getLong(10))) }
        }
        val accounts = readableDatabase.rawQuery("SELECT * FROM accounts ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) {
                val id = c.getLong(0); val baseline = c.getLong(2); val asOf = c.getLong(3)
                val balance = entries.filter { it.accountId == id && !it.pending && it.time > asOf }.fold(baseline) { sum, e -> Math.addExact(sum, e.signed) }
                add(Account(id, c.getString(1), baseline, asOf, balance))
            } }
        }
        return WalletState(accounts, entries)
    }
    @Synchronized fun saveAccount(name: String, balance: Long, id: Long? = null) {
        require(name.trim().isNotEmpty()) { "请输入账户名称" }
        val values = ContentValues().apply { put("name", name.trim()); put("baseline", balance); put("as_of", System.currentTimeMillis()) }
        if (id == null) writableDatabase.insertOrThrow("accounts", null, values)
        else check(writableDatabase.update("accounts", values, "id=?", arrayOf(id.toString())) == 1)
        changed()
    }
    @Synchronized fun preview(entries: List<Entry>): List<ImportRow> {
        val seen = snapshot().entries.toMutableList()
        return entries.mapIndexed { i, e -> ImportRow(i + 2, e, Duplicates.classify(e, seen)).also { seen.add(e) } }
    }
    @Synchronized fun insert(entries: List<Entry>, allowPossible: Boolean = false, approvedPossible: Set<Entry> = emptySet()): Int {
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try {
            val seen = snapshot().entries.toMutableList()
            entries.forEach { e ->
                require(e.amount > 0 && e.merchant.isNotBlank()) { "金额和交易方不能为空" }
                if (e.pending && e.externalId.isNotBlank() && db.rawQuery(
                    "SELECT 1 FROM entries WHERE account_id=? AND external_id=?", arrayOf(e.accountId.toString(), e.externalId)
                ).use { it.moveToFirst() }) return@forEach
                when (Duplicates.classify(e, seen)) {
                    Match.CONFLICT -> error("流水号 ${e.externalId} 已存在，但交易内容不同")
                    Match.EXACT -> Unit
                    Match.POSSIBLE -> if (allowPossible || e in approvedPossible) { write(db, e); count++; seen.add(e) }
                    Match.NEW -> { write(db, e); count++; seen.add(e) }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(); return count
    }
    private fun write(db: SQLiteDatabase, e: Entry) = db.insertOrThrow("entries", null, ContentValues().apply {
        put("account_id", e.accountId); put("occurred_at", e.time); put("amount", e.amount); put("direction", e.direction.name)
        put("merchant", e.merchant); put("category", e.category); put("source", e.source); put("external_id", e.externalId); put("pending", if (e.pending) 1 else 0); put("auto_post_at", e.autoPostAt)
    })
    @Synchronized fun recordNotification(entry: Entry, autoPost: Boolean, postAt: Long = 0): CaptureResult {
        require(entry.externalId.isNotBlank() && entry.source.startsWith("通知") && entry.amount > 0)
        val db = writableDatabase
        var result = CaptureResult.DUPLICATE
        db.beginTransaction()
        try {
            // Include dismissed notifications and all accounts: changing a mapping must not replay an old event.
            val alreadyReceived = db.rawQuery("SELECT 1 FROM entries WHERE external_id=? AND source LIKE '通知%'", arrayOf(entry.externalId)).use { it.moveToFirst() }
            if (!alreadyReceived) {
                val nearbyNotification = db.rawQuery(
                    "SELECT 1 FROM entries WHERE account_id=? AND source LIKE '通知%' AND amount=? AND direction=? AND occurred_at BETWEEN ? AND ?",
                    arrayOf(entry.accountId.toString(), entry.amount.toString(), entry.direction.name, (entry.time - 120_000).toString(), (entry.time + 120_000).toString())
                ).use { it.moveToFirst() }
                val match = Duplicates.classify(entry, snapshot().entries)
                val post = autoPost && !nearbyNotification && match == Match.NEW
                val buffered = post && postAt > 0
                write(db, entry.copy(pending = !post || buffered, autoPostAt = if (buffered) postAt else 0))
                result = if (buffered) CaptureResult.BUFFERED else if (post) CaptureResult.AUTO_POSTED else CaptureResult.PENDING_REVIEW
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (result != CaptureResult.DUPLICATE) changed()
        return result
    }
    @Synchronized fun nextNotificationPostAt(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(auto_post_at) FROM entries WHERE pending=1 AND auto_post_at>0", null
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    @Synchronized fun flushBufferedNotifications(now: Long = System.currentTimeMillis()): Int {
        val db = writableDatabase; var posted = 0; var updated = false
        db.beginTransaction()
        try {
            val entries = snapshot().entries
            val seen = entries.filterNot { it.pending }.toMutableList()
            entries.filter { it.pending && it.autoPostAt in 1..now }.sortedWith(compareBy<Entry> { it.autoPostAt }.thenBy { it.id }).forEach { e ->
                val post = Duplicates.classify(e, seen) == Match.NEW
                db.update("entries", ContentValues().apply { put("auto_post_at", 0); put("pending", if (post) 0 else 1) }, "id=?", arrayOf(e.id.toString()))
                if (post) { seen.add(e.copy(pending = false, autoPostAt = 0)); posted++ }
                updated = true
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (updated) changed()
        return posted
    }
    @Synchronized fun moveNotificationToReview(id: Long) {
        check(writableDatabase.update("entries", ContentValues().apply { put("pending", 1); put("auto_post_at", 0) }, "id=? AND pending=0 AND source LIKE '通知%'", arrayOf(id.toString())) == 1) { "该记录不属于已入账通知" }
        changed()
    }
    @Synchronized fun resolve(id: Long, accept: Boolean, accountId: Long, allowPossible: Boolean = false) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val state = snapshot(); val e = state.entries.first { it.id == id && it.pending }.copy(accountId = accountId, pending = false)
            if (accept) {
                val match = Duplicates.classify(e, state.entries.filterNot { it.id == id })
                require(match != Match.EXACT && match != Match.CONFLICT) { "该流水号已存在，请忽略候选" }
                require(match != Match.POSSIBLE || allowPossible) { "疑似重复：请核对后勾选仍然入账" }
                db.update("entries", ContentValues().apply { put("pending", 0); put("account_id", accountId); put("auto_post_at", 0) }, "id=?", arrayOf(id.toString()))
            } else {
                // Retain dismissed candidates as an audit record without affecting balances.
                db.execSQL("UPDATE entries SET pending=2, auto_post_at=0 WHERE id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    @Synchronized fun seedDemo() {
        require(snapshot().accounts.isEmpty()) { "示例仅可装入空账本" }
        val now = System.currentTimeMillis(); val start = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val db = writableDatabase; db.beginTransaction()
        try {
            listOf(Triple("招商银行", 2568000L, "1"), Triple("支付宝", 326850L, "2"), Triple("现金", 50000L, "3")).forEach { (name, amount, _) ->
                db.insertOrThrow("accounts", null, ContentValues().apply { put("name", name); put("baseline", amount); put("as_of", start - 1) })
            }
            val accounts = snapshot().accounts
            val sample = listOf(
                Entry(accountId = accounts[0].id, time = start + 1000, amount = 1280000, direction = Direction.INCOME, merchant = "工资入账", category = "工资", source = "示例", externalId = "demo-salary"),
                Entry(accountId = accounts[0].id, time = now - 3600000, amount = 320000, direction = Direction.EXPENSE, merchant = "本月房租", category = "居住", source = "示例", externalId = "demo-rent"),
                Entry(accountId = accounts[1].id, time = now - 1800000, amount = 2850, direction = Direction.EXPENSE, merchant = "巷口咖啡", category = "餐饮", source = "示例", externalId = "demo-coffee"),
                Entry(accountId = accounts[1].id, time = now - 1200000, amount = 12680, direction = Direction.EXPENSE, merchant = "盒马鲜生", category = "购物", source = "示例", externalId = "demo-market"),
                Entry(accountId = accounts[1].id, time = now - 600000, amount = 600, direction = Direction.EXPENSE, merchant = "地铁出行", category = "交通", source = "示例", externalId = "demo-metro")
            )
            sample.forEach { write(db, it) }; db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    companion object {
        @Volatile private var instance: WalletStore? = null
        fun get(context: Context): WalletStore = instance ?: synchronized(this) { instance ?: WalletStore(context.applicationContext, onChanged = {
            io.openwallet.demo.export.SheetsScheduler.changed(context.applicationContext)
            io.openwallet.demo.export.DriveScheduler.changed(context.applicationContext)
        }).also { instance = it } }
    }
}
