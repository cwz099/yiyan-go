$ErrorActionPreference = 'Stop'
$engineRoot = Join-Path $PSScriptRoot 'engine\katago'
$cacheRoot = Join-Path $PSScriptRoot 'target\engine-downloads'
New-Item -ItemType Directory -Force -Path $engineRoot, $cacheRoot | Out-Null
$archive = Join-Path $cacheRoot 'katago-v1.18.1-eigen-windows-x64.zip'
$expected = '074485cf150c38aa3bb14ac9f54f2952ffefbceb44673709bbb8a83650bf95d6'
if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive).Hash -ne $expected) {
    Invoke-WebRequest 'https://github.com/lightvector/KataGo/releases/download/v1.18.1/katago-v1.18.1-eigen-windows-x64.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive).Hash -ne $expected) { throw 'KataGo 下载校验失败。' }
Expand-Archive -LiteralPath $archive -DestinationPath $engineRoot -Force
$model = Join-Path $engineRoot 'kata1-b18c384nbt-s9996604416-d4316597426.bin.gz'
$modelHash = '9d7a6afed8ff5b74894727e156f04f0cd36060a24824892008fbb6e0cba51f1d'
if (-not (Test-Path -LiteralPath $model) -or (Get-FileHash -LiteralPath $model).Hash -ne $modelHash) {
    $partial = Join-Path $cacheRoot 'model.partial'
    Invoke-WebRequest 'https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz' -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial).Hash -ne $modelHash) { throw '模型下载校验失败。' }
    Move-Item -LiteralPath $partial -Destination $model -Force
}
Invoke-WebRequest 'https://raw.githubusercontent.com/lightvector/KataGo/v1.18.1/LICENSE' -OutFile (Join-Path $engineRoot 'LICENSE')
Get-FileHash -LiteralPath $model
Write-Host "KataGo 已安装：$engineRoot"
