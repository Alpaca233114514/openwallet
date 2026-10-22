# 本机 Excel 自动导出

在「设置 → Excel 自动导出」点击「选择文件夹并开启」，通过 Android 系统文件管理器选择本机目录并确认授权。例如先在 Documents 中新建 OpenWallet 文件夹，再选择它。Android 11 及以上不能直接选择内部存储根目录或 Download 根目录。

应用创建 `OpenWallet-流水.xlsx`，以后更新同一文件。目标目录若已有同名文件，系统文档提供程序会为新文件分配不同名称；应用只更新自己创建并记录 URI 的文件。目录切换后保留旧文件，回到原目录时沿用该目录的导出文件。Excel 文件是应用管理的账本快照，手工编辑的内容会在下次导出时被覆盖，需要编辑时先复制一份。

手工记账、CSV 导入、通知入账、移回待确认等账本变化会触发 WorkManager 任务；应用启动时恢复任务并导出，另有至少 15 分钟周期的后台补偿。实际执行时间受 Android 调度和省电限制。支持「立即导出」「暂停导出」「继续自动导出」和「打开 Excel 文件」。本机目录导出无需联网或 Google 账户；打开文件需要设备上有 Excel、WPS 等支持 `.xlsx` 的应用。

工作簿只有「已入账流水」工作表，包含时间、账户、类型、金额（元）、交易方、分类、流水号、来源、本地记录 ID。金额为可求和的数值，显示两位小数；其余列为文本，保留流水号前导零和长编号，交易方中的公式前缀也作为普通文本。首行冻结、启用筛选并设置列宽。待确认、缓冲中、已忽略的通知均不导出。

使用系统 Storage Access Framework 持久化目录授权，不申请全盘存储权限。权限失效或目录/文件不可用时停止并提示重新选择；临时写入失败按指数退避重试，最多 5 次，之后等待下次周期或手动触发。先在应用缓存中生成完整 `.xlsx`，再截断并写入目标文件。文档提供程序不保证原子替换，若写入时存储中断，导出副本可能暂不完整；应用保留失败状态，下一次成功会重写副本，SQLite 原账本不受影响。

每次自动导出先检查持久读写授权，避免目录选择器留下的临时权限掩盖授权已撤销的状态。重新选择文件夹可恢复授权；仅在旧页面仍能访问目录并不代表后台授权有效。

升级后旧 Google Sheets 的周期与变更任务会取消；即使旧 Worker 被恢复也不会调用 Google。已有 Google 表格和旧配置元数据保留，Excel 默认关闭，需自行选择本机目录。Google 云盘每日 CSV 已移除，升级时停止旧上传任务，已有云盘文件保留。

格式实现使用 Java 标准库 ZIP/XML 写出 SpreadsheetML，未新增依赖或复用第三方代码。开发前查阅了 GitHub 的 [ExcelKt](https://github.com/evanrupert/ExcelKt) 与 [DroidXLS](https://github.com/youichi-uda/droidxls)，本功能只写出单张流水表，采用较小的自有写出器。结构参考 [Microsoft SpreadsheetML 文档](https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/structure-of-a-spreadsheetml-document)，目录授权参考 [Android 官方文档](https://developer.android.com/training/data-storage/shared/documents-files)。
