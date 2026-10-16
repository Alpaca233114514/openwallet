# 设置与应用更新

右上角齿轮打开独立设置页。账本导入/导出、通知记账、Google Sheets 和 Google 云盘的入口集中在此；设置页隐藏账本底部导航与记账按钮，顶部返回或系统返回恢复之前的资产/收支分页。即使尚未创建账户也能进入设置。

## 检查和下载

更新源固定为 [Alpaca233114514/openwallet Releases](https://github.com/Alpaca233114514/openwallet/releases)，通过无凭证的 HTTPS 请求访问 `GET /repos/Alpaca233114514/openwallet/releases/latest`。只接受正式发布，按 SemVer 比较当前安装包版本和 release 的 `tag_name`，支持 `v` 前缀、预发布排序、忽略 build metadata；同版本或旧版本不提示升级。

设置页显示安装包实际的 `versionName` 与 `versionCode`。检查请求在 IO 线程运行，以 ViewModel 保存状态；检查中不能重复发起，界面重建后继续显示结果。失败后可以重试。GitHub 返回 404 显示「暂未找到公开发布版本」，包括仓库不可公开访问或尚未发布的情况，不宣称已是最新。403/429 提示访问限制，超时、断网、服务错误及格式问题各有提示。

发现新版本时展示版本号和发布说明。若 release 中有已上传的 `.apk` 附件，提供「前往下载更新」；否则显示「该版本尚未上传 APK 安装包」和「查看新版本」。两者都打开本仓库的对应发布页，由浏览器完成下载，再由 Android 系统安装流程确认安装。此轮范围是检查和发布页下载入口，不包含应用内下载进度或静默安装。

## 后续发布要求

1. 在 `app/build.gradle.kts` 更新版本，`versionCode` 必须严格递增；`versionName` 使用 `主版本.次版本.修订号`，例如 `0.2.0`。
2. 使用与设备上已安装应用一致的签名生成 APK，并保持应用包名不变。不同签名的安装包不能直接覆盖安装；长期发布应使用固定并妥善保管的发布签名。
3. 创建对应标签，例如 `v0.2.0`，发布一个公开的正式 GitHub Release，填写更新说明，并上传兼容目标设备的 APK。草稿和标记为 pre-release 的版本不会作为此次检查的升级目标。
4. 发布标签必须与 APK 的 `versionName` 一致。更新检查比较标签，Android 安装时仍以 APK 的 `versionCode`、签名与系统要求判断能否覆盖安装。

未发布真实新版本或未完成覆盖安装时，本地测试只能证明比较、错误处理、界面及入口行为，不能作为真实升级安装成功的证据。

## 实现参考

增加功能前查看了 [pavi2410/kmp-app-updater](https://github.com/pavi2410/kmp-app-updater) 的 GitHub Release / Compose 更新方案，采用相同的检查、状态展示及安装入口分离思路。本仓库的实现独立编写，未复制该项目源码、未引入其依赖。[GitHub Releases REST API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) 是协议依据。
