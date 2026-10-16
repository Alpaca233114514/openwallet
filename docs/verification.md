# 验证记录

日期：2026-10-15。范围：`codex/android-demo` 分支；本文件记录本地验证结果，PR 的远端状态以 GitHub 为准。

## 独立设置页与检查更新（2026-10-15）

- 最终 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 成功；38 项 JVM 测试通过，Lint 0 errors / 37 warnings。未新增依赖或关闭检查。安装包为 `app/build/outputs/apk/debug/app-debug.apk`，已覆盖安装到现有 API 35 模拟器，保留原有应用数据。构建回执 `.local/settings-update-build-final.log`，安装回执 `.local/settings-update-install-final.log`。
- 完整设备回归输出 `OK (46 tests)`，其中 45 项通过、1 项按需联网检查因未传入 `liveUpdates=true` 跳过。新增 4 项 release 解析/响应测试及 3 项设置 UI 测试，覆盖数字和预发布版本排序、相同/旧版本、缺少 APK/发布说明、无公开版本、错误响应、超时/断网、检查中禁用重复请求、重试、重新进入设置保留结果、无账户时打开设置、顶部及系统返回；已有账本、通知和 Google 导出回归也通过。回执 `.local/settings-update-device-final.log`。
- 按用户要求另行显式运行真实联网测试：在 APP 内点击「检查更新」，使用生产 HTTPS transport，请求固定 GitHub Releases API，约 2411 ms 得到 HTTP 403。电脑侧同一接口明确返回 `API rate limit exceeded`。因此已验证请求可到达 GitHub，但当时的匿名 API 额度耗尽，未成功取得真实版本信息，真实联网测试如实判为失败。没有把它改成模拟成功；回执 `.local/settings-update-live-1.log` 与[联网 JSON](screenshots/settings-update/live-connectivity.json)。
- 已检查[完整设置页](screenshots/settings-update/settings.png)和[真实 403 错误界面](screenshots/settings-update/live-connectivity.png)截图，无系统弹窗遮挡。后者是用生产设置组件运行的联网测试界面，省略了外层导航栏；[新版本提示](screenshots/settings-update/update-available.png)使用注入测试响应，属于界面验证。
- 首轮设备进程在冷启动阶段 ANR，未执行到功能测试；正常启动后恢复。完整回归第二轮有一项测试在设置页完成渲染前触发系统返回，已调整测试为确认页面显示后再返回，最终整套回归通过。失败回执保留在 `.local/settings-update-device-1.log`、`.local/settings-update-runtime-1.log`、`.local/settings-update-device-2.log`。

检查更新的开发和本地回归已完成；在线获取真实版本目前受 GitHub 限流影响，真实 APK 下载及覆盖升级尚未验证。发布要求见[应用更新说明](app-updates.md)。本轮未提交或推送。

## 通知重复记账、折叠与缓冲（2026-10-15）

修复前在 API 35 隔离模拟器中新增 3 个复现用例，连同原有 4 项通知测试运行。原样重复投递同一条招商通知只产生 1 条账目；另外两个断言失败：同一 Android 通知标识更新时间后，待确认项能在未勾选重复确认的情况下再次入账；微信与银行映射不同账户时，第二条同额支出通知返回 `AUTO_POSTED`。回执 `.local/duplicate-before-fix-device-tests.log` 为 `Tests run: 7, Failures: 2`。这证明了代码存在相应路径，尚不能据此确定手机上那笔真实交易经过了哪些系统回调。

修复后，采集、缓冲到期检查、确认和界面折叠使用同一套匹配规则。同一通知标识、同账户、同额同方向的时间戳变化不会绕过检查；通知标识可能被应用复用，因此保留核对真实新交易的入口。两分钟内不同来源的同额支出通知，即使账户不同，也只允许第一条自动入账，其余作为候选折叠在已入账记录下。默认保留第一条的账户，只有明确勾选是另一笔交易才能追加。收入与支出、不同金额、不同账户的普通 CSV 记录不按跨通知规则折叠。

自动入账先等待 8 秒，期限持久化到 SQLite；到期在事务中重新检查已入账记录。应用启动和通知监听重连会恢复调度，系统暂停进程可能延后执行。缓冲中的候选不影响余额、统计和导出。数据库 v1 → v2 仅增加 `auto_post_at` 列，保留历史入账、待确认及已忽略记录；没有自动删除或改写已入账的历史重复项。

- 离线 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 成功，35 项 JVM 测试全部通过，Lint 0 errors / 27 warnings，未新增依赖或关闭检查。回执 `.local/duplicate-buffer-build.log`。
- 完整设备回归 `OK (37 tests)`：10 项通知、5 项缓冲/迁移、2 项折叠 UI，以及已有 SQLite、账本 UI、Sheets 和 Drive 测试。回执 `.local/duplicate-buffer-device-tests.log`。
- 最后禁止缓冲记录提前确认，并让确认对话框跟随当前记录状态更新。最终构建、JVM 测试与 Lint 成功，回执 `.local/duplicate-final-build.log`；3 项折叠 UI 复验为 `OK (3 tests)`，回执 `.local/duplicate-final-ui-tests.log`。其中新增用例检查等待合并时入口禁用、到期后余额更新。
- 真实定时器用例等待微信晚到 5 秒，检查 8 秒前总余额仍为 200 元，到期后为 177 元，只导出 1 笔 23 元支出；另覆盖到期前 1 毫秒不入账、关闭重开数据库恢复期限、忽略取消自动入账、等待期间出现 CSV 入账后不重复扣账。
- 同一招商通知标识在原样重投、1 秒后更新时间、3 分钟后更新时间时，均验证不会未经明确允许再次扣账。折叠 UI 验证默认只有一张金额卡、可保留一笔、可明确追加另一笔，以及切换筛选后候选仍可访问。
- 复现与修复使用合成通知构造实际 `StatusBarNotification`，并调用生产监听器共用的提取、解析、SQLite 记账路径。尚未接收手机上的真实招商/微信推送，也未读取或修复手机账本。没有提交或推送。
- 已查看最终[通知折叠](screenshots/notification-folding/folded.png)、[重复核对](screenshots/notification-folding/review.png)和[等待合并](screenshots/notification-folding/buffered.png)截图，没有系统弹窗遮挡。测试数据使用独立临时数据库和设置，结束后设备数据库目录仅保留 `openwallet.db` 及其 journal。
- 本轮安装包副本：`.local/notification-fix-20261015/app-debug.apk`。独立构建输出位于 `.local/auth-feedback-build-20261015/app/outputs/apk/debug/app-debug.apk`，标准 `app/build` 中的旧 APK 不代表本轮结果。

对照了现有开源项目 [expense-notification](https://github.com/fanis/expense-notification) 的跨来源重复核对思路及仓库已有参考 [seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)；本次规则和事务逻辑由本仓库实现。

## Google 云盘每日 CSV（2026-10-15）

- 最终离线运行 `assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug` 成功，安装包为 `app/build/outputs/apk/debug/app-debug.apk`。未新增依赖，依赖锁保持原样。回执：`.local/drive-verified-build.log`。
- 当前工作区 JVM 测试共 33 项通过，其中新增 `DriveExportTest` 7 项：本地日期边界、待确认过滤与 CSV 转义、固定 15 分钟重试期限、重复触发不重置、初次加 5 次重试上限、跨天与漏跑日期补传、暂停/授权失效以及同日快照替换。另保留工作区同期的 Sheets 授权诊断修改，其 6 项单元测试也通过。
- Android 15 / API 35 AOSP 隔离模拟器完成 11 项设备测试：`DriveIntegrationTest` 5 项、`DriveUiTest` 2 项、已有 `SheetsIntegrationTest` 4 项。回执：`.local/drive-verified-install.log`、`.local/drive-verified-device-tests.log`，结果 `OK (11 tests)`。
- 模拟网关检查覆盖预生成 ID、创建响应丢失后的恢复、409 冲突后核对并更新、CSV MIME / multipart 请求、文件夹与文件归属校验、账户间目标隔离及重试队列持久化。UI 覆盖邮箱校验、手动重试后清除旧错误、暂停和缺少 Google Play 服务的明确提示。临时设置使用独立名称并在测试后清理，没有连接真实 Google 账户。
- Lint：0 errors、36 warnings，主要为可选依赖升级、KTX 写法与现有备份规则提示；未关闭规则或升级依赖。`git diff --check` 通过。
- 初次截图被模拟器的 System UI 无响应弹窗遮挡；正常点击系统「Wait」恢复后重新执行测试并检查截图。旧图保留在 `screenshots/drive-update/initial`，最终使用 [授权入口](screenshots/drive-update/verified/connect.png)、[手动重试](screenshots/drive-update/verified/retry.png)、[服务缺失提示](screenshots/drive-update/verified/services-unavailable.png)。截图中的账户和状态由隔离测试注入，不代表真实授权或云盘写入。
- 当前模拟器缺少 Google Play 服务。真实 OAuth、后台令牌续期、Drive 文件夹创建/CSV 写入以及真机休眠恢复尚未验证；需按 [云盘配置](google-drive.md) 在有 Google Play 服务且配置好 Android OAuth 的设备上验收。自动任务受系统调度影响，不能将 15 分钟最小间隔解释为精确计时保证。

## PR 提交前复验（2026-10-15）

- 使用现有依赖缓存离线运行 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`，构建成功；未新增依赖或修改锁文件。本机回执：`.local/pr-validation-20261015.log`。
- 账单转 CSV 脚本的合成数据检查通过：UTF-8 BOM、CSV 往返、金额合计、公式前缀转义、前导零流水号，以及重复流水号、错误账户和非法金额的拒绝。本检查不覆盖原始银行文件提取或 OCR。
- 重新核对下述已有模拟器测试日志和最终页面截图；本次提交前复验未重新运行设备测试。真实通知、Google OAuth 和云端写入仍按下述限制保留为未验证。

## 悬浮毛玻璃导航（2026-10-15）

底部「资产 / 收支」改为圆角悬浮栏，两侧 20 dp、底部 12 dp 留白，并避让系统导航区。Android 12 及以上使用 Compose GraphicsLayer 捕获栏后的页面，在导航区域内执行 22 dp 背景模糊；高光描边、半透明渐变和阴影构成玻璃表面，图标与文字独立绘制以保持清晰。旧系统使用较高不透明度的半透明背景，尚未在旧系统设备上实测。没有新增依赖或修改依赖锁。

实现前查阅了 GitHub 的 [Haze](https://github.com/chrisbanes/haze) 和 [Glassy Bottom Navigation](https://github.com/ditacristianionut/Glassy-Bottom-Navigation)；本次使用现有 Compose API 实现，GraphicsLayer 捕获方式参照 [Android 官方说明](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers#composable-to-bitmap)。

- 最终 `assembleDebug assembleDebugAndroidTest lintDebug` 成功；Lint 0 errors、26 warnings，未关闭检查。
- Android 15 / API 35 模拟器的现有隔离 UI 流程通过：`OK (1 test)`，覆盖示例账本、列表滚动、两页切换、手工记账和通知设置。未使用真实账本写入测试数据，测试后数据库目录仅剩 `openwallet.db`。
- 已查看最终[资产页](screenshots/floating-navigation/assets.png)、[收支页](screenshots/floating-navigation/transactions.png)截图，并检查[滚动后的资产页](screenshots/floating-navigation/assets-scrolled.png)。列表末尾留白使最后一项可以滚动到浮栏和记账按钮上方；系统三键导航区保持无遮挡。
- 本机日志：`.local/floating-nav-final-build.log`、`.local/floating-nav-final-ui-test.log`。APK：`app/build/outputs/apk/debug/app-debug.apk`。此前截图和失败日志保留。

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
