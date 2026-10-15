param([string]$StudioHome = 'D:\AndroidStudio', [int]$Port = 64342)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$plugins = Join-Path $root '.local\studio-plugins'
if (!(Test-Path (Join-Path $plugins 'mcpserver\lib\mcpserver.jar'))) {
    throw '先安装兼容的 JetBrains 官方 MCP Server 插件，参阅 docs/android-studio-mcp.md。'
}
if ($Port -lt 1024 -or $Port -gt 65535) { throw '端口须在 1024–65535 之间' }
$config = Join-Path $root '.local\mcp-headless-config'
$options = Join-Path $config 'options'
New-Item -ItemType Directory -Force $options | Out-Null
$settings = Join-Path $options 'mcpServer.xml'
if (!(Test-Path $settings)) {
    @"
<application><component name="McpServerSettings">
  <option name="enableMcpServer" value="true" />
  <option name="enableBraveMode" value="false" />
  <option name="mcpServerPort" value="$Port" />
</component></application>
"@ | Set-Content -Encoding utf8 $settings
}
$properties = Join-Path $root '.local\mcp-headless.properties'
$portableRoot = $root.Replace('\', '/')
@"
idea.config.path=$portableRoot/.local/mcp-headless-config
idea.system.path=$portableRoot/.local/mcp-headless-system
idea.plugins.path=$portableRoot/.local/studio-plugins
idea.log.path=$portableRoot/.local/mcp-headless-log
"@ | Set-Content -Encoding utf8 $properties
$vmoptions = Join-Path $root '.local\mcp-headless.vmoptions'
if (!(Test-Path $vmoptions)) {
    (Get-Content (Join-Path $StudioHome 'bin\studio64.exe.vmoptions')) -replace '^-Xmx\S+$', '-Xmx1024m' | Set-Content $vmoptions
    Add-Content $vmoptions '-XX:ActiveProcessorCount=2'
}
$env:STUDIO_PROPERTIES = $properties
$env:STUDIO_VM_OPTIONS = $vmoptions
& (Join-Path $StudioHome 'bin\studio.bat') mcpServer "--port=$Port" "--project=$root"
exit $LASTEXITCODE
