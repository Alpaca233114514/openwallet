package io.openwallet.demo.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Direction { EXPENSE, INCOME }
data class Account(val id: Long, val name: String, val baseline: Long, val asOf: Long, val balance: Long = baseline)
data class Entry(
    val id: Long = 0, val accountId: Long, val time: Long, val amount: Long,
    val direction: Direction, val merchant: String, val category: String,
    val source: String = "手工", val externalId: String = "", val pending: Boolean = false,
    val autoPostAt: Long = 0
) {
    val signed: Long get() = if (direction == Direction.INCOME) amount else -amount
    fun samePayment(other: Entry) = accountId == other.accountId && time == other.time &&
        amount == other.amount && direction == other.direction && merchant.trim() == other.merchant.trim()
}
data class WalletState(val accounts: List<Account> = emptyList(), val entries: List<Entry> = emptyList())
enum class Match { NEW, EXACT, POSSIBLE, CONFLICT }
data class ImportRow(val line: Int, val entry: Entry, val match: Match)

object Money {
    fun parse(text: String): Long {
        require(Regex("[+-]?\\d{1,10}(\\.\\d{1,2})?").matches(text.trim())) { "金额应为最多两位小数的数字" }
        return BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }
    fun decimal(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
    fun display(cents: Long): String = java.text.DecimalFormat("#,##0.00").format(BigDecimal.valueOf(cents, 2))
}
object Dates {
    val format: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    fun parse(value: String): Long = LocalDateTime.parse(value.trim(), format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun text(time: Long): String = format.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
}
object Duplicates {
    fun possibleMatch(entry: Entry, other: Entry): Boolean {
        if (entry.samePayment(other)) return true
        if (entry.amount != other.amount || entry.direction != other.direction) return false
        val notifications = entry.source.startsWith("通知") && other.source.startsWith("通知")
        // Android may update the same visible notification with a later timestamp. A reused slot is
        // reviewable rather than silently discarded, since it can also represent a real new payment.
        val identity = notificationIdentity(entry)
        if (notifications && entry.accountId == other.accountId && identity != null && identity == notificationIdentity(other)) return true
        if (kotlin.math.abs(entry.time - other.time) > 120_000) return false
        // A wallet payment and the funding bank's debit can refer to the same expense on different accounts.
        return (entry.accountId == other.accountId && (entry.source != other.source || notifications)) ||
            (notifications && entry.direction == Direction.EXPENSE && entry.source != other.source)
    }

    private fun notificationIdentity(entry: Entry): String? = entry.externalId.takeIf {
        entry.source.startsWith("通知") && it.startsWith("notification:") && it.endsWith(":${entry.time}")
    }?.removeSuffix(":${entry.time}")

    fun relatedPostedNotification(entry: Entry, existing: List<Entry>): Entry? =
        if (!entry.source.startsWith("通知")) null else existing.filter {
            it.id != entry.id && !it.pending && it.source.startsWith("通知") && possibleMatch(entry, it)
        }.minByOrNull { kotlin.math.abs(it.time - entry.time) }

    fun classify(entry: Entry, existing: List<Entry>): Match {
        val idMatch = existing.firstOrNull { it.accountId == entry.accountId && entry.externalId.isNotBlank() && it.externalId == entry.externalId }
        if (idMatch != null) return if (entry.samePayment(idMatch)) Match.EXACT else Match.CONFLICT
        return if (existing.any { possibleMatch(entry, it) }) Match.POSSIBLE else Match.NEW
    }
}
