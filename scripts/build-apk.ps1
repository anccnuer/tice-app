<#
  命令行构建与本机单元测试（不依赖 Android Studio）。
  用法: powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1
        powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1 -Install
#>
[CmdletBinding()]
param([switch]$Install)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jdk = Join-Path $root '.tools\jdk17'

if (Test-Path (Join-Path $jdk 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;$env:PATH"
}
# 使用工作区内预置的 Gradle 发行版与依赖缓存，避免联网下载发行版
$env:GRADLE_USER_HOME = Join-Path $root '.tools\gradle-home'

Push-Location $root
try {
    & .\gradlew.bat assembleDebug testDebugUnitTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    Write-Host "`nAPK: $apk" -ForegroundColor Green
    Write-Host "大小: $([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB"

    if ($Install) {
        $adb = Join-Path $root '.tools\android-sdk\platform-tools\adb.exe'
        if (-not (Test-Path $adb)) { Write-Warning '未找到 adb，跳过安装' } else { & $adb install -r $apk }
    }
}
finally { Pop-Location }
