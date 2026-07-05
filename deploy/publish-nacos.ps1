param(
    [string]$Server = "http://127.0.0.1:8848",
    [string]$Namespace = "dev",
    [string]$Group = "EXAM_GROUP",
    [string]$Username = "nacos",
    [string]$Password = "nacos"
)

try {
    $tokenResponse = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v3/auth/user/login" -Body @{ username = $Username; password = $Password }
} catch {
    try {
        $adminResult = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v3/auth/user/admin" -Body @{ username = $Username; password = $Password }
        if ($adminResult.code -ne 0 -and $adminResult.data -ne $true) {
            throw "Nacos admin initialization failed: $($adminResult.message)"
        }
        Write-Host "Initialized Nacos administrator"
        $tokenResponse = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v3/auth/user/login" -Body @{ username = $Username; password = $Password }
    } catch {
        throw "Nacos login failed and administrator could not be initialized: $($_.Exception.Message)"
    }
}
$token = $tokenResponse.accessToken
if (-not $token) { throw "Nacos login failed" }
$encodedToken = [Uri]::EscapeDataString($token)

$namespaceCheck = Invoke-RestMethod -Method Get -Uri "$Server/nacos/v3/admin/core/namespace/check?namespaceId=$([Uri]::EscapeDataString($Namespace))&accessToken=$encodedToken"
if ($namespaceCheck.code -ne 0) { throw "Failed to check Nacos namespace: $($namespaceCheck.message)" }
if (-not $namespaceCheck.data) {
    $namespaceResult = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v3/admin/core/namespace?accessToken=$encodedToken" -Body @{
        namespaceId = $Namespace
        namespaceName = $Namespace
        namespaceDesc = "Exam $Namespace environment"
    }
    if ($namespaceResult.code -ne 0 -or $namespaceResult.data -ne $true) { throw "Failed to create Nacos namespace: $($namespaceResult.message)" }
    Write-Host "Created namespace $Namespace"
}

Get-ChildItem "$PSScriptRoot/nacos-config" -Filter "*.yml" | ForEach-Object {
    $body = @{
        dataId = $_.Name
        groupName = $Group
        namespaceId = $Namespace
        type = "yaml"
        content = Get-Content -Raw $_.FullName
    }
    $result = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v3/admin/cs/config?accessToken=$encodedToken" -Body $body
    if ($result.code -ne 0 -or $result.data -ne $true) { throw "Failed to publish $($_.Name): $($result.message)" }
    Write-Host "Published $($_.Name)"
}
