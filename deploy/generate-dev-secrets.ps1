[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$secretDirectory = Join-Path $PSScriptRoot 'secrets'
$privateKey = Join-Path $secretDirectory 'jwt-private.pem'
$publicKey = Join-Path $secretDirectory 'jwt-public.pem'

if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    throw '未找到 openssl，请先安装 OpenSSL 并加入 PATH。'
}

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $privateKey)) {
    & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $privateKey
}
if (-not (Test-Path -LiteralPath $publicKey)) {
    & openssl pkey -in $privateKey -pubout -out $publicKey
}

Write-Host "JWT Docker secrets 已生成到 $secretDirectory"
Write-Host '另请在未提交的 .env 中设置 SERVICE_CLIENT_SECRET。'
