$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$applicationJar = Join-Path $projectRoot "target\yiyan-go.jar"

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "未找到 Maven Wrapper：$mavenWrapper"
}

Push-Location $projectRoot
try {
    & $mavenWrapper --no-transfer-progress "-DskipTests" package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败。"
    }

    & java -jar $applicationJar
    if ($LASTEXITCODE -ne 0) {
        throw "应用异常退出，代码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
}
