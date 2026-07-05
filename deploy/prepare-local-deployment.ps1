[CmdletBinding()]
param(
    [string]$OutputFile = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.microservices')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$legacyEnvFile = Join-Path $projectRoot '.env'
$secretDirectory = Join-Path $PSScriptRoot 'secrets'
$privateKeyFile = Join-Path $secretDirectory 'jwt-private.pem'
$publicKeyFile = Join-Path $secretDirectory 'jwt-public.pem'

function Read-EnvFile([string]$Path) {
    $values = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
            }
        }
    }
    return $values
}

function Read-WslContainerEnv([string]$ContainerName) {
    $values = [ordered]@{}
    $lines = & wsl.exe --exec docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $values
    }
    foreach ($line in $lines) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2]
        }
    }
    return $values
}

function New-Secret([int]$Bytes = 32) {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes($Bytes))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function ConvertTo-Pem([string]$Type, [byte[]]$Bytes) {
    $encoded = [Convert]::ToBase64String($Bytes)
    $lines = for ($offset = 0; $offset -lt $encoded.Length; $offset += 64) {
        $encoded.Substring($offset, [Math]::Min(64, $encoded.Length - $offset))
    }
    return "-----BEGIN $Type-----`n$($lines -join "`n")`n-----END $Type-----`n"
}

$legacy = Read-EnvFile $legacyEnvFile
$current = Read-EnvFile $OutputFile
$mysqlContainer = Read-WslContainerEnv 'exam-mysql'
$minioContainer = Read-WslContainerEnv 'exam-minio'
function Existing-OrNew([string]$Name, [int]$Bytes = 32) {
    if ($current.Contains($Name) -and -not [string]::IsNullOrWhiteSpace($current[$Name])) { return $current[$Name] }
    return New-Secret $Bytes
}

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $privateKeyFile) -or -not (Test-Path -LiteralPath $publicKeyFile)) {
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        Set-Content -LiteralPath $privateKeyFile -Value (ConvertTo-Pem 'PRIVATE KEY' $rsa.ExportPkcs8PrivateKey()) -NoNewline
        Set-Content -LiteralPath $publicKeyFile -Value (ConvertTo-Pem 'PUBLIC KEY' $rsa.ExportSubjectPublicKeyInfo()) -NoNewline
    } finally {
        $rsa.Dispose()
    }
}

$nacosPassword = Existing-OrNew 'NACOS_PASSWORD' 24
$nacosHash = (& wsl.exe --exec python3 -c 'import bcrypt,sys; print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt()).decode())' $nacosPassword).Trim()
if (-not $nacosHash.StartsWith('$2')) { throw '无法生成 Nacos BCrypt 密码摘要' }

$mysqlRootPassword = if ($mysqlContainer.Contains('MYSQL_ROOT_PASSWORD')) {
    $mysqlContainer['MYSQL_ROOT_PASSWORD']
} elseif ($legacy.Contains('DB_USERNAME') -and $legacy['DB_USERNAME'] -eq 'root' -and $legacy.Contains('DB_PASSWORD')) {
    $legacy['DB_PASSWORD']
} elseif ($current.Contains('MYSQL_ROOT_PASSWORD')) { $current['MYSQL_ROOT_PASSWORD'] } else { '123456' }
$minioAccessKey = if ($minioContainer.Contains('MINIO_ROOT_USER')) {
    $minioContainer['MINIO_ROOT_USER']
} elseif ($legacy.Contains('MINIO_ACCESS_KEY')) { $legacy['MINIO_ACCESS_KEY'] } else { 'admin' }
$minioSecretKey = if ($minioContainer.Contains('MINIO_ROOT_PASSWORD')) {
    $minioContainer['MINIO_ROOT_PASSWORD']
} elseif ($legacy.Contains('MINIO_SECRET_KEY')) { $legacy['MINIO_SECRET_KEY'] } else { 'password123' }

$values = [ordered]@{
    MYSQL_ROOT_PASSWORD = $mysqlRootPassword
    EXAM_DB_PASSWORD = Existing-OrNew 'EXAM_DB_PASSWORD'
    RABBITMQ_USERNAME = 'exam'
    RABBITMQ_PASSWORD = Existing-OrNew 'RABBITMQ_PASSWORD'
    MINIO_ACCESS_KEY = $minioAccessKey
    MINIO_SECRET_KEY = $minioSecretKey
    NACOS_USERNAME = 'nacos'
    NACOS_PASSWORD = $nacosPassword
    NACOS_PASSWORD_HASH = $nacosHash
    NACOS_AUTH_IDENTITY_KEY = Existing-OrNew 'NACOS_AUTH_IDENTITY_KEY' 18
    NACOS_AUTH_IDENTITY_VALUE = Existing-OrNew 'NACOS_AUTH_IDENTITY_VALUE' 24
    NACOS_AUTH_TOKEN = if ($current.Contains('NACOS_AUTH_TOKEN')) {
        $current['NACOS_AUTH_TOKEN']
    } else {
        [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    }
    XXL_JOB_ACCESS_TOKEN = Existing-OrNew 'XXL_JOB_ACCESS_TOKEN'
    XXL_JOB_ADMIN_PASSWORD = Existing-OrNew 'XXL_JOB_ADMIN_PASSWORD' 18
    SERVICE_CLIENT_SECRET = Existing-OrNew 'SERVICE_CLIENT_SECRET'
    APP_DEFAULT_PASSWORD = if ($legacy.Contains('APP_DEFAULT_PASSWORD')) { $legacy['APP_DEFAULT_PASSWORD'] } else { Existing-OrNew 'APP_DEFAULT_PASSWORD' 12 }
}

$content = $values.GetEnumerator() | ForEach-Object {
    $value = [string]$_.Value
    if ($value.Contains('$')) {
        "$($_.Key)='$value'"
    } else {
        "$($_.Key)=$value"
    }
}
[IO.File]::WriteAllText(
    $OutputFile,
    ($content -join "`n") + "`n",
    [Text.UTF8Encoding]::new($false)
)
Write-Host "本地部署环境文件已准备：$OutputFile"
Write-Host "JWT Docker secrets 已准备：$secretDirectory"
