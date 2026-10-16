package io.openwallet.demo.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.openwallet.demo.capture.NotificationParser
import io.openwallet.demo.domain.*

@Composable fun AccountDialog(account: Account?, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by remember { mutableStateOf(account?.name.orEmpty()) }
    var balance by remember { mutableStateOf(account?.let { Money.decimal(it.balance) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (account == null) "添加账户" else "校准当前余额") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("账户名称") }, singleLine = true)
            OutlinedTextField(balance, { balance = it }, label = { Text("当前余额（元）") }, singleLine = true)
            if (account != null) Text("保存后以此余额重新计算。", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        error = runCatching { require(name.isNotBlank()) { "请输入账户名称" }; Money.parse(balance) }.exceptionOrNull()?.message
        if (error == null) save(name, balance)
    }) { Text("保存余额") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable fun AccountPicker(accounts: List<Account>, selected: Long, select: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(accounts.firstOrNull { it.id == selected }?.name ?: "选择账户") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { accounts.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { select(a.id); expanded = false }) } }
    }
}
@Composable fun EntryDialog(accounts: List<Account>, dismiss: () -> Unit, save: (Entry) -> Unit) {
    var account by remember { mutableLongStateOf(accounts.first().id) }
    var direction by remember { mutableStateOf(Direction.EXPENSE) }
    var amount by remember { mutableStateOf("") }; var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("餐饮") }; var time by remember { mutableStateOf(Dates.text(System.currentTimeMillis())) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("记一笔") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Direction.entries.forEach { d -> FilterChip(selected = direction == d, onClick = { direction = d; category = if (d == Direction.INCOME) "工资" else "餐饮" }, label = { Text(if (d == Direction.INCOME) "收入" else "支出") }) } }
            AccountPicker(accounts, account) { account = it }
            OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, singleLine = true)
            OutlinedTextField(merchant, { merchant = it }, label = { Text("交易方 / 备注") }, singleLine = true)
            OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true)
            OutlinedTextField(time, { time = it }, label = { Text("时间") }, supportingText = { Text("yyyy-MM-dd HH:mm:ss") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        runCatching {
            val cents = Money.parse(amount); require(cents > 0) { "金额必须大于零" }; require(merchant.isNotBlank()) { "请输入交易方" }
            val occurredAt = Dates.parse(time); require(occurredAt <= System.currentTimeMillis()) { "交易时间不能晚于现在" }
            Entry(accountId = account, time = occurredAt, amount = cents, direction = direction, merchant = merchant.trim(), category = category.trim().ifBlank { "其他" })
        }.onSuccess(save).onFailure { error = it.message }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable fun ImportDialog(rows: List<ImportRow>, busy: Boolean, dismiss: () -> Unit, save: (List<ImportRow>) -> Unit) {
    val selected = remember(rows) { mutableStateMapOf<Int, Boolean>().apply { rows.forEach { put(it.line, it.match == Match.NEW) } } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("核对导入 · ${rows.size} 条") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新记录 ${rows.count { it.match == Match.NEW }} · 重复/疑似 ${rows.count { it.match == Match.EXACT || it.match == Match.POSSIBLE }} · 冲突 ${rows.count { it.match == Match.CONFLICT }}", fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(rows, key = { it.line }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected[row.line] == true, onCheckedChange = { selected[row.line] = it }, enabled = !busy && row.match in listOf(Match.NEW, Match.POSSIBLE))
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text("${row.entry.merchant} · ${Money.display(row.entry.amount)}", fontWeight = FontWeight.Medium)
                            Text("第 ${row.line} 行 · ${Dates.text(row.entry.time).take(16)}", fontSize = 11.sp)
                            Text(when (row.match) { Match.NEW -> "新记录"; Match.EXACT -> "同一流水号，已跳过"; Match.POSSIBLE -> "疑似重复 · 核对后可选择保留"; Match.CONFLICT -> "流水号冲突" }, fontSize = 11.sp, color = if (row.match == Match.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }, confirmButton = { Button(enabled = !busy && selected.any { it.value }, onClick = { save(rows.filter { selected[it.line] == true }) }) { Text("导入 ${selected.count { it.value }} 条") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("取消") } })
}
@Composable fun CandidateDialog(entry: Entry, state: WalletState, dismiss: () -> Unit, resolve: (Boolean, Long, Boolean) -> Unit) {
    var account by remember(entry.id) { mutableLongStateOf(entry.accountId) }; var allow by remember(entry.id, account) { mutableStateOf(false) }
    val match = Duplicates.classify(entry.copy(accountId = account), state.entries.filterNot { it.id == entry.id })
    val related = Duplicates.relatedPostedNotification(entry.copy(accountId = account), state.entries)
    AlertDialog(onDismissRequest = dismiss, title = { Text("确认这笔通知") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.merchant); Text("${if (entry.direction == Direction.INCOME) "收入" else "支出"} ¥ ${Money.display(entry.amount)}", style = MaterialTheme.typography.headlineSmall)
            Text(Dates.text(entry.time)); AccountPicker(state.accounts, account) { account = it }
            related?.let { posted -> Text("已入账：${state.accounts.firstOrNull { it.id == posted.accountId }?.name.orEmpty()} · ${posted.source} · ¥ ${Money.display(posted.amount)}") }
            if (match == Match.POSSIBLE) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allow, { allow = it }); Text("确认是另一笔交易，仍然入账") }
            if (match == Match.EXACT || match == Match.CONFLICT) Text("流水号已存在，请忽略此候选", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(enabled = match == Match.NEW || (match == Match.POSSIBLE && allow), onClick = { resolve(true, account, allow) }) { Text(if (related != null) "另记一笔" else "确认入账") } }, dismissButton = { Row { TextButton(onClick = { resolve(false, account, false) }) { Text(if (related != null) "保留一笔" else "忽略") }; TextButton(onClick = dismiss) { Text("稍后") } } })
}
@Composable fun CaptureDialog(accounts: List<Account>, dismiss: () -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("capture", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var automatic by remember { mutableStateOf(prefs.getBoolean("auto_post", true)) }
    var granted by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { NotificationParser.packages.keys.forEach { put(it, prefs.getBoolean(it, false)) } } }
    val mappings = remember { mutableStateMapOf<String, Long>().apply { NotificationParser.packages.keys.forEach { pkg -> put(pkg, prefs.getLong("account:$pkg", prefs.getLong("account", accounts.firstOrNull()?.id ?: -1))) } } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("通知自动记账") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("读取交易通知", Modifier.weight(1f)); Switch(enabled, { enabled = it }, modifier = Modifier.testTag("capture-enabled"), enabled = accounts.isNotEmpty()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("直接入账", Modifier.weight(1f)); Switch(automatic, { automatic = it }, modifier = Modifier.testTag("capture-automatic"), enabled = enabled) }
            if (enabled) Text(if (automatic) "识别明确的交易直接入账，疑似重复待确认。" else "所有交易先放入待确认。")
            NotificationParser.packages.forEach { (pkg, name) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(selected[pkg] == true, { selected[pkg] = it }, modifier = Modifier.testTag("capture-source:$pkg"), enabled = enabled); Text(name) }
                    if (selected[pkg] == true) AccountPicker(accounts, mappings[pkg] ?: -1) { mappings[pkg] = it }
                }
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(if (granted) "通知权限已开启" else "开启通知权限") }
            prefs.getString("last_result", null)?.let { Text(it) }
        }
    }, confirmButton = { Button(enabled = !enabled || (selected.any { it.value } && selected.filterValues { it }.keys.all { pkg -> accounts.any { it.id == mappings[pkg] } }), onClick = {
        prefs.edit().putBoolean("enabled", enabled).putBoolean("auto_post", automatic).apply {
            selected.forEach { (key, value) -> putBoolean(key, value) }
            mappings.forEach { (pkg, id) -> putLong("account:$pkg", id) }
        }.apply(); dismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
