# Google 云盘每日 CSV

在「设置 → Google 云盘每日 CSV」输入设备上已登录的 Google 邮箱，点击「连接并开启」并完成授权。应用会安排联网任务，在此账户的「我的云端硬盘」根目录创建 **OpenWallet 每日 CSV** 文件夹，写入 `openwallet-YYYY-MM-DD.csv`。可以与 Google Sheets 自动导出分别启用、暂停。

## 一次性配置

沿用 [Google Cloud 配置](google-sheets.md#一次性-google-cloud-配置) 中的 Android OAuth 客户端、实际 APK 包名和签名证书；只使用云盘 CSV 时需要启用 Google Drive API，无需启用 Sheets API。授权范围是 `drive.file` 与 `userinfo.email`。设备需有 Google Play 服务，并能连接 Google API。授权由用户在设备上完成，源码无需 client secret，也不保存访问令牌或 refresh token。

## 文件与每日调度

- CSV 使用标准七列、UTF-8 BOM、CRLF，复用手动导出的金额精度、引号与公式前缀转义规则，可在 OpenWallet 中预览导入。按设备本地日期筛选，按时间和本地 ID 排序；只包含已入账流水。空白日写表头，不上传通知候选和原始通知正文。
- 授权启用后立即安排上传；手工记账、CSV 导入、通知入账和移回待确认也触发检查。持久 WorkManager 每隔至少 15 分钟检查并刷新当天同一个 CSV，而非每天累积多个同名文件。系统休眠、厂商后台限制与网络条件可能延迟执行，不承诺某一整点上传。
- 跨天时再次更新前一天，收齐当天最后的流水。从启用日起未执行的日期会补建，每次任务最多处理 3 天，后续周期继续处理。进程退出、重启、断网不会清除已保存的日期队列；强制停止后需重新打开应用恢复工作。
- 每个 Google 账户分别保存文件夹、每日文件 ID 与队列。先取得 Drive 预生成 ID 并持久保存，再创建远程对象；创建响应丢失后复用原 ID，遇到 409 则校验已有目标并更新。已完成的往日文件不会因之后补录旧日期流水而自动重新生成；当天和跨天收尾属于此功能的自动更新范围。
- CSV 是应用管理的每日快照，手动修改其内容会在下次对应日期上传时被覆盖。请复制文件后再做个人编辑。暂停保留本地账本、云端文件及待处理任务；恢复连接后继续补传。切换、暂停和工作线程串行处理，等待正在进行的请求结束。

## 固定重试

| 情况 | 行为 |
| --- | --- |
| 连接/读取超时、网络异常、HTTP 408 / 429 / 5xx、明确限流的 403 | 初次失败后至少间隔 15 分钟重试，最多重试 5 次，共最多 6 次尝试 |
| 重试尚未到期 | 记账变化、重启与其他调度不会重置计数或提前发请求 |
| 重试耗尽或目标被移动、删除、更改 | 保留该日期任务，显示错误；点击「立即重试」重置未完成任务的计数，并安排联网上传 |
| OAuth 失效、授权账户不匹配或普通 403 | 停止后台上传并提示重新授权；「授权并启用」恢复该账户任务 |
| 到了下一天 | 新日期有独立重试计数，旧日期失败任务继续保留 |

重试计数和下一次允许时间保存在应用私有设置中。WorkManager 周期轮询执行到期任务，因此实际等待可能超过 15 分钟；不使用指数退避，也不会因每次轮询重新开启一轮重试。用户主动点击「立即重试」或重新授权属于新的重试轮次。

写入前校验文件所有者、安装标记、日期和父文件夹。文件被移走或放入回收站时先恢复原目标，再点击重试。应用不会删除云盘文件。清除应用数据或卸载会丢失本地目标 ID 和队列；再次连接会新建文件夹，原目录仍保留。

## 实现依据与验收范围

实现前检查了 [googleworkspace/android-samples](https://github.com/googleworkspace/android-samples)。该官方仓库已归档，未采用其旧登录方式；授权复用项目现有的 [AuthorizationClient](https://developer.android.com/identity/authorization)，文件夹及上传分别遵循 [Drive folder guide](https://developers.google.com/workspace/drive/api/guides/folder) 和 [Drive upload guide](https://developers.google.com/workspace/drive/api/guides/manage-uploads#use_a_pre-generated_id_to_upload_files)。没有新增依赖或变动依赖锁；共用 HTTP 与授权实现也供已有 Sheets 导出使用。

本地验证使用隔离账本、临时设置和模拟 Drive HTTP 响应，涵盖日期筛选、固定重试和上限、跨天补传、响应丢失后的同 ID 恢复、账户隔离、目标校验及设置界面。实际命令和结果见 [验证记录](verification.md)。当前 AOSP 测试模拟器没有 Google Play 服务，真实 OAuth、令牌续期和真实云盘写入仍需在配置好 OAuth 的设备上验收。
