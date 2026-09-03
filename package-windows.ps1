param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "target"))
$distributionRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "dist"))
$windowsRoot = [System.IO.Path]::GetFullPath((Join-Path $distributionRoot "windows"))
$stagingRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "jpackage-input"))
$applicationRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot "弈言"))
$packagingRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot (".staging-" + [Guid]::NewGuid().ToString("N"))))
$sourceJar = Join-Path $targetRoot "yiyan-go.jar"

function Assert-ChildPath {
    param(
        [string]$Path,
        [string]$Parent
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $resolvedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd("\") + "\"
    if (-not $resolvedPath.StartsWith($resolvedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不在预期目录内：$resolvedPath"
    }
}

Assert-ChildPath -Path $stagingRoot -Parent $targetRoot
Assert-ChildPath -Path $applicationRoot -Parent $windowsRoot
Assert-ChildPath -Path $packagingRoot -Parent $windowsRoot

$jpackageCandidates = @()
if ($env:JAVA_HOME) {
    $jpackageCandidates += (Join-Path $env:JAVA_HOME "bin\jpackage.exe")
}
$jpackageOnPath = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if ($jpackageOnPath) {
    $jpackageCandidates += $jpackageOnPath.Source
}
$jpackageCandidates += "C:\Program Files\Java\jdk-23\bin\jpackage.exe"
$jpackage = $jpackageCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $jpackage) {
    throw "未找到 jpackage。请安装包含 jpackage 的 JDK 17 或更高版本，并设置 JAVA_HOME。"
}

if (-not $SkipBuild) {
    & (Join-Path $projectRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败。"
    }
}

if (-not (Test-Path -LiteralPath $sourceJar)) {
    throw "未找到待打包 JAR：$sourceJar"
}

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null
New-Item -ItemType Directory -Force -Path $windowsRoot | Out-Null
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $stagingRoot "yiyan-go.jar")
$engineDirectory = Join-Path $projectRoot 'engine\katago'
if (-not (Test-Path -LiteralPath (Join-Path $engineDirectory 'katago.exe'))) {
    throw '请先运行 install-katago.ps1 安装终局引擎。'
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'engine') -Destination (Join-Path $stagingRoot 'engine') -Recurse

try {
    & $jpackage `
        --type app-image `
        --dest $packagingRoot `
        --name "弈言" `
        --input $stagingRoot `
        --main-jar "yiyan-go.jar" `
        --main-class "com.yiyan.go.App" `
        --app-version "1.0.0" `
        --vendor "弈言" `
        --description "带落子说明的围棋应用" `
        --add-modules "java.desktop,java.net.http,java.xml,jdk.crypto.ec" `
        --java-options "-Dfile.encoding=UTF-8"

    if ($LASTEXITCODE -ne 0) {
        throw "Windows 应用打包失败。"
    }

    $stagedApplication = Join-Path $packagingRoot "弈言"
    if (-not (Test-Path -LiteralPath (Join-Path $stagedApplication "弈言.exe"))) {
        throw "暂存目录没有生成可执行文件，原安装目录保持不变。"
    }
    if (Test-Path -LiteralPath $applicationRoot) {
        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
        $backupRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot "弈言-backup-$timestamp"))
        Assert-ChildPath -Path $backupRoot -Parent $windowsRoot
        try {
            Move-Item -LiteralPath $applicationRoot -Destination $backupRoot -ErrorAction Stop
            Write-Host "上一版保留在：$backupRoot"
        } catch {
            $applicationRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot "弈言-$timestamp"))
            Assert-ChildPath -Path $applicationRoot -Parent $windowsRoot
            Write-Warning "原应用目录正在使用或不能移动；新版将保存到独立目录。"
        }
    }
    Assert-ChildPath -Path $stagedApplication -Parent $packagingRoot
    Move-Item -LiteralPath $stagedApplication -Destination $applicationRoot
} finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $packagingRoot) {
        Remove-Item -LiteralPath $packagingRoot -Recurse -Force
    }
}

$launcher = Join-Path $applicationRoot "弈言.exe"
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "打包完成，但没有找到启动程序：$launcher"
}

Write-Host "Windows 免安装版已生成：$launcher"
Write-Host "分发时请保留整个目录：$applicationRoot"
