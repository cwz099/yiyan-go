$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$targetJar = Join-Path $projectRoot "target\yiyan-go.jar"
$distributionRoot = Join-Path $projectRoot "dist"

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "未找到 Maven Wrapper：$mavenWrapper"
}

Push-Location $projectRoot
try {
    & $mavenWrapper --no-transfer-progress clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败。"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $targetJar)) {
    throw "构建完成，但没有找到 $targetJar"
}

New-Item -ItemType Directory -Force -Path $distributionRoot | Out-Null
$distributionJar = Join-Path $distributionRoot "yiyan-go.jar"
$temporaryJar = Join-Path $distributionRoot ".yiyan-go-build.jar"
Copy-Item -LiteralPath $targetJar -Destination $temporaryJar -Force

try {
    Move-Item -LiteralPath $temporaryJar -Destination $distributionJar -Force
} catch {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $distributionJar = Join-Path $distributionRoot "yiyan-go-$timestamp.jar"
    Move-Item -LiteralPath $temporaryJar -Destination $distributionJar
    Write-Warning "标准 JAR 正被运行中的应用占用，已改用带时间戳的文件名。"
}

Write-Host "已生成 $distributionJar"
