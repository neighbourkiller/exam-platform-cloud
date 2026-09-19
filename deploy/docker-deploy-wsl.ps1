#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$Distribution
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$wslCommand = Get-Command wsl.exe -ErrorAction SilentlyContinue
if ($null -eq $wslCommand) {
    throw '未找到 wsl.exe，请先安装并初始化 WSL。'
}

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$distributionArguments = if ([string]::IsNullOrWhiteSpace($Distribution)) {
    @()
}
else {
    @('--distribution', $Distribution)
}

$linuxProjectRootOutput = & $wslCommand.Source @distributionArguments `
    --exec wslpath -a -u -- $projectRoot
if ($LASTEXITCODE -ne 0) {
    throw "无法将项目目录转换为 WSL 路径，wslpath 退出码：$LASTEXITCODE"
}

$linuxProjectRoot = ($linuxProjectRootOutput | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($linuxProjectRoot)) {
    throw 'wslpath 未返回项目目录。'
}

& $wslCommand.Source @distributionArguments --exec bash -c `
    'exec bash "$1/deploy/docker-deploy-example.sh"' bash $linuxProjectRoot
$deployExitCode = $LASTEXITCODE

exit $deployExitCode
