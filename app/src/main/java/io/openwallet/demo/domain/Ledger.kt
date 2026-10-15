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
    val source: String = "手工", val externalId: String = "", val pending: Boolean = false
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
    fun classify(entry: Entry, existing: List<Entry>): Match {
        val idMatch = existing.firstOrNull { it.accountId == entry.accountId && entry.externalId.isNotBlank() && it.externalId == entry.externalId }
        if (idMatch != null) return if (entry.samePayment(idMatch)) Match.EXACT else Match.CONFLICT
        return if (existing.any { it.samePayment(entry) ||
            (it.accountId == entry.accountId && it.amount == entry.amount && it.direction == entry.direction &&
                kotlin.math.abs(it.time - entry.time) <= 120_000 && it.source != entry.source)
        }) Match.POSSIBLE else Match.NEW
    }
}
