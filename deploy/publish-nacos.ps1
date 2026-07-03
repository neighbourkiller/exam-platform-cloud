param(
    [string]$Server = "http://127.0.0.1:8848",
    [string]$Namespace = "dev",
    [string]$Group = "EXAM_GROUP",
    [string]$Username = "nacos",
    [string]$Password = "nacos"
)

$tokenResponse = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v1/auth/login" -Body @{ username = $Username; password = $Password }
$token = $tokenResponse.accessToken
if (-not $token) { throw "Nacos login failed" }

Get-ChildItem "$PSScriptRoot/nacos-config" -Filter "*.yml" | ForEach-Object {
    $body = @{
        dataId = $_.Name
        group = $Group
        tenant = $Namespace
        type = "yaml"
        content = Get-Content -Raw $_.FullName
        accessToken = $token
    }
    $result = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v1/cs/configs" -Body $body
    if ($result -ne $true) { throw "Failed to publish $($_.Name)" }
    Write-Host "Published $($_.Name)"
}
