package io.openwallet.demo.capture

import io.openwallet.demo.domain.*

data class ParsedNotification(val entry: Entry, val canAutoPost: Boolean)

object NotificationParser {
    val packages = mapOf("com.eg.android.AlipayGphone" to "支付宝", "com.tencent.mm" to "微信", "cmb.pb" to "招商银行")
    private val number = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?"
    private val money = Regex("(?<![\\d.,-])(?:(?:[¥￥]|人民币|CNY|RMB)\\s*($number)(?![\\d.,])|($number)\\s*元)")
    // Payment wording and merchant patterns adapted from seamless-bookkeeping (MIT); see THIRD_PARTY_NOTICES.md.
    private val action = Regex("向[你您]\\s*付款|成功收款|收款到账|收款成功|支付成功|付款成功|成功付款|成功支付|已支付|支付了|消费|支出|扣款|扣费|支取|付款|收款|入账|到账|到帐|收入")
    private fun direction(word: String) = if (word.startsWith("向你") || word.startsWith("向您") || word in listOf("成功收款", "收款到账", "收款成功", "收款", "入账", "到账", "到帐", "收入")) Direction.INCOME else Direction.EXPENSE

    fun parse(packageName: String, text: String, accountId: Long, time: Long, key: String, title: String = ""): ParsedNotification? {
        val app = packages[packageName] ?: return null
        val trustedTitle = when (packageName) {
            "com.tencent.mm" -> title in setOf("微信支付", "微信收款助手", "收款通知", "支付凭证")
            "com.eg.android.AlipayGphone" -> title in setOf("支付宝", "支付宝支付", "支付成功", "付款成功", "收款到账", "收款通知", "交易提醒", "付款通知")
            "cmb.pb" -> title in setOf("招商银行", "交易提醒", "账户变动通知", "动账通知")
            else -> false
        }
        // A WeChat chat message containing payment words is not a payment notification.
        if (packageName == "com.tencent.mm" && !trustedTitle) return null
        val combined = "$title\n$text"
        if (listOf("验证码", "失败", "待支付", "待付款", "请付款", "请求", "退款", "撤销", "余额不足", "优惠", "转账").any(combined::contains)) return null
        val amounts = money.findAll(combined).mapNotNull { match ->
            val raw = match.groupValues[1].ifBlank { match.groupValues[2] }
            runCatching { Money.parse(raw.replace(",", "")) }.getOrNull()?.takeIf { it > 0 }?.let { match to it }
        }.toList()
        if (amounts.isEmpty()) return null
        val actions = action.findAll(combined).toList()
        val anchored = actions.flatMap { marker -> amounts.filter { (match, _) ->
            val start = marker.range.last + 1
            match.range.first >= start && match.range.first - start <= 12 &&
                Regex("[\\s:：，,金额为了]*").matches(combined.substring(start, match.range.first))
        }.map { (_, amount) -> direction(marker.value) to amount } }.distinct()
        val uniqueAmounts = amounts.map { it.second }.distinct()
        val directions = actions.map { direction(it.value) }.distinct()
        val payment = when {
            anchored.size == 1 -> anchored.single()
            anchored.isEmpty() && uniqueAmounts.size == 1 && directions.size == 1 -> directions.single() to uniqueAmounts.single()
            else -> return null // Several different amounts/directions cannot safely form a transaction.
        }
        val merchant = Regex("(?:交易方|商户|收款方|付款方)[：:]\\s*([^\\n，,；;。]+)").find(text)?.groupValues?.get(1)
            ?: Regex("向\\s*(?!你|您)(.{1,40}?)(?:付款|支付)").find(text)?.groupValues?.get(1)
            ?: Regex("(?:付款给|付给)\\s*(\\S+?)(?:[\\s，,。.！!]|$)").find(text)?.groupValues?.get(1)
            ?: "$app 交易"
        return ParsedNotification(Entry(accountId = accountId, time = time, amount = payment.second, direction = payment.first,
            merchant = merchant.trim().take(60), category = "待分类", source = "通知·$app", externalId = "notification:$packageName:$key", pending = true),
            canAutoPost = trustedTitle)
    }
}
