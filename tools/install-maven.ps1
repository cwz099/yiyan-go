param(
    [string]$Version = "3.9.16",
    [string]$ExpectedSha512 = "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3"
)

$ErrorActionPreference = "Stop"

if (-not $env:LOCALAPPDATA) {
    throw "无法确定 LOCALAPPDATA，不能执行用户级安装。"
}

$installRoot = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA "Programs\Apache\Maven"))
$installDirectory = Join-Path $installRoot "apache-maven-$Version"
$mavenBin = Join-Path $installDirectory "bin"
$mavenCommand = Join-Path $mavenBin "mvn.cmd"

function Set-MavenUserEnvironment {
    [Environment]::SetEnvironmentVariable("MAVEN_HOME", $installDirectory, "User")

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @($userPath -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $alreadyPresent = $entries | Where-Object {
        $_.TrimEnd("\") -ieq $mavenBin.TrimEnd("\")
    }
    if (-not $alreadyPresent) {
        $entries += $mavenBin
        [Environment]::SetEnvironmentVariable("Path", ($entries -join ";"), "User")
    }

    $env:MAVEN_HOME = $installDirectory
    if (-not (($env:Path -split ";") -contains $mavenBin)) {
        $env:Path = "$mavenBin;$env:Path"
    }
}

if (Test-Path -LiteralPath $mavenCommand) {
    Set-MavenUserEnvironment
    Write-Host "Maven $Version 已安装：$installDirectory"
    & $mavenCommand -version
    exit $LASTEXITCODE
}

$temporaryRoot = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) ("yiyan-maven-" + [Guid]::NewGuid().ToString("N"))))
$expectedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
if (-not $temporaryRoot.StartsWith($expectedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "临时目录不在系统临时目录内。"
}

$archiveName = "apache-maven-$Version-bin.zip"
$archivePath = Join-Path $temporaryRoot $archiveName
$downloadUrl = "https://dlcdn.apache.org/maven/maven-3/$Version/binaries/$archiveName"
$extractRoot = Join-Path $temporaryRoot "extract"

New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
try {
    $curl = Get-Command curl.exe -ErrorAction Stop
    & $curl.Source --fail --location --silent --show-error --output $archivePath $downloadUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 下载失败，curl 退出码：$LASTEXITCODE"
    }

    $actualSha512 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actualSha512 -ne $ExpectedSha512.ToLowerInvariant()) {
        throw "Maven 压缩包 SHA-512 校验失败。期望 $ExpectedSha512，实际 $actualSha512"
    }

    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot
    $extractedDirectory = Join-Path $extractRoot "apache-maven-$Version"
    if (-not (Test-Path -LiteralPath (Join-Path $extractedDirectory "bin\mvn.cmd"))) {
        throw "压缩包内没有找到 Maven 启动脚本。"
    }

    New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
    if (Test-Path -LiteralPath $installDirectory) {
        throw "目标目录已经存在但不是可用的 Maven 安装：$installDirectory"
    }
    Move-Item -LiteralPath $extractedDirectory -Destination $installDirectory
    Set-MavenUserEnvironment

    Write-Host "Maven $Version 已安装：$installDirectory"
    Write-Host "已设置用户级 MAVEN_HOME，并将 Maven bin 加入用户 PATH。"
    & $mavenCommand -version
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 安装完成，但版本验证失败。"
    }
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
