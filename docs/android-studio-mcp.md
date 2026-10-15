# Android Studio MCP 配置记录

## 当前配置

- IDE：`D:\AndroidStudio`，Android Studio Quail 2 / 2026.1.2，平台 build `261.25134.95.2612.15822958`。
- 插件：JetBrains 官方 `com.intellij.mcpServer`，版本 `261.25134.203`，兼容范围 `261.25134` 至 `261.*`。
- 安装包来自 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26071-mcp-server)，解压在本机 `.local/studio-plugins`。
- Codex 服务名：`android-studio`，Streamable HTTP 地址 `http://127.0.0.1:64342/stream`。
- `enableBraveMode=false`：保留命令执行确认；没有修改 Codex Auto Review、分支保护或其他 MCP 配置。

已通过 MCP `initialize` 收到服务名 `Android Studio MCP Server`、版本 `Quail 2 | 2026.1.2`；`tools/list` 返回 27 个工具；`get_file_text_by_path` 成功读取本项目 `app/src/main/AndroidManifest.xml`。这验证了实际工具访问，不能据此推断所有 IDE 工具或原生 Android 调试流程均已验证。

## 无界面运行

官方插件自带 `mcpServer` headless 启动入口。当前机器可运行：

```powershell
.\scripts\start-android-studio-mcp.ps1
```

该脚本调用 Android Studio 自带 `studio.bat mcpServer --port=64342 --project=...`，使用项目独立配置、1 GB 堆内存和 2 个处理器，不需要操作前台 IDE。进程必须保持运行；它不是 Windows 开机自启服务。关闭后重新运行脚本即可。

已保存的 Codex 配置等价于：

```toml
[mcp_servers.android-studio]
url = "http://127.0.0.1:64342/stream"
```

新 Codex 会话加载该注册项。本次工作中的连通性使用本机 MCP 协议调用直接验证；已有会话的工具列表是否热加载取决于客户端。

## 普通 IDE

```powershell
.\scripts\start-android-studio.ps1
```

普通 IDE 使用 `.local/studio.properties` 的独立配置。若无界面实例已占用 64342，应先正常结束该实例再启动普通 IDE 的 MCP 服务，避免端口冲突。普通 IDE 里可在 `Settings → Tools → MCP Server` 检查服务状态，必要时完成首次启动向导；无需为 MCP 登录 Gemini。

## 保留的故障与限制

原默认 IDE 缓存目录 `C:\Users\Logan\AppData\Local\Google\AndroidStudio2026.1.2` 中的 `.port` 文件导致启动时 FileSystemException。本次未删除、改名或清理原目录，改用独立配置缓存。普通 IDE 曾停留在首次登录向导；后续实际连接验证使用官方 headless 入口完成。

`.local` 包含本机插件、IDE 缓存与验证日志，未纳入 Git。其他机器需要先安装兼容的官方插件并配置各自 IDE 路径，不能直接依赖本机绝对路径。

官方说明：[JetBrains MCP Server](https://www.jetbrains.com/help/idea/mcp-server.html)、[Codex MCP 配置](https://developers.openai.com/codex/mcp)。
