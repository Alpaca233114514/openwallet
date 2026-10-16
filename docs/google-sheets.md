# Google Sheets 自动导出

> 历史功能说明：2026-10-16 起，该入口已替换为[本机 Excel 自动导出](excel-export.md)。升级后取消旧 Sheets 后台任务，保留已有云端表格与账户元数据；下文仅保留原实现的配置记录。

## 一次性 Google Cloud 配置

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 创建或选择自己的项目，启用 **Google Sheets API** 和 **Google Drive API**。
2. 配置 Google Auth Platform 的应用信息、受众和数据访问。个人测试可使用 External / Testing，并把目标 Google 邮箱加入测试用户。
3. 创建 **Android** 类型 OAuth 客户端，包名填 `io.openwallet.demo`，填写所安装 APK 签名证书的 **SHA-1**。可运行 `./gradlew signingReport` 查看；debug、release、Google Play App Signing 的证书可能不同，需分别注册实际使用的证书。
4. 声明 `https://www.googleapis.com/auth/drive.file` 和 `https://www.googleapis.com/auth/userinfo.email`。前者限定到本应用使用/创建的文件，后者用于核对指定账户。
5. 在具备 Google Play 服务、能连接 Google API 的 Android 设备上登录目标 Google 账户，安装 APK，然后进入「设置 → Google Sheets 自动导出」，输入该账户的准确邮箱并授权。别名与账户实际返回邮箱不一致时会拒绝导出。

此实现由 Google Play 服务按包名与签名识别 Android OAuth 客户端，无需在源码中加入 client secret、服务账户密钥或 Web 客户端 ID。短期访问令牌只在内存中使用，后续授权交给 Google Play 服务管理；不保存 refresh token。系统授权不能由代码代替用户点击。

## 行为

- 默认关闭。连接成功后开启自动导出，初次联网执行时创建专用表格；创建请求中断后，重试先用应用私有属性查找之前创建的文件。Google 文件索引延迟等极端情形仍可能留下额外空表，不能将其视为严格的远端 exactly-once 创建。
- 每次导出先重新取得令牌，再验证返回邮箱；写入前检查文件仍归当前账户所有，且具有本安装的导出标记。切换账户与网络导出串行处理，旧请求结束后才切换。
- 本地事务提交后安排 WorkManager 任务，涵盖前台记账、CSV 导入及后台通知。另保留最短 15 分钟的持久周期任务，用于断网、应用进程退出等情况的补偿；休眠、省电、厂商后台限制和强制停止都会影响及时性。重新打开应用会恢复周期调度。
- 只导出当前已入账流水，按时间及本地记录 ID 排序。列为时间、账户、类型、金额（元）、交易方、分类、流水号、来源、本地记录 ID；日期采用设备当前时区。
- 使用一次 Sheets `batchUpdate` 扩容并替换 A:I 单元格值，移回待确认的旧记录从快照中清除；空账本只保留表头。重试重复写入同一快照，不追加重复流水。保留表格格式与其他工作表。
- 所有值使用 `stringValue`，避免交易方等字段被解析为公式，也保留金额的小数精度及流水号前导零。若需数值运算，在分析页使用 `VALUE` 转换金额。
- 网络、429、部分限流 403 和 5xx 会指数退避重试；单次任务达到重试上限后由下一轮周期检查或手动触发继续。授权撤销/账户不匹配会停止上传并提示重新授权。表格丢失或删除工作表会显示错误，不覆盖用户其他表格。
- 「暂停导出」会等待当前请求结束，关闭后续调度；保留本地账本、云端表格和账户对应的目标 ID。重新授权并启用会继续更新原表。可以在 Google 账户第三方连接页撤销授权。

首次创建的专用工作表 A:I 归应用管理，请在其他工作表添加个人分析。卸载/清除应用数据后会丢失安装标记和目标 ID，再次连接会建立新导出文件；原云端文件不会被删除。

## 依赖与参考

新增 `com.google.android.gms:play-services-auth:21.4.0` 和 `androidx.work:work-runtime-ktx:2.10.1`，安装前读取 Google 官方 Maven 的 POM，检查其来源、AAR 包装与传递依赖；没有引入安装脚本。Gradle 更新并保留依赖锁，未整体升级已有依赖。

实现前检查了 [googleworkspace/android-samples](https://github.com/googleworkspace/android-samples)，该仓库已归档并标明示例可能过时，故未复制其旧登录代码。授权采用 [Android AuthorizationClient 官方指南](https://developer.android.com/identity/authorization)，范围参考 [Sheets scopes](https://developers.google.com/workspace/sheets/api/scopes)，快照更新遵循 [UpdateCellsRequest](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/request#UpdateCellsRequest)。

## 验收边界

本地单元测试与 Android 设备测试使用合成账本、隔离设置与模拟网关；没有向真实 Google 账户发送账本。当前测试模拟器是 AOSP 镜像，不具备 Google Play 服务，不能据此验证 OAuth 弹窗、Google 后台续期和真实云端写入。完成上述 Cloud 配置后，还需在有 Google Play 服务的设备上用测试账本验证首次授权、重启后自动导出、断网恢复、切换账户和撤销授权。
