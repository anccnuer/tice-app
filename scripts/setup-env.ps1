<#
  纯命令行准备 Android 构建环境（不安装 Android Studio，不修改系统环境变量）。
  所有工具落在工作区 .tools/：Temurin JDK 17、Android SDK cmdline-tools、Gradle Wrapper jar。
  用法：powershell -ExecutionPolicy Bypass -File scripts/setup-env.ps1
#>
[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tools = Join-Path $root '.tools'
$sdk = Join-Path $tools 'android-sdk'
$jdk = Join-Path $tools 'jdk17'
New-Item -ItemType Directory -Force -Path $tools | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Fetch([string]$url, [string]$dest) {
    if (Test-Path $dest) {
        if ((Get-Item $dest).Length -gt 4096) { Write-Host "  已存在，跳过: $(Split-Path $dest -Leaf)"; return $true }
        Remove-Item $dest -Force
    }
    Write-Host "  下载: $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -MaximumRedirection 10 -TimeoutSec 900
        return (Test-Path $dest)
    } catch {
        Write-Warning "  下载失败: $($_.Exception.Message)"
        Remove-Item $dest -Force -ErrorAction SilentlyContinue
        return $false
    }
}
function Unzip([string]$zip, [string]$dest) {
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

# ---------- 1. JDK 17（AGP 8.x 硬性要求，本机 JDK 25 不兼容 Gradle 8.7） ----------
if (-not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    Step '准备 Temurin JDK 17'
    $jdkZip = Join-Path $tools 'jdk17.zip'
    $jdkUrls = @(
        'https://repo.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip',
        'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
    )
    $ok = $false
    foreach ($u in $jdkUrls) { if (Fetch $u $jdkZip) { $ok = $true; break } }
    if (-not $ok) { throw 'JDK 17 下载失败，请检查网络后重试' }
    $jdkTmp = Join-Path $tools 'jdk17-extract'
    Unzip $jdkZip $jdkTmp
    $inner = Get-ChildItem -Path $jdkTmp -Directory | Select-Object -First 1
    if (-not $inner) { throw 'JDK 压缩包结构异常：未找到顶层目录' }
    if (Test-Path $jdk) { Remove-Item $jdk -Recurse -Force }
    Move-Item -Path $inner.FullName -Destination $jdk
    Write-Host "  JDK: $jdk"
}
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

# ---------- 2. Android SDK cmdline-tools ----------
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Step '准备 Android SDK cmdline-tools'
    $cliZip = Join-Path $tools 'cmdline-tools.zip'
    if (-not (Fetch 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $cliZip)) { throw 'cmdline-tools 下载失败' }
    $cliTmp = Join-Path $tools 'cmdline-tools-extract'
    Unzip $cliZip $cliTmp
    $latest = Join-Path $sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
    if (Test-Path $latest) { Remove-Item $latest -Recurse -Force }
    Move-Item -Path (Join-Path $cliTmp 'cmdline-tools') -Destination $latest
}

Step '接受 Android SDK 许可协议'
$yes = "y`r`n" * 30
try { $yes | & $sdkmanager --sdk_root="$sdk" --licenses 2>&1 | Out-Null } catch { Write-Warning '许可确认步骤返回非零，继续尝试安装' }

Step '安装 platform-tools / android-35 / build-tools 35.0.0'
& $sdkmanager --sdk_root="$sdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager 安装失败，退出码 $LASTEXITCODE" }

# ---------- 3. Gradle Wrapper ----------
$wrapperJar = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
if ((-not (Test-Path $wrapperJar)) -or $Force) {
    Step '准备 Gradle Wrapper'
    $gradleZip = Join-Path $tools 'gradle-8.7-bin.zip'
    if (-not (Fetch 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $gradleZip)) { throw 'Gradle 发行版下载失败' }
    $gradleDist = Join-Path $tools 'gradle-dist'
    $gradleBin = Join-Path $gradleDist 'gradle-8.7\bin\gradle.bat'
    if (-not (Test-Path $gradleBin)) { Unzip $gradleZip $gradleDist }
    # 在独立空构建中生成 Wrapper，--no-validate-url 避免联网校验 distribution URL 失败
    $work = Join-Path $tools 'wrapper-work'
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $work 'settings.gradle.kts'), 'rootProject.name = "wrapper-gen"' + "`r`n", $utf8NoBom)
    Push-Location $work
    try {
        & $gradleBin wrapper --gradle-version 8.7 --distribution-type bin --no-validate-url --no-daemon --console=plain | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'gradle wrapper 任务执行失败' }
    } finally { Pop-Location }
    $generated = Join-Path $work 'gradle\wrapper\gradle-wrapper.jar'
    if (-not (Test-Path $generated)) { throw '未生成 gradle-wrapper.jar' }
    Copy-Item -Path $generated -Destination $wrapperJar -Force
    foreach ($name in @('gradlew.bat', 'gradlew')) {
        $src = Join-Path $work $name
        if (Test-Path $src) { Copy-Item -Path $src -Destination (Join-Path $root $name) -Force }
    }
    Write-Host "  Wrapper jar: $wrapperJar"
}

# ---------- 3.5 预置 Gradle 发行版到工作区缓存，绕过 CDN 的 TLS 证书问题 ----------
# 目录名来自 Gradle PathAssembler：distributionUrl 的 MD5 再转 base36，8.7-bin 固定为 bhs2wmbdwecv87pi65oeuq5iu
$gradleHome = Join-Path $tools 'gradle-home'
$distDir = Join-Path $gradleHome 'wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$distZip = Join-Path $tools 'gradle-8.7-bin.zip'
if (-not (Test-Path $distZip)) {
    if (-not (Fetch 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $distZip)) { throw 'Gradle 发行版下载失败' }
}
if (-not (Test-Path (Join-Path $distDir 'gradle-8.7\bin\gradle.bat'))) {
    Step '预置 Gradle 发行版（离线启动）'
    Copy-Item -Path $distZip -Destination (Join-Path $distDir 'gradle-8.7-bin.zip') -Force
    Expand-Archive -Path (Join-Path $distDir 'gradle-8.7-bin.zip') -DestinationPath $distDir -Force
}
[System.IO.File]::WriteAllText((Join-Path $distDir 'gradle-8.7-bin.zip.ok'), '')

# ---------- 4. 写入本机配置（不影响系统环境变量） ----------
Step '写入 local.properties 与 gradle.properties'
[System.IO.File]::WriteAllText((Join-Path $root 'local.properties'), "sdk.dir=$($sdk.Replace('\','/'))`r`n", $utf8NoBom)
$propsPath = Join-Path $root 'gradle.properties'
$lines = @(Get-Content -Path $propsPath | Where-Object { $_ -notmatch '^\s*org\.gradle\.java\.home\s*=' })
$lines += "org.gradle.java.home=$($jdk.Replace('\','/'))"
[System.IO.File]::WriteAllLines($propsPath, [string[]]$lines, $utf8NoBom)

Step '环境就绪'
Write-Host "  JDK      : $jdk"
Write-Host "  SDK      : $sdk"
Write-Host "  Wrapper  : $wrapperJar"
Write-Host "  下一步   : powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1"
