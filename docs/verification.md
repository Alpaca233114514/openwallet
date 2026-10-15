# 验证记录

日期：2026-10-15。范围：`codex/android-demo` 工作区，尚未提交或推送。

## Google Sheets 自动导出更新

- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 成功；20 项 JVM 测试（原有 13 项、新增 7 项）全部通过。Lint 为 0 errors / 26 warnings，涉及依赖更新、KTX 写法及备份规则建议，未禁用检查。
- API 35 AOSP 模拟器完整设备测试 `OK (16 tests)`，含原有 11 项以及新增 4 项导出集成测试、1 项设置界面测试。
- 模拟网关测试验证：指定账户不匹配时没有建表或写入；授权撤销停止上传；网络/限流可重试；写入响应丢失后使用相同文件和快照；切换账户后恢复各自的目标；通知移回待确认后只剩表头。
- Android 测试验证实际 SharedPreferences 持久化、暂停、按账户保存文件 ID，Sheets JSON 请求的字面量文本/公式隔离、扩容顺序及尾部旧数据清除范围，以及实际 SQLite 事务提交后触发回调、冲突回滚不触发回调。
- UI 测试实际渲染设置对话框，检查无效邮箱不能连接、有效邮箱可连接、状态展示和暂停按钮。已查看 [连接界面](screenshots/sheets-update/connect.png) 与 [已连接状态](screenshots/sheets-update/connected.png) 截图，文字与操作没有截断。后者使用 `wallet@example.com` 的合成设置；截图中的「已导出 5 条」是界面测试注入的状态，不是 Google 云端回执。
- 新测试使用独立临时 SQLite/SharedPreferences，测试后释放并清理；设备复核没有遗留测试数据库或测试设置，原 `openwallet.db` 保留。未向真实 Google 账户上传数据。
- 开发 APK：`app/build/outputs/apk/debug/app-debug.apk`；本轮回执：`.local/sheets-build-1.log`、`.local/sheets-build-2.log`、`.local/sheets-install.log`、`.local/sheets-device-tests.log`。

真实 OAuth 授权、Google Play 服务后台续期、Sheets/Drive 远端接口及 Android 长时间休眠/断网后的实际调度尚未端到端验证。本机测试镜像无 Google Play 服务，需按 [配置说明](google-sheets.md) 注册 Android OAuth 客户端后，在具备 Google Play 服务的设备上完成验收。自动导出默认关闭。

## 通知自动记账与界面精简更新

本节保留通知功能更新时的验证结果；后面的「已完成」及故障记录保留初版验证历史。

- APK 与设备测试包构建成功；13 项 JVM 测试全部通过，Lint 为 0 errors / 17 warnings（版本更新、KTX 写法和备份规则建议）。未关闭检查或新增依赖。
- API 35 模拟器完整功能测试为 `OK (11 tests)`：4 项通知链路、6 项 SQLite 事务与余额、1 项界面流程。
- 通知测试使用 mock 的标题和正文调用生产 `NotificationCapture`：100 元账户自动记入 28.50 元支出后余额 71.50 元；同一事件重投不重复扣款；移回待确认后恢复 100 元；忽略后重投不重现。
- 同额近时通知更新、CSV 与通知交叉重复进入待确认；关闭直接入账时只生成候选；关闭采集、未选来源和失效账户均不入账。更改来源账户映射后，旧事件仍不重复入账。
- 解析覆盖金额精度、交易金额与余额区分、微信普通聊天忽略、「向你付款」收入方向及不明确标题待确认。
- 界面流程覆盖空账本载入示例、资产/收支导航、填写并保存手工支出、打开工具和通知设置、启用开关及选择来源。首轮通知来源选择器匹配过多，添加唯一标签后通过。
- 已查看实际截图，删除了资产页标语、本地账本说明、账户余额基准小字、空态装饰文案及工具菜单副说明。金额、账户、日期和必要操作说明保留。

### 测试数据清理

所有设备测试使用独立临时数据库；界面测试注入测试账本并复用实际主题和页面。测试结束释放 ViewModel、关闭并删除数据库，通知测试同步完成设置写入后再删除临时设置。首次清理发现异步 `apply()` 写入会重建设置文件，已修复；相关 10 项测试复验通过。

本轮前两次界面测试及初版留下的 3 条「UI验收咖啡」记录（ID 6/7/8）已经按确定的名称、金额和来源条件删除。最终复核：临时测试数据库/设置文件 0、主账本测试记录 0、待确认 0。原有演示账本的 3 个账户、5 条示例流水保留，总资产恢复为 38,887.20 元。截图中的测试数据仅供展示，本轮隔离测试没有向日常账本写入 mock。

当前截图：[资产](screenshots/notification-update/assets.png)、[收支](screenshots/notification-update/transactions.png)、[通知设置](screenshots/notification-update/capture.png)。截图是在隔离测试期间取得。

本机回执：`.local/isolated-final-build.log`、`.local/isolated-device-tests.log`、`.local/cleanup-build.log`、`.local/cleanup-device-tests.log`、`.local/mock-ui-cleanup.log`、`.local/final-cleanup-check.log`。测试完成后关闭本轮模拟器，保留旧构建和失败日志。

尚未验证实际微信/支付宝/银行推送经过 Android 系统监听器的完整链路；当前结果是解析、入账、SQLite 持久化、撤回和界面的 mock 功能验证，不能据此宣称所有真实通知均能识别。

## 已完成

- APK 编译和打包成功，安装包：`app/build/outputs/apk/debug/app-debug.apk`。
- JVM 单元测试 9 项通过：分单位金额、严格日期、CSV 引号/换行/公式转义往返、无效输入、流水号冲突、疑似重复、通知解析和候选导出过滤。
- Android 15 / API 35 AOSP x86_64 模拟器中安装成功。执行 `am start -W -n io.openwallet.demo/.MainActivity` 返回 `Status: ok`，首次记录冷启动约 6.6 秒（低并发软件渲染测试环境，不代表真机性能）。
- SQLite 设备测试 6 项通过：历史流水保持当前余额、重复导入幂等、批次冲突原子回滚、忽略通知不改余额且不重现、确认通知只入账一次、预览后新出现的重复不会自动放行。
- 最终设备测试 `OK (7 tests)`：6 项 SQLite 测试与 1 项完整界面流程均通过，界面流程包含载入示例、切换两页、填写并保存支出。
- 从测试设备只读复制 SQLite 后独立查询：`UI验收咖啡` 记录已入账，金额 `1234` 分、方向 `EXPENSE`；账户余额合计 `3887486` 分。重启应用仍显示 `¥38,874.86`，核对了持久化结果。
- Lint 完整运行：0 errors、14 warnings。提示涉及可选依赖更新、KTX 写法、Android 新版备份规则；未禁用规则，未提高 minSdk 掩盖兼容问题。
- MCP：成功初始化、列出 27 个工具，并通过 `get_file_text_by_path` 读取当前项目 Manifest。

最终视觉修正后的 `assembleDebug testDebugUnitTest lintDebug` 已再次成功。实际触摸点击底部「收支」导航成功，显示结余 `¥9,426.36`、支出 `¥3,373.64`，与记入 `¥12.34` 支出后的账本一致。

最终无遮挡截图：[资产](screenshots/verified/assets.png)、[收支](screenshots/verified/transactions.png)。真机通知识别不在本轮已验证范围。

验证完成后已向 Android 发送关机命令，并确认测试模拟器宿主进程退出；保留虚拟磁盘和全部回执，避免继续占用电脑资源。

## 验证环境与故障修复

使用现有 JDK 21、SDK 36 和固定版本依赖。Gradle 限制为 2 个 worker、1.5 GB 堆内存；通过本机已配置的代理连接官方 Maven 仓库，未关闭 TLS 检查。

模拟器全程无窗口运行，采用 WHPX、SwiftShader、2 个虚拟 CPU、2.5 GB RAM。其用户目录、磁盘和临时目录均隔离在 `.local`。Netsim 子进程启动失败阻塞了最初的模拟器启动；本 demo 不使用无线硬件，因此使用官方功能开关关闭蓝牙/UWB/NFC/无线包流模拟后启动。未修改 Windows 防护、虚拟设备 verified boot、真实手机设置或真实通知授权。

Lint 首次遇到未编译测试符号的内部错误，先构建设备测试包后正常运行，随后修复 API 26 上的高版本主题属性和 Windows `local.properties` 路径转义。没有跳过 Lint。

首轮 UI 测试在「记一笔」的合并语义树选择器处失败，改用未合并语义树后通过。后续全屏截图发现测试模拟器的 System UI 无响应弹窗：这是系统进程弹窗，不能用被遮挡的截图声称正常视觉验收。正常点击「Wait」恢复后，重新启动应用、进行真实触摸导航，并采集上述无遮挡截图。此前截图分别保存在 `screenshots/screenshots` 和 `screenshots/final` 中作为过程证据；报告与 README 只展示 `screenshots/verified`。

## 本机证据

- `.local/build-final.log`：整套构建、测试、Lint 的成功记录。
- `.local/release-check.log`：最后一轮视觉修正后的复验。
- `.local/device-tests.log`：首轮设备测试，6 项数据库通过、1 项 UI 选择器失败；保留失败证据。
- `.local/device-tests-final.log`：最终 7 项设备测试通过。
- `.local/device-openwallet.db`：仅含本轮合成示例及测试记录的设备数据库只读副本。
- `.local/mcp-initialize.json`、`.local/mcp-tools.json`、`.local/mcp-project-read.json`：MCP 实际调用回执。
- `app/build/test-results/testDebugUnitTest/`：JVM JUnit XML。
- `app/build/reports/lint-results-debug.html`：完整 Lint 报告。

## 范围限制

CSV 采用仓库提供的标准格式，未验证银行原生 CSV/XLSX/PDF 自动适配。通知规则是实验性中文规则，仅测试解析与候选记账逻辑，未用微信、支付宝或银行的真实推送完成端到端验证。API 26 已进行静态兼容检查，本轮设备运行使用 API 35；多币种、转账去重、退款关联不在此 demo 范围内。
