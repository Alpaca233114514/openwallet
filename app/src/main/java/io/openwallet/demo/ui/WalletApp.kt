package io.openwallet.demo.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.openwallet.demo.WalletViewModel
import io.openwallet.demo.domain.*
import java.time.*

private val Income = Color(0xFF267A59)
private val Expense = Color(0xFFB76B4D)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WalletApp(vm: WalletViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var editAccount by remember { mutableStateOf<Account?>(null) }
    var candidate by remember { mutableStateOf<Entry?>(null) }
    val currentCandidate = state.entries.firstOrNull { it.id == candidate?.id && it.pending }
    LaunchedEffect(candidate?.id, currentCandidate?.id) { if (candidate != null && currentCandidate == null) candidate = null }
    var postedNotification by remember { mutableStateOf<Entry?>(null) }
    val snack = remember { SnackbarHostState() }
    val layoutDirection = LocalLayoutDirection.current
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    WalletNavigation(
        settingsOpen = settingsOpen,
        backEnabled = dialog == null && preview == null && currentCandidate == null && postedNotification == null,
        onBack = { settingsOpen = false },
    ) { showSettings ->
    val backdrop = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!showSettings) Icon(Icons.Rounded.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (showSettings) "设置" else "OpenWallet", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }, navigationIcon = {
            if (showSettings) IconButton(onClick = { settingsOpen = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回账本") }
        }, actions = {
            if (!showSettings) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Settings, "设置") }
        }) },
        bottomBar = {
            if (!showSettings) FloatingNavigationBar(selectedTab = tab, onTab = { tab = it }, backdrop = backdrop, backdropOrigin = backdropOrigin)
        },
        floatingActionButton = {
            if (!showSettings && state.accounts.isNotEmpty()) ExtendedFloatingActionButton(onClick = { dialog = "entry" }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("记一笔") }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)
        }
    ) { padding ->
        // Let the list scroll behind the floating glass; its trailing padding keeps the last row reachable.
        Column(Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection),
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
            .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
            .drawWithContent {
                backdrop.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop)
            }
            .background(MaterialTheme.colorScheme.background)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (showSettings) SettingsScreen(
                onImport = { dialog = "importHelp" },
                onExport = { exportFile.launch("openwallet-${LocalDate.now()}.csv") },
                onCapture = { dialog = "capture" }, onExcel = { dialog = "excel" },
            )
            else if (state.accounts.isEmpty()) EmptyWallet(onAccount = { dialog = "account" }, onDemo = vm::demo, busy = busy)
            else WalletTabContent(tab) { selected ->
                if (selected == 0) AssetsScreen(state, onAdd = { dialog = "account" }, onEdit = { editAccount = it; dialog = "account" }, onImport = { dialog = "importHelp" }, onExport = { exportFile.launch("openwallet-${LocalDate.now()}.csv") })
                else TransactionsScreen(state, onCandidate = { candidate = it }, onNotification = { postedNotification = it }, onImport = { dialog = "importHelp" })
            }
        }
    }
    }
    if (dialog == "account") AccountDialog(editAccount, dismiss = { dialog = null; editAccount = null }) { name, balance -> vm.account(name, balance, editAccount?.id); dialog = null; editAccount = null }
    if (dialog == "importAccount") AccountDialog(null, dismiss = { dialog = "importHelp" }) { name, balance ->
        vm.account(name, balance)
        dialog = "importHelp"
    }
    if (dialog == "entry") EntryDialog(state.accounts, dismiss = { dialog = null }, save = { vm.add(it); dialog = null })
    if (dialog == "capture") CaptureDialog(state.accounts) { dialog = null }
    if (dialog == "excel") ExcelDialog { dialog = null }
    if (dialog == "importHelp") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("导入银行流水") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("将银行导出的流水整理为 UTF-8 CSV，并先在资产页添加同名账户。")
            Text("时间,账户,类型,金额,交易方,分类,流水号", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("时间格式：2026-10-15 12:30:00\n类型：收入 / 支出；金额填正数。\n流水号可留空，疑似重复项默认不选。")
        }
    }, confirmButton = {
        Button(onClick = {
            if (state.accounts.isEmpty()) dialog = "importAccount"
            else {
                dialog = null
                importFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
            }
        }) { Text(if (state.accounts.isEmpty()) "添加账户" else "选择 CSV 文件") }
    }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
    preview?.let { rows -> ImportDialog(rows, busy, { vm.preview.value = null }, vm::importSelected) }
    currentCandidate?.let { entry -> CandidateDialog(entry, state, dismiss = { candidate = null }) { accept, account, allow -> vm.resolve(entry, accept, account, allow); candidate = null } }
    postedNotification?.let { entry -> AlertDialog(onDismissRequest = { postedNotification = null }, title = { Text("通知记账") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.merchant)
            Text("${if (entry.direction == Direction.INCOME) "收入" else "支出"} ¥ ${Money.display(entry.amount)}", style = MaterialTheme.typography.headlineSmall)
            Text("${state.accounts.firstOrNull { it.id == entry.accountId }?.name.orEmpty()} · ${Dates.text(entry.time)}")
        }
    }, confirmButton = { TextButton(onClick = { vm.reviewNotification(entry); postedNotification = null }) { Text("移回待确认") } }, dismissButton = { TextButton(onClick = { postedNotification = null }) { Text("关闭") } }) }
}

@Composable private fun EmptyWallet(onAccount: () -> Unit, onDemo: () -> Unit, busy: Boolean) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp)); Text("开始记账", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp)); Button(onClick = onAccount, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加第一个账户") }
        OutlinedButton(onClick = onDemo, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("体验示例账本") }
    }
}

@Composable private fun AssetsScreen(state: WalletState, onAdd: () -> Unit, onEdit: (Account) -> Unit, onImport: () -> Unit, onExport: () -> Unit) {
    val posted = state.entries.filterNot { it.pending }
    val month = YearMonth.now(); val monthly = posted.filter { YearMonth.from(Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault())) == month }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 180.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("我的资产", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF164E3F), Color(0xFF387D62))))) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 35.dp, y = (-45).dp).size(190.dp).blur(40.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded).background(Color(0xFFB4E8B5).copy(alpha = .25f), CircleShape))
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("总资产 · CNY", color = Color.White.copy(alpha = .8f)); Icon(Icons.Rounded.NorthEast, null, tint = Color(0xFFC3E8CF), modifier = Modifier.size(20.dp)) }
                Text("¥ ${Money.display(state.accounts.sumOf { it.balance })}", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("本月收入", monthly.filter { it.direction == Direction.INCOME }.sumOf { it.amount }, Income, Modifier.weight(1f))
            Metric("本月支出", monthly.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount }, Expense, Modifier.weight(1f))
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("我的账户", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("添加账户") }
        } }
        items(state.accounts, key = { it.id }) { account ->
            Surface(onClick = { onEdit(account) }, shape = RoundedCornerShape(22.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Rounded.AccountBalance, null, Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)).padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(account.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("¥ ${Money.display(account.balance)}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入流水") }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导出 CSV") }
        } }
    }
}

@Composable private fun Metric(label: String, amount: Long, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("¥ ${Money.display(amount)}", fontWeight = FontWeight.Bold, color = color, fontSize = 21.sp)
        }
    }
}

@Composable private fun TransactionsScreen(state: WalletState, onCandidate: (Entry) -> Unit, onNotification: (Entry) -> Unit, onImport: () -> Unit) {
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val month = YearMonth.now().plusMonths(monthOffset.toLong())
    val pending = state.entries.filter { it.pending }
    val monthEntries = state.entries.filter { !it.pending && YearMonth.from(Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault())) == month }
    val visible = monthEntries.filter { filter == "全部" || (filter == "收入") == (it.direction == Direction.INCOME) }
    val folded = pending.mapNotNull { candidate ->
        Duplicates.relatedPostedNotification(candidate, visible)?.let { it.id to candidate }
    }.groupBy({ it.first }, { it.second })
    val foldedIds = folded.values.flatten().map { it.id }.toSet()
    val separatePending = pending.filterNot { it.id in foldedIds }
    val expense = monthEntries.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount }
    val income = monthEntries.filter { it.direction == Direction.INCOME }.sumOf { it.amount }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 180.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("收支明细", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthOffset-- }) { Icon(Icons.Rounded.ChevronLeft, "上个月") }
            Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { monthOffset++ }, enabled = monthOffset < 0) { Icon(Icons.Rounded.ChevronRight, "下个月") }
        } }
        item { Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("当月结余", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("¥ ${Money.display(income - expense)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("收入  ${Money.display(income)}", color = Income); Text("支出  ${Money.display(expense)}", color = Expense) }
                if (expense > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    monthEntries.filter { it.direction == Direction.EXPENSE }.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amount } }.toList().sortedByDescending { it.second }.take(3).forEach { (category, amount) ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(category, fontSize = 12.sp); Text(String.format(java.util.Locale.CHINA, "%.1f%%", amount.toDouble() / expense * 100), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            LinearProgressIndicator(progress = { amount.toFloat() / expense }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
                        }
                    }
                }
            }
        } }
        if (separatePending.isNotEmpty()) {
            item { Text("待确认通知 · ${separatePending.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            items(separatePending, key = { "pending-${it.id}" }) { e -> Surface(onClick = { onCandidate(e) }, enabled = e.autoPostAt == 0L, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f), shape = RoundedCornerShape(18.dp)) { EntryRow(e, state, pending = true) } }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "支出", "收入").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) } } }
        if (visible.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("这个月还没有${if (filter == "全部") "记录" else filter}", color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = onImport) { Text("导入一份流水") } } }
        visible.groupBy { Dates.text(it.time).take(10) }.forEach { (date, entries) ->
            item(key = "date-$date") { Text(date, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            items(entries, key = { it.id }) { e -> Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                Column {
                    Box(modifier = if (e.source.startsWith("通知")) Modifier.clickable { onNotification(e) } else Modifier) { EntryRow(e, state) }
                    folded[e.id]?.let { candidates ->
                        val sources = (listOf(e) + candidates).map { it.source.removePrefix("通知·") }.distinct().joinToString(" / ")
                        TextButton(onClick = { onCandidate(candidates.first()) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("$sources · ${candidates.size + 1} 条通知 · 核对") }
                    }
                }
            } }
        }
    }
}
@Composable private fun EntryRow(e: Entry, state: WalletState, pending: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = when (e.category) { "餐饮" -> Icons.Rounded.LocalCafe; "交通" -> Icons.Rounded.DirectionsSubway; "购物" -> Icons.Rounded.ShoppingBag; "居住" -> Icons.Rounded.Home; "工资" -> Icons.Rounded.Work; else -> Icons.Rounded.Payments }
        Icon(icon, null, Modifier.background(Color(0xFFF1F4EF), RoundedCornerShape(13.dp)).padding(10.dp), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(e.merchant, fontWeight = FontWeight.Medium)
            Text("${state.accounts.firstOrNull { it.id == e.accountId }?.name.orEmpty()} · ${e.source} · ${Dates.text(e.time).substring(11, 16)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${if (e.direction == Direction.INCOME) "+" else "−"}${Money.display(e.amount)}", color = if (e.direction == Direction.INCOME) Income else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (pending) Text(if (e.autoPostAt > 0) "等待通知合并" else "待确认 · 未入账", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
