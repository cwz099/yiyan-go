$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "未找到 Maven Wrapper：$mavenWrapper"
}

Push-Location $projectRoot
try {
    & $mavenWrapper --no-transfer-progress test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 测试失败。"
    }
} finally {
    Pop-Location
}
