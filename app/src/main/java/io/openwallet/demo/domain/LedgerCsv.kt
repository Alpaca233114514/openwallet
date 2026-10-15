package io.openwallet.demo.domain

/** Standard UTF-8 CSV. No bank-specific column guessing; errors reject the entire preview. */
object LedgerCsv {
    val headers = listOf("时间", "账户", "类型", "金额", "交易方", "分类", "流水号")
    fun parse(text: String, accounts: List<Account>): List<Entry> {
        val rows = tokenize(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.first() == headers) { "表头应为：${headers.joinToString(",")}" }
        require(rows.size <= 10_001) { "一次最多导入 10000 条流水" }
        return rows.drop(1).filterNot { it.all(String::isBlank) }.mapIndexed { i, row ->
            try {
                require(row.size == headers.size) { "列数应为 7" }
                val account = accounts.singleOrNull { it.name == row[1].trim() } ?: error("找不到账户 ${row[1]}，请先添加同名账户")
                val direction = when (row[2].trim()) { "支出" -> Direction.EXPENSE; "收入" -> Direction.INCOME; else -> error("类型只支持收入或支出") }
                val amount = Money.parse(row[3])
                require(amount > 0) { "流水金额必须大于零" }
                require(row[4].isNotBlank()) { "交易方不能为空" }
                Entry(accountId = account.id, time = Dates.parse(row[0]), amount = amount, direction = direction,
                    merchant = row[4].trim(), category = row[5].trim().ifBlank { "其他" }, externalId = row[6].trim(), source = "CSV")
            } catch (e: Exception) { throw IllegalArgumentException("第 ${i + 2} 行：${e.message}", e) }
        }
    }
    fun export(entries: List<Entry>, accounts: List<Account>): String = "\uFEFF" +
        (listOf(headers) + entries.filterNot { it.pending }.map { e -> listOf(
            Dates.text(e.time), accounts.first { it.id == e.accountId }.name,
            if (e.direction == Direction.INCOME) "收入" else "支出", Money.decimal(e.amount), e.merchant, e.category, e.externalId
        ) }).joinToString("\r\n") { row -> row.joinToString(",") { quote(it) } } + "\r\n"

    // Prefix potentially executable spreadsheet formulas; decode only this exact escaping convention.
    private fun quote(s: String): String {
        val safe = if (s.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n', '\'')) "'$s" else s
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun tokenize(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var i = 0
        fun endCell() {
            val v = cell.toString()
            row.add(if (v.startsWith("'") && v.getOrNull(1) in listOf('=', '+', '-', '@', '\t', '\r', '\n', '\'')) v.drop(1) else v)
            cell.setLength(0); closed = false
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (text.getOrNull(i + 1) == '"') { cell.append('"'); i++ } else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "CSV 引号位置错误" }; quoted = true }
                ',' -> endCell()
                '\r', '\n' -> { endCell(); rows.add(row); row = mutableListOf(); if (c == '\r' && text.getOrNull(i + 1) == '\n') i++ }
                else -> { require(!closed) { "CSV 引号后有多余内容" }; cell.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) { endCell(); rows.add(row) }
        return rows
    }
}
