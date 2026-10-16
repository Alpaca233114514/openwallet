package io.openwallet.demo

import io.openwallet.demo.domain.*
import io.openwallet.demo.capture.NotificationParser
import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
    private val accounts = listOf(Account(1, "招商银行", 10000, 0))
    private val entry = Entry(accountId = 1, time = Dates.parse("2026-10-15 12:30:00"), amount = 2850, direction = Direction.EXPENSE, merchant = "咖啡", category = "餐饮", externalId = "bank-1")
    @Test fun moneyUsesIntegerCents() { assertEquals(29L, Money.parse("0.29")); assertEquals(-12345L, Money.parse("-123.45")); assertEquals("0.29", Money.decimal(29)) }
    @Test fun rejectsPrecisionLoss() { listOf("0.001", "NaN", "1e2", "1,000", "").forEach { assertTrue(runCatching { Money.parse(it) }.isFailure) } }
    @Test fun csvRoundTripPreservesQuotedUnicodeAndFormulaText() {
        val e = entry.copy(merchant = "=咖啡,\"双份\"\n备注", category = "'饮品")
        val csv = LedgerCsv.export(listOf(e), accounts)
        val result = LedgerCsv.parse(csv, accounts).single()
        assertEquals(e.merchant, result.merchant); assertEquals(e.category, result.category); assertEquals(e.amount, result.amount)
        assertTrue(csv.contains("\"'=咖啡"))
    }
    @Test fun invalidCsvRejectsWholeInput() {
        val csv = LedgerCsv.export(listOf(entry, entry.copy(time = entry.time + 1_000)), accounts).replace("28.50", "28.501")
        assertTrue(runCatching { LedgerCsv.parse(csv, accounts) }.isFailure)
        assertTrue(runCatching { LedgerCsv.tokenize("\"未闭合") }.isFailure)
    }
    @Test fun datesAreStrict() { assertTrue(runCatching { Dates.parse("2026-02-30 12:00:00") }.isFailure) }
    @Test fun externalIdDetectsConflictingAmount() {
        assertEquals(Match.EXACT, Duplicates.classify(entry, listOf(entry)))
        assertEquals(Match.CONFLICT, Duplicates.classify(entry.copy(amount = 500), listOf(entry)))
        assertEquals(Match.NEW, Duplicates.classify(entry.copy(accountId = 2), listOf(entry)))
    }
    @Test fun paymentsWithoutIdRemainReviewable() {
        assertEquals(Match.POSSIBLE, Duplicates.classify(entry.copy(externalId = ""), listOf(entry)))
        assertEquals(Match.POSSIBLE, Duplicates.classify(entry.copy(time = entry.time + 60_000, source = "通知", externalId = "notice-1"), listOf(entry)))
    }
    @Test fun notificationExpensesMatchAcrossAccountsAndSameSourceUpdates() {
        val bank = entry.copy(source = "通知·招商银行", externalId = "bank-notification")
        val wechat = bank.copy(accountId = 2, time = bank.time + 120_000, source = "通知·微信", externalId = "wechat-notification")
        assertEquals(Match.POSSIBLE, Duplicates.classify(wechat, listOf(bank)))
        assertEquals(Match.POSSIBLE, Duplicates.classify(bank, listOf(wechat)))
        assertEquals(Match.POSSIBLE, Duplicates.classify(bank.copy(time = bank.time + 1000, externalId = "bank-update"), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(time = bank.time + 120_001), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(amount = bank.amount + 1), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(direction = Direction.INCOME), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(source = "CSV"), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(source = bank.source), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(wechat.copy(direction = Direction.INCOME), listOf(bank.copy(direction = Direction.INCOME))))
    }
    @Test fun foldingChoosesNearestPostedNotificationAndKeepsPendingSeparate() {
        val bank = entry.copy(id = 1, source = "通知·招商银行")
        val candidate = bank.copy(id = 2, accountId = 2, time = bank.time + 1000, source = "通知·微信", pending = true)
        assertEquals(bank, Duplicates.relatedPostedNotification(candidate, listOf(candidate, bank)))
        assertNull(Duplicates.relatedPostedNotification(candidate, listOf(bank.copy(pending = true))))
        assertNull(Duplicates.relatedPostedNotification(candidate, listOf(bank.copy(source = "CSV"))))
        val closer = bank.copy(id = 3, time = candidate.time)
        assertEquals(closer, Duplicates.relatedPostedNotification(candidate, listOf(bank, closer)))
    }
    @Test fun sameNotificationIdentityRemainsReviewableAcrossMappingsAndTimeWindows() {
        val bank = entry.copy(id = 1, source = "通知·招商银行", externalId = "notification:cmb.pb:slot:${entry.time}")
        val updatedTime = bank.time + 180_000
        val update = bank.copy(id = 2, accountId = 2, time = updatedTime,
            externalId = "notification:cmb.pb:slot:$updatedTime", pending = true)
        assertEquals(Match.POSSIBLE, Duplicates.classify(update, listOf(bank)))
        assertEquals(Match.POSSIBLE, Duplicates.classify(bank, listOf(update)))
        assertEquals(bank, Duplicates.relatedPostedNotification(update, listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(update.copy(externalId = "notification:cmb.pb:other:$updatedTime"), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(update.copy(amount = bank.amount + 1), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(update.copy(direction = Direction.INCOME), listOf(bank)))
        assertEquals(Match.NEW, Duplicates.classify(update.copy(source = "CSV"), listOf(bank)))
    }
    @Test fun notificationIgnoresOtherAppsFailuresAndOtp() {
        val pkg = "com.eg.android.AlipayGphone"
        assertNull(NotificationParser.parse("unrelated.app", "支付成功 28.50元", 1, 1, "1"))
        assertNull(NotificationParser.parse(pkg, "支付失败 28.50元", 1, 1, "1"))
        assertNull(NotificationParser.parse(pkg, "验证码123456 消费28.50元", 1, 1, "1"))
        val parsed = NotificationParser.parse(pkg, "支付成功 28.50元", 1, 1, "1")!!
        assertEquals(2850L, parsed.entry.amount); assertTrue(parsed.entry.pending); assertEquals(Direction.EXPENSE, parsed.entry.direction)
    }
    @Test fun exportExcludesUnconfirmedNotifications() { assertEquals(1, LedgerCsv.parse(LedgerCsv.export(listOf(entry, entry.copy(pending = true)), accounts), accounts).size) }
    @Test fun trustedPaymentExtractsMerchantAndExactCents() {
        val parsed = NotificationParser.parse("com.tencent.mm", "向 巷口咖啡 付款 28.50 元", 1, 1, "a", "微信支付")!!
        assertTrue(parsed.canAutoPost); assertEquals("巷口咖啡", parsed.entry.merchant)
        assertEquals(2850L, parsed.entry.amount); assertEquals(Direction.EXPENSE, parsed.entry.direction)
    }
    @Test fun incomingPaymentIsNotAnExpense() {
        val parsed = NotificationParser.parse("com.tencent.mm", "向你付款 100.00 元", 1, 1, "a", "微信支付")!!
        assertEquals(Direction.INCOME, parsed.entry.direction)
        assertEquals(10000L, parsed.entry.amount)
        val alipay = NotificationParser.parse("com.eg.android.AlipayGphone", "成功收款 1.00 元", 1, 1, "b", "支付宝支付")!!
        assertTrue(alipay.canAutoPost); assertEquals(Direction.INCOME, alipay.entry.direction)
    }
    @Test fun transactionAmountWinsOverRemainingBalance() {
        val parsed = NotificationParser.parse("cmb.pb", "尾号1234消费人民币28.50元，余额人民币1,234.56元", 1, 1, "a", "招商银行")!!
        assertEquals(2850L, parsed.entry.amount); assertTrue(parsed.canAutoPost)
    }
    @Test fun ambiguousAmountsChatAndMalformedMoneyAreNotPosted() {
        assertNull(NotificationParser.parse("com.tencent.mm", "支付成功28.50元", 1, 1, "a", "张三"))
        assertNull(NotificationParser.parse("cmb.pb", "消费28.50元，消费50.00元", 1, 1, "a", "招商银行"))
        listOf("消费28.501元", "消费-28.50元", "消费1,23.45元").forEach {
            assertNull(it, NotificationParser.parse("cmb.pb", it, 1, 1, "a", "招商银行"))
        }
        assertFalse(NotificationParser.parse("com.eg.android.AlipayGphone", "支付成功28.50元", 1, 1, "a", "提醒")!!.canAutoPost)
    }
}
