param([string]$StudioHome = 'D:\AndroidStudio')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$properties = Join-Path $root '.local\studio.properties'
if (!(Test-Path -LiteralPath $properties)) { throw '缺少本机 IDE 配置，请参阅 docs/android-studio-mcp.md' }
$env:STUDIO_PROPERTIES = $properties
Start-Process (Join-Path $StudioHome 'bin\studio64.exe') -ArgumentList ('"' + $root + '"') -WindowStyle Hidden
