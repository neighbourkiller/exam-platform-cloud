#requires -Version 7.0

<#
.SYNOPSIS
使用 Windows WSL Containers CLI（wslc.exe）部署 EkuExam Cloud 备用环境。

.DESCRIPTION
这是经明确授权保存在 deploy/wslc 下的实验性第二套编排，不替代根目录
docker-compose.yml。脚本从 Compose 文件读取基础镜像、业务镜像 JAR_FILE 参数和
一次性初始化命令，但由于 WSLC 暂无 Compose 支持，端口、环境变量、卷和依赖顺序
仍需由本脚本显式映射。
#>

[CmdletBinding()]
param(
    [ValidateSet('Up', 'Down', 'Status', 'Validate')]
    [string]$Action = 'Up',

    [ValidateRange(0, 32)]
    [int]$RuntimeReplicas = 0,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$ProjectName = 'exam-platform-cloud-wslc',

    [ValidateNotNullOrEmpty()]
    [string]$NetworkSubnet = '10.203.0.0/24',

    [ValidateNotNullOrEmpty()]
    [string]$Session,

    [switch]$SkipPull,
    [switch]$SkipBuild,
    [switch]$RemoveVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:WslcExecutable = $null
$script:WslcGlobalArguments = @()
$script:TemporaryFiles = [System.Collections.Generic.List[string]]::new()
$script:DeploymentEnvironment = [ordered]@{}

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$composePath = Join-Path $projectRoot 'docker-compose.yml'
$environmentPath = Join-Path $projectRoot '.env.microservices'
$networkName = "$ProjectName-network"

$volumes = [ordered]@{
    MySql = "$ProjectName-mysql-data"
    Redis = "$ProjectName-redis-data"
    RabbitMq = "$ProjectName-rabbitmq-data"
    Minio = "$ProjectName-minio-data"
    JwtKeys = "$ProjectName-jwt-keys"
}

$volumeSizes = @{}
$volumeSizes[$volumes.MySql] = 20GB
$volumeSizes[$volumes.Redis] = 4GB
$volumeSizes[$volumes.RabbitMq] = 8GB
$volumeSizes[$volumes.Minio] = 20GB
$volumeSizes[$volumes.JwtKeys] = 512MB

$fixedContainerNames = @(
    "$ProjectName-gateway",
    "$ProjectName-iam-service",
    "$ProjectName-academic-service",
    "$ProjectName-content-service",
    "$ProjectName-management-service",
    "$ProjectName-grading-service",
    "$ProjectName-reporting-service",
    "$ProjectName-xxl-job-admin",
    "$ProjectName-nacos",
    "$ProjectName-minio",
    "$ProjectName-rabbitmq",
    "$ProjectName-redis",
    "$ProjectName-mysql"
)

function Initialize-Wslc {
    $command = Get-Command wslc.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw '未找到 wslc.exe。请安装或更新到包含 WSL Containers 的 WSL 版本。'
    }

    $script:WslcExecutable = $command.Source
    if (-not [string]::IsNullOrWhiteSpace($Session)) {
        $script:WslcGlobalArguments = @('--session', $Session)
    }

    $versionOutput = & $script:WslcExecutable @script:WslcGlobalArguments version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "无法读取 WSLC 版本，退出码：$LASTEXITCODE"
    }

    $versionText = ($versionOutput | Out-String).Trim()
    if ($versionText -notmatch '(\d+\.\d+\.\d+(?:\.\d+)?)') {
        throw '无法解析 wslc version 输出。'
    }

    $installedVersion = [version]$Matches[1]
    if ($installedVersion -lt [version]'2.9.3') {
        throw "WSLC 版本过低：$installedVersion；至少需要 2.9.3。"
    }
}

function Invoke-WslcCommand {
    param(
        [Parameter(Mandatory)]
        [string[]]$CommandArguments,

        [switch]$CaptureOutput,
        [switch]$AllowFailure
    )

    $arguments = @($script:WslcGlobalArguments) + $CommandArguments
    if ($CaptureOutput) {
        $output = & $script:WslcExecutable @arguments 2>&1
        $exitCode = $LASTEXITCODE
        if (-not $AllowFailure -and $exitCode -ne 0) {
            throw "wslc 命令执行失败，退出码：$exitCode；子命令：$($CommandArguments[0])"
        }
        return ,@($output)
    }

    & $script:WslcExecutable @arguments
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "wslc 命令执行失败，退出码：$exitCode；子命令：$($CommandArguments[0])"
    }
}

function Test-WslcObject {
    param(
        [Parameter(Mandatory)][ValidateSet('container', 'network', 'volume')][string]$Type,
        [Parameter(Mandatory)][string]$Name
    )

    $arguments = @($script:WslcGlobalArguments) + @('inspect', '--type', $Type, $Name)
    & $script:WslcExecutable @arguments *> $null
    return $LASTEXITCODE -eq 0
}

function Remove-WslcContainerIfPresent {
    param([Parameter(Mandatory)][string]$Name)

    if (Test-WslcObject -Type container -Name $Name) {
        Write-Host "移除已有容器：$Name"
        Invoke-WslcCommand -CommandArguments @('remove', '--force', $Name)
    }
}

function Get-ComposeServiceBlock {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $startPattern = '^  ' + [regex]::Escape($ServiceName) + ':\s*$'
    $startIndex = -1
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index] -match $startPattern) {
            $startIndex = $index
            break
        }
    }
    if ($startIndex -lt 0) {
        throw "docker-compose.yml 中不存在服务：$ServiceName"
    }

    $endIndex = $Lines.Count
    for ($index = $startIndex + 1; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index] -match '^  [A-Za-z0-9][A-Za-z0-9_-]*:\s*$') {
            $endIndex = $index
            break
        }
        if ($Lines[$index] -match '^[A-Za-z0-9][A-Za-z0-9_-]*:\s*$') {
            $endIndex = $index
            break
        }
    }

    return ,@($Lines[$startIndex..($endIndex - 1)])
}

function Get-ComposeImage {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $block = Get-ComposeServiceBlock -Lines $Lines -ServiceName $ServiceName
    foreach ($line in $block) {
        if ($line -match '^    image:\s*(.+?)\s*$') {
            return $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
    throw "服务 $ServiceName 未声明 image。"
}

function Get-ComposeJarFile {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $block = Get-ComposeServiceBlock -Lines $Lines -ServiceName $ServiceName
    foreach ($line in $block) {
        if ($line -match '^\s+JAR_FILE:\s*(.+?)\s*$') {
            return $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
    throw "服务 $ServiceName 未声明 JAR_FILE 构建参数。"
}

function Get-ComposeLiteralCommand {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $block = Get-ComposeServiceBlock -Lines $Lines -ServiceName $ServiceName
    $commandIndex = -1
    for ($index = 0; $index -lt $block.Count; $index++) {
        if ($block[$index] -match '^    command:\s*$') {
            $commandIndex = $index
            break
        }
    }
    if ($commandIndex -lt 0 -or $commandIndex + 1 -ge $block.Count) {
        throw "服务 $ServiceName 未声明多行 command。"
    }
    if ($block[$commandIndex + 1] -notmatch '^      - \|\s*$') {
        throw "服务 $ServiceName 的 command 不是预期的 YAML 字面量格式。"
    }

    $commandLines = [System.Collections.Generic.List[string]]::new()
    for ($index = $commandIndex + 2; $index -lt $block.Count; $index++) {
        $line = $block[$index]
        if ($line -match '^    [A-Za-z0-9][A-Za-z0-9_-]*:') {
            break
        }
        if ($line.Length -ge 8) {
            $commandLines.Add($line.Substring(8))
        }
        else {
            $commandLines.Add('')
        }
    }

    if ($commandLines.Count -eq 0) {
        throw "服务 $ServiceName 的 command 内容为空。"
    }
    return ([string]::Join("`n", $commandLines)).Replace('$$', '$').TrimEnd()
}

function Read-DeploymentEnvironment {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少部署环境文件：$Path。请从 .env.microservices.example 复制并填写。"
    }

    $values = [ordered]@{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }
        $parts = $line.Split('=', 2)
        if ($parts.Count -ne 2) {
            throw '环境文件中存在不符合 KEY=VALUE 格式的行。'
        }
        $key = $parts[0].Trim()
        $value = $parts[1]
        if ([string]::IsNullOrWhiteSpace($key)) {
            throw '环境文件中存在空键名。'
        }
        $values[$key] = $value
    }
    return $values
}

function Get-DeploymentValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [AllowEmptyString()][string]$DefaultValue = ''
    )

    if ($script:DeploymentEnvironment.Contains($Name)) {
        return [string]$script:DeploymentEnvironment[$Name]
    }
    return $DefaultValue
}

function Assert-RequiredDeploymentValues {
    $requiredNames = @(
        'MYSQL_ROOT_PASSWORD',
        'EXAM_DB_PASSWORD',
        'RABBITMQ_PASSWORD',
        'MINIO_ACCESS_KEY',
        'MINIO_SECRET_KEY',
        'NACOS_PASSWORD',
        'NACOS_AUTH_IDENTITY_KEY',
        'NACOS_AUTH_IDENTITY_VALUE',
        'NACOS_AUTH_TOKEN',
        'XXL_JOB_ACCESS_TOKEN',
        'XXL_JOB_ADMIN_PASSWORD',
        'SERVICE_CLIENT_SECRET',
        'APP_DEFAULT_PASSWORD',
        'APP_DEFAULT_PASSWORD_HASH'
    )

    $missing = @($requiredNames | Where-Object {
        [string]::IsNullOrWhiteSpace((Get-DeploymentValue -Name $_))
    })
    if ($missing.Count -gt 0) {
        throw "环境文件缺少必填项或值为空：$($missing -join ', ')"
    }
}

function Merge-Environment {
    param(
        [hashtable]$Base = @{},
        [hashtable]$Additional = @{}
    )

    $merged = [ordered]@{}
    foreach ($key in $Base.Keys) {
        $merged[$key] = [string]$Base[$key]
    }
    foreach ($key in $Additional.Keys) {
        $merged[$key] = [string]$Additional[$key]
    }
    return $merged
}

function New-ContainerEnvironmentFile {
    param(
        [Parameter(Mandatory)][string]$Purpose,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Values
    )

    $path = Join-Path ([System.IO.Path]::GetTempPath()) (
        '{0}-{1}-{2}-{3}.env' -f $ProjectName, $Purpose, $PID, ([guid]::NewGuid().ToString('N'))
    )
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($key in ($Values.Keys | Sort-Object)) {
        $value = [string]$Values[$key]
        if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "非法环境变量名：$key"
        }
        if ($value.Contains("`r") -or $value.Contains("`n")) {
            throw "环境变量 $key 包含换行符，无法安全写入临时 env 文件。"
        }
        $lines.Add("$key=$value")
    }
    [System.IO.File]::WriteAllLines($path, $lines, [System.Text.UTF8Encoding]::new($false))
    $script:TemporaryFiles.Add($path)
    return $path
}

function Start-WslcContainer {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Alias,
        [Parameter(Mandatory)][string]$Image,
        [string]$EnvironmentFile,
        [string[]]$Ports = @(),
        [string[]]$Volumes = @(),
        [string]$EntryPoint,
        [string[]]$ContainerCommand = @(),
        [string]$HealthCommand,
        [string]$HealthInterval,
        [string]$HealthTimeout,
        [string]$HealthStartPeriod,
        [int]$HealthRetries = 0
    )

    Remove-WslcContainerIfPresent -Name $Name
    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @(
        'run', '--detach', '--name', $Name,
        '--network', $networkName,
        '--network-alias', $Alias,
        '--label', "com.ekusys.exam.wslc.project=$ProjectName"
    )) {
        $arguments.Add($value)
    }
    if (-not [string]::IsNullOrWhiteSpace($EnvironmentFile)) {
        $arguments.Add('--env-file')
        $arguments.Add($EnvironmentFile)
    }
    foreach ($port in $Ports) {
        $arguments.Add('--publish')
        $arguments.Add($port)
    }
    foreach ($volume in $Volumes) {
        $arguments.Add('--volume')
        $arguments.Add($volume)
    }
    if (-not [string]::IsNullOrWhiteSpace($EntryPoint)) {
        $arguments.Add('--entrypoint')
        $arguments.Add($EntryPoint)
    }
    if (-not [string]::IsNullOrWhiteSpace($HealthCommand)) {
        $arguments.Add('--health-cmd')
        $arguments.Add($HealthCommand)
        if (-not [string]::IsNullOrWhiteSpace($HealthInterval)) {
            $arguments.Add('--health-interval')
            $arguments.Add($HealthInterval)
        }
        if (-not [string]::IsNullOrWhiteSpace($HealthTimeout)) {
            $arguments.Add('--health-timeout')
            $arguments.Add($HealthTimeout)
        }
        if (-not [string]::IsNullOrWhiteSpace($HealthStartPeriod)) {
            $arguments.Add('--health-start-period')
            $arguments.Add($HealthStartPeriod)
        }
        if ($HealthRetries -gt 0) {
            $arguments.Add('--health-retries')
            $arguments.Add([string]$HealthRetries)
        }
    }
    $arguments.Add($Image)
    foreach ($value in $ContainerCommand) {
        $arguments.Add($value)
    }

    Write-Host "启动容器：$Name"
    Invoke-WslcCommand -CommandArguments $arguments.ToArray()
}

function Invoke-WslcOneShot {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Image,
        [string]$EnvironmentFile,
        [string[]]$Volumes = @(),
        [Parameter(Mandatory)][string]$EntryPoint,
        [Parameter(Mandatory)][string[]]$ContainerCommand
    )

    Remove-WslcContainerIfPresent -Name $Name
    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @(
        'run', '--rm', '--name', $Name,
        '--network', $networkName,
        '--label', "com.ekusys.exam.wslc.project=$ProjectName"
    )) {
        $arguments.Add($value)
    }
    if (-not [string]::IsNullOrWhiteSpace($EnvironmentFile)) {
        $arguments.Add('--env-file')
        $arguments.Add($EnvironmentFile)
    }
    foreach ($volume in $Volumes) {
        $arguments.Add('--volume')
        $arguments.Add($volume)
    }
    $arguments.Add('--entrypoint')
    $arguments.Add($EntryPoint)
    $arguments.Add($Image)
    foreach ($value in $ContainerCommand) {
        $arguments.Add($value)
    }

    Write-Host "运行一次性任务：$Name"
    Invoke-WslcCommand -CommandArguments $arguments.ToArray()
}

function Get-WslcContainerInspection {
    param([Parameter(Mandatory)][string]$Name)

    $output = Invoke-WslcCommand -CommandArguments @(
        'inspect', '--type', 'container', '--format', 'json', $Name
    ) -CaptureOutput
    $json = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($json)) {
        throw "容器检查没有返回内容：$Name"
    }
    return $json | ConvertFrom-Json
}

function Wait-WslcContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 180
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $inspection = Get-WslcContainerInspection -Name $Name
        $running = [bool]$inspection.State.Running
        if (-not $running) {
            throw "容器已退出，无法达到健康状态：$Name"
        }
        $healthStatus = [string]$inspection.State.Health.Status
        if ($healthStatus -eq 'healthy') {
            Write-Host "容器健康：$Name"
            return
        }
        if ($healthStatus -eq 'unhealthy') {
            throw "容器健康检查失败：$Name"
        }
        Start-Sleep -Seconds 2
    }
    throw "等待容器健康超时：$Name"
}

function New-WslcInfrastructure {
    if (-not (Test-WslcObject -Type network -Name $networkName)) {
        Write-Host "创建 WSLC 网络：$networkName ($NetworkSubnet)"
        Invoke-WslcCommand -CommandArguments @(
            'network', 'create', '--subnet', $NetworkSubnet, $networkName
        )
    }

    foreach ($volumeName in $volumes.Values) {
        if (-not (Test-WslcObject -Type volume -Name $volumeName)) {
            $sizeBytes = [uint64]$volumeSizes[$volumeName]
            Write-Host "创建 WSLC VHD 卷：$volumeName ($sizeBytes bytes)"
            Invoke-WslcCommand -CommandArguments @(
                'volume', 'create', '--driver', 'vhd',
                '--opt', "SizeBytes=$sizeBytes", $volumeName
            )
        }
    }
}

function Stop-WslcDeployment {
    Write-Warning '正在删除 WSLC 备用环境的容器；默认保留命名卷中的数据。'

    for ($index = 1; $index -le 32; $index++) {
        Remove-WslcContainerIfPresent -Name "$ProjectName-runtime-service-$index"
    }
    foreach ($name in $fixedContainerNames) {
        Remove-WslcContainerIfPresent -Name $name
    }
    foreach ($suffix in @('nacos-config-init', 'jwt-key-init', 'app-data-init')) {
        Remove-WslcContainerIfPresent -Name "$ProjectName-$suffix"
    }

    if (Test-WslcObject -Type network -Name $networkName) {
        Invoke-WslcCommand -CommandArguments @('network', 'remove', '--force', $networkName)
    }

    if ($RemoveVolumes) {
        Write-Warning 'RemoveVolumes 已启用：将永久删除 WSLC 环境的数据卷。'
        foreach ($volumeName in $volumes.Values) {
            Invoke-WslcCommand -CommandArguments @('volume', 'remove', '--force', $volumeName)
        }
    }
    Write-Host 'WSLC 备用环境已停止。'
}

function Show-WslcDeploymentStatus {
    $arguments = @($script:WslcGlobalArguments) + @('list', '--all', '--no-trunc')
    $output = & $script:WslcExecutable @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "读取 WSLC 容器列表失败，退出码：$LASTEXITCODE"
    }
    $matchingLines = @($output | Where-Object { [string]$_ -match [regex]::Escape($ProjectName) })
    if ($matchingLines.Count -eq 0) {
        Write-Host "未找到项目前缀为 $ProjectName 的容器。"
        return
    }
    $matchingLines | ForEach-Object { Write-Host $_ }
}

Initialize-Wslc

if ($Action -eq 'Status') {
    Show-WslcDeploymentStatus
    exit 0
}
if ($Action -eq 'Down') {
    Stop-WslcDeployment
    exit 0
}

Write-Warning '当前使用的是实验性 WSLC 备用编排；正式部署入口仍为根目录 docker-compose.yml。'

try {
    if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) {
        throw "缺少 Compose 文件：$composePath"
    }
    $composeLines = @(Get-Content -LiteralPath $composePath)
    $script:DeploymentEnvironment = Read-DeploymentEnvironment -Path $environmentPath
    Assert-RequiredDeploymentValues

    if ($RuntimeReplicas -eq 0) {
        $configuredReplicas = Get-DeploymentValue -Name 'APP_RUNTIME_REPLICAS' -DefaultValue '4'
        $parsedReplicas = 0
        if (-not [int]::TryParse($configuredReplicas, [ref]$parsedReplicas) -or
            $parsedReplicas -lt 1 -or $parsedReplicas -gt 32) {
            throw 'APP_RUNTIME_REPLICAS 必须是 1 到 32 之间的整数。'
        }
        $RuntimeReplicas = $parsedReplicas
    }

    $baseImages = [ordered]@{
        MySql = Get-ComposeImage -Lines $composeLines -ServiceName 'mysql'
        Redis = Get-ComposeImage -Lines $composeLines -ServiceName 'redis'
        RabbitMq = Get-ComposeImage -Lines $composeLines -ServiceName 'rabbitmq'
        Minio = Get-ComposeImage -Lines $composeLines -ServiceName 'minio'
        Nacos = Get-ComposeImage -Lines $composeLines -ServiceName 'nacos'
        Curl = Get-ComposeImage -Lines $composeLines -ServiceName 'nacos-config-init'
        XxlJob = Get-ComposeImage -Lines $composeLines -ServiceName 'xxl-job-admin'
        Alpine = Get-ComposeImage -Lines $composeLines -ServiceName 'jwt-key-init'
    }

    $applicationServices = @(
        'gateway',
        'iam-service',
        'academic-service',
        'content-service',
        'management-service',
        'runtime-service',
        'grading-service',
        'reporting-service'
    )
    $applicationImages = [ordered]@{}
    $applicationJars = [ordered]@{}
    foreach ($service in $applicationServices) {
        $applicationImages[$service] = "$ProjectName-$service`:local"
        $applicationJars[$service] = Get-ComposeJarFile -Lines $composeLines -ServiceName $service
    }

    $nacosConfigCommand = Get-ComposeLiteralCommand `
        -Lines $composeLines -ServiceName 'nacos-config-init'
    $jwtKeyCommand = Get-ComposeLiteralCommand `
        -Lines $composeLines -ServiceName 'jwt-key-init'
    $appDataCommand = Get-ComposeLiteralCommand `
        -Lines $composeLines -ServiceName 'app-data-init'

    if ($Action -eq 'Validate') {
        Write-Host 'WSLC 前置条件、部署环境和 Compose 映射检查通过。'
        Write-Host "基础镜像数：$($baseImages.Count)；业务镜像数：$($applicationImages.Count)；Runtime 实例数：$RuntimeReplicas"
        return
    }

    New-WslcInfrastructure

    if (-not $SkipPull) {
        foreach ($image in @($baseImages.Values | Select-Object -Unique)) {
            Write-Host "拉取镜像：$image"
            Invoke-WslcCommand -CommandArguments @('pull', $image)
        }
    }

    if (-not $SkipBuild) {
        foreach ($service in $applicationServices) {
            Write-Host "构建业务镜像：$service"
            Invoke-WslcCommand -CommandArguments @(
                'build', '--tag', $applicationImages[$service],
                '--build-arg', "JAR_FILE=$($applicationJars[$service])",
                $projectRoot
            )
        }
    }

    $mysqlEnvironment = [ordered]@{
        MYSQL_ROOT_PASSWORD = Get-DeploymentValue -Name 'MYSQL_ROOT_PASSWORD'
        XXL_JOB_ADMIN_PASSWORD = Get-DeploymentValue -Name 'XXL_JOB_ADMIN_PASSWORD'
        EXAM_DB_PASSWORD = Get-DeploymentValue -Name 'EXAM_DB_PASSWORD'
    }
    $rabbitMqEnvironment = [ordered]@{
        RABBITMQ_DEFAULT_USER = Get-DeploymentValue -Name 'RABBITMQ_USERNAME' -DefaultValue 'exam'
        RABBITMQ_DEFAULT_PASS = Get-DeploymentValue -Name 'RABBITMQ_PASSWORD'
    }
    $minioEnvironment = [ordered]@{
        MINIO_ROOT_USER = Get-DeploymentValue -Name 'MINIO_ACCESS_KEY'
        MINIO_ROOT_PASSWORD = Get-DeploymentValue -Name 'MINIO_SECRET_KEY'
    }
    $nacosEnvironment = [ordered]@{
        MODE = 'standalone'
        SPRING_DATASOURCE_PLATFORM = 'mysql'
        MYSQL_SERVICE_HOST = 'mysql'
        MYSQL_SERVICE_PORT = '3306'
        MYSQL_SERVICE_DB_NAME = 'nacos_config'
        MYSQL_SERVICE_USER = 'exam_nacos'
        MYSQL_SERVICE_PASSWORD = Get-DeploymentValue -Name 'EXAM_DB_PASSWORD'
        MYSQL_SERVICE_DB_PARAM = 'characterEncoding=utf8&connectTimeout=1000&socketTimeout=3000&autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
        NACOS_AUTH_ENABLE = 'true'
        NACOS_AUTH_IDENTITY_KEY = Get-DeploymentValue -Name 'NACOS_AUTH_IDENTITY_KEY'
        NACOS_AUTH_IDENTITY_VALUE = Get-DeploymentValue -Name 'NACOS_AUTH_IDENTITY_VALUE'
        NACOS_AUTH_TOKEN = Get-DeploymentValue -Name 'NACOS_AUTH_TOKEN'
    }
    $nacosInitEnvironment = [ordered]@{
        NACOS_SERVER = 'http://nacos:8080'
        NACOS_NAMESPACE = 'dev'
        NACOS_GROUP = 'EXAM_GROUP'
        NACOS_USERNAME = Get-DeploymentValue -Name 'NACOS_USERNAME' -DefaultValue 'nacos'
        NACOS_PASSWORD = Get-DeploymentValue -Name 'NACOS_PASSWORD'
    }
    $xxlJobEnvironment = [ordered]@{
        PARAMS = "--spring.datasource.url=jdbc:mysql://mysql:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai --spring.datasource.username=exam_xxl --spring.datasource.password=$(Get-DeploymentValue -Name 'EXAM_DB_PASSWORD') --xxl.job.accessToken=$(Get-DeploymentValue -Name 'XXL_JOB_ACCESS_TOKEN')"
    }

    $commonEnvironment = [ordered]@{
        NACOS_SERVER_ADDR = 'nacos:8848'
        NACOS_NAMESPACE = 'dev'
        NACOS_USERNAME = Get-DeploymentValue -Name 'NACOS_USERNAME' -DefaultValue 'nacos'
        NACOS_PASSWORD = Get-DeploymentValue -Name 'NACOS_PASSWORD'
        MYSQL_HOST = 'mysql'
        REDIS_HOST = 'redis'
        RABBITMQ_HOST = 'rabbitmq'
        RABBITMQ_USERNAME = Get-DeploymentValue -Name 'RABBITMQ_USERNAME' -DefaultValue 'exam'
        RABBITMQ_PASSWORD = Get-DeploymentValue -Name 'RABBITMQ_PASSWORD'
        MINIO_ENDPOINT = 'http://minio:9000'
        MINIO_ACCESS_KEY = Get-DeploymentValue -Name 'MINIO_ACCESS_KEY'
        MINIO_SECRET_KEY = Get-DeploymentValue -Name 'MINIO_SECRET_KEY'
        JWT_PUBLIC_KEY_LOCATION = '/run/secrets/jwt-public.pem'
        SERVICE_CLIENT_SECRET = Get-DeploymentValue -Name 'SERVICE_CLIENT_SECRET'
    }

    $mysqlEnvFile = New-ContainerEnvironmentFile -Purpose 'mysql' -Values $mysqlEnvironment
    $rabbitMqEnvFile = New-ContainerEnvironmentFile -Purpose 'rabbitmq' -Values $rabbitMqEnvironment
    $minioEnvFile = New-ContainerEnvironmentFile -Purpose 'minio' -Values $minioEnvironment
    $nacosEnvFile = New-ContainerEnvironmentFile -Purpose 'nacos' -Values $nacosEnvironment
    $nacosInitEnvFile = New-ContainerEnvironmentFile -Purpose 'nacos-init' -Values $nacosInitEnvironment
    $xxlJobEnvFile = New-ContainerEnvironmentFile -Purpose 'xxl-job' -Values $xxlJobEnvironment

    Invoke-WslcOneShot `
        -Name "$ProjectName-jwt-key-init" `
        -Image $baseImages.Alpine `
        -Volumes @("$($volumes.JwtKeys):/keys") `
        -EntryPoint '/bin/sh' `
        -ContainerCommand @('-ec', $jwtKeyCommand)

    Start-WslcContainer `
        -Name "$ProjectName-mysql" -Alias 'mysql' -Image $baseImages.MySql `
        -EnvironmentFile $mysqlEnvFile `
        -Ports @('23306:3306') `
        -Volumes @(
            "$($volumes.MySql):/var/lib/mysql",
            "${projectRoot}\deploy\mysql\init:/docker-entrypoint-initdb.d:ro"
        ) `
        -HealthCommand 'mysqladmin ping -h localhost -p"$MYSQL_ROOT_PASSWORD"' `
        -HealthInterval '5s' -HealthTimeout '3s' -HealthRetries 30

    Start-WslcContainer `
        -Name "$ProjectName-redis" -Alias 'redis' -Image $baseImages.Redis `
        -Ports @('127.0.0.1:26379:6379') `
        -Volumes @(
            "$($volumes.Redis):/data",
            "${projectRoot}\deploy\redis\redis.conf:/usr/local/etc/redis/redis.conf:ro",
            "${projectRoot}\deploy\redis\entrypoint.sh:/usr/local/bin/exam-redis-entrypoint.sh:ro"
        ) `
        -EntryPoint 'sh' `
        -ContainerCommand @(
            '/usr/local/bin/exam-redis-entrypoint.sh',
            'redis-server', '/usr/local/etc/redis/redis.conf'
        ) `
        -HealthCommand 'redis-cli ping' `
        -HealthInterval '5s' -HealthTimeout '3s' `
        -HealthStartPeriod '20m' -HealthRetries 30

    Start-WslcContainer `
        -Name "$ProjectName-rabbitmq" -Alias 'rabbitmq' -Image $baseImages.RabbitMq `
        -EnvironmentFile $rabbitMqEnvFile `
        -Ports @('15672:5672', '25672:15672') `
        -Volumes @("$($volumes.RabbitMq):/var/lib/rabbitmq") `
        -HealthCommand 'rabbitmq-diagnostics -q ping' `
        -HealthInterval '10s' -HealthTimeout '5s' -HealthRetries 12

    Start-WslcContainer `
        -Name "$ProjectName-minio" -Alias 'minio' -Image $baseImages.Minio `
        -EnvironmentFile $minioEnvFile `
        -Ports @('29000:9000', '29001:9001') `
        -Volumes @("$($volumes.Minio):/data") `
        -ContainerCommand @('server', '/data', '--console-address', ':9001')

    Wait-WslcContainerHealthy -Name "$ProjectName-mysql" -TimeoutSeconds 210
    Wait-WslcContainerHealthy -Name "$ProjectName-redis" -TimeoutSeconds 1260
    Wait-WslcContainerHealthy -Name "$ProjectName-rabbitmq" -TimeoutSeconds 180

    Start-WslcContainer `
        -Name "$ProjectName-nacos" -Alias 'nacos' -Image $baseImages.Nacos `
        -EnvironmentFile $nacosEnvFile `
        -Ports @('18081:8080', '18848:8848', '19848:9848', '19849:9849')

    Start-WslcContainer `
        -Name "$ProjectName-xxl-job-admin" -Alias 'xxl-job-admin' -Image $baseImages.XxlJob `
        -EnvironmentFile $xxlJobEnvFile `
        -Ports @('18080:8080')

    Invoke-WslcOneShot `
        -Name "$ProjectName-nacos-config-init" `
        -Image $baseImages.Curl `
        -EnvironmentFile $nacosInitEnvFile `
        -Volumes @("${projectRoot}\deploy\nacos-config:/config:ro") `
        -EntryPoint '/bin/sh' `
        -ContainerCommand @('-ec', $nacosConfigCommand)

    $jwtReadOnlyVolume = "$($volumes.JwtKeys):/run/secrets:ro"
    $gatewayEnvironment = [ordered]@{
        NACOS_SERVER_ADDR = 'nacos:8848'
        NACOS_NAMESPACE = 'dev'
        NACOS_USERNAME = Get-DeploymentValue -Name 'NACOS_USERNAME' -DefaultValue 'nacos'
        NACOS_PASSWORD = Get-DeploymentValue -Name 'NACOS_PASSWORD'
        JWT_PUBLIC_KEY_LOCATION = '/run/secrets/jwt-public.pem'
        TRUSTED_PROXY_CIDRS = Get-DeploymentValue -Name 'TRUSTED_PROXY_CIDRS'
        REDIS_HOST = 'redis'
    }
    $gatewayEnvFile = New-ContainerEnvironmentFile -Purpose 'gateway' -Values $gatewayEnvironment
    Start-WslcContainer `
        -Name "$ProjectName-gateway" -Alias 'gateway' `
        -Image $applicationImages['gateway'] `
        -EnvironmentFile $gatewayEnvFile `
        -Volumes @($jwtReadOnlyVolume) `
        -Ports @('16730:16730')

    $iamEnvironment = Merge-Environment -Base $commonEnvironment -Additional @{
        DB_USERNAME = 'exam_iam'
        DB_PASSWORD = Get-DeploymentValue -Name 'EXAM_DB_PASSWORD'
        JWT_PRIVATE_KEY_LOCATION = '/run/secrets/jwt-private.pem'
        APP_DEFAULT_PASSWORD = Get-DeploymentValue -Name 'APP_DEFAULT_PASSWORD'
    }
    $iamEnvFile = New-ContainerEnvironmentFile -Purpose 'iam' -Values $iamEnvironment
    Start-WslcContainer `
        -Name "$ProjectName-iam-service" -Alias 'iam-service' `
        -Image $applicationImages['iam-service'] `
        -EnvironmentFile $iamEnvFile -Volumes @($jwtReadOnlyVolume)

    $serviceDefinitions = @(
        @{ Name = 'academic-service'; DbUser = 'exam_academic' },
        @{ Name = 'content-service'; DbUser = 'exam_content' },
        @{ Name = 'management-service'; DbUser = 'exam_management' },
        @{ Name = 'grading-service'; DbUser = 'exam_grading' },
        @{ Name = 'reporting-service'; DbUser = 'exam_reporting' }
    )
    foreach ($definition in $serviceDefinitions) {
        $serviceName = [string]$definition.Name
        $serviceEnvironment = Merge-Environment -Base $commonEnvironment -Additional @{
            SERVICE_CLIENT_ID = "exam-$serviceName"
            DB_USERNAME = [string]$definition.DbUser
            DB_PASSWORD = Get-DeploymentValue -Name 'EXAM_DB_PASSWORD'
        }
        $serviceEnvFile = New-ContainerEnvironmentFile -Purpose $serviceName -Values $serviceEnvironment
        Start-WslcContainer `
            -Name "$ProjectName-$serviceName" -Alias $serviceName `
            -Image $applicationImages[$serviceName] `
            -EnvironmentFile $serviceEnvFile -Volumes @($jwtReadOnlyVolume)
    }

    $runtimeAdditional = @{
        XXL_JOB_ADMIN_ADDRESSES = 'http://xxl-job-admin:8080/xxl-job-admin'
        XXL_JOB_ACCESS_TOKEN = Get-DeploymentValue -Name 'XXL_JOB_ACCESS_TOKEN'
        XXL_JOB_EXECUTOR_PORT = '19999'
        SERVICE_CLIENT_ID = 'exam-runtime-service'
        DB_USERNAME = 'exam_runtime'
        DB_PASSWORD = Get-DeploymentValue -Name 'EXAM_DB_PASSWORD'
        APP_EXAM_ENTRY_V2_ENABLED = Get-DeploymentValue -Name 'APP_EXAM_ENTRY_V2_ENABLED' -DefaultValue 'false'
        APP_TIMEOUT_SUBMISSION_V2_ENABLED = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_V2_ENABLED' -DefaultValue 'false'
        APP_RUNTIME_DB_MAX_POOL_SIZE = Get-DeploymentValue -Name 'APP_RUNTIME_DB_MAX_POOL_SIZE' -DefaultValue '24'
        APP_RUNTIME_DB_MIN_IDLE = Get-DeploymentValue -Name 'APP_RUNTIME_DB_MIN_IDLE' -DefaultValue '8'
        APP_RUNTIME_DB_CONNECTION_TIMEOUT_MS = Get-DeploymentValue -Name 'APP_RUNTIME_DB_CONNECTION_TIMEOUT_MS' -DefaultValue '2000'
        APP_TIMEOUT_SUBMISSION_BATCH_SIZE = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_BATCH_SIZE' -DefaultValue '8'
        APP_TIMEOUT_SUBMISSION_WORKER_COUNT = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_WORKER_COUNT' -DefaultValue '8'
        APP_TIMEOUT_SUBMISSION_LEASE_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_LEASE_MS' -DefaultValue '30000'
        APP_TIMEOUT_SUBMISSION_MAX_RUN_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_MAX_RUN_MS' -DefaultValue '25000'
        APP_TIMEOUT_SUBMISSION_TASK_TIMEOUT_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_TASK_TIMEOUT_MS' -DefaultValue '20000'
        APP_TIMEOUT_SUBMISSION_LEASE_RENEW_INTERVAL_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_LEASE_RENEW_INTERVAL_MS' -DefaultValue '10000'
        APP_TIMEOUT_SUBMISSION_BACKLOG_REFRESH_INTERVAL_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_BACKLOG_REFRESH_INTERVAL_MS' -DefaultValue '10000'
        APP_TIMEOUT_SUBMISSION_MAX_ATTEMPTS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_MAX_ATTEMPTS' -DefaultValue '12'
        APP_TIMEOUT_SUBMISSION_CROSS_SHARD_DELAY_MS = Get-DeploymentValue -Name 'APP_TIMEOUT_SUBMISSION_CROSS_SHARD_DELAY_MS' -DefaultValue '10000'
    }
    $runtimeEnvironment = Merge-Environment -Base $commonEnvironment -Additional $runtimeAdditional
    $runtimeEnvFile = New-ContainerEnvironmentFile -Purpose 'runtime' -Values $runtimeEnvironment
    for ($index = $RuntimeReplicas + 1; $index -le 32; $index++) {
        Remove-WslcContainerIfPresent -Name "$ProjectName-runtime-service-$index"
    }
    for ($index = 1; $index -le $RuntimeReplicas; $index++) {
        Start-WslcContainer `
            -Name "$ProjectName-runtime-service-$index" `
            -Alias 'runtime-service' `
            -Image $applicationImages['runtime-service'] `
            -EnvironmentFile $runtimeEnvFile -Volumes @($jwtReadOnlyVolume)
    }

    $appDataEnvironment = [ordered]@{
        MYSQL_PWD = Get-DeploymentValue -Name 'MYSQL_ROOT_PASSWORD'
        APP_DEFAULT_PASSWORD_HASH = Get-DeploymentValue -Name 'APP_DEFAULT_PASSWORD_HASH'
    }
    $appDataEnvFile = New-ContainerEnvironmentFile -Purpose 'app-data' -Values $appDataEnvironment
    Invoke-WslcOneShot `
        -Name "$ProjectName-app-data-init" `
        -Image $baseImages.MySql `
        -EnvironmentFile $appDataEnvFile `
        -Volumes @("${projectRoot}\deploy\seed-data:/seed-data:ro") `
        -EntryPoint '/bin/sh' `
        -ContainerCommand @('-ec', $appDataCommand)

    Write-Host ''
    Write-Host "WSLC 备用环境部署完成，Runtime 实例数：$RuntimeReplicas"
    Write-Host "查看状态：pwsh -File .\deploy\wslc\deploy.ps1 -Action Status"
    Write-Host "停止环境：pwsh -File .\deploy\wslc\deploy.ps1 -Action Down"
}
finally {
    foreach ($path in $script:TemporaryFiles) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}
