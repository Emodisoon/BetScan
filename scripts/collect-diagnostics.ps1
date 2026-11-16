param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DiagnosticsDirectory = 'diagnostics',
    [int]$TimeoutSeconds = 600,
    [string]$OutputPath = 'diagnostics.zip'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/scans" | Out-Null
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
    Write-Host 'A scan is already running; waiting for its result.'
}
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $status = Invoke-RestMethod -Uri "$BaseUrl/api/matches"
    if ((Get-Date) -gt $deadline) { throw 'Scan timed out. Check diagnostics/application.log.' }
} while ($status.scanRunning)
New-Item -ItemType Directory -Force $DiagnosticsDirectory | Out-Null
$status | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 (Join-Path $DiagnosticsDirectory 'api-status.json')
# Copy the active log with read/write sharing so packaging works while Spring is running.
$bundle = Join-Path $DiagnosticsDirectory ('bundle-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
New-Item -ItemType Directory -Force $bundle | Out-Null
foreach ($name in @('betboom', 'pari')) {
    $source = Join-Path $DiagnosticsDirectory $name
    if (Test-Path $source) { Copy-Item -LiteralPath $source -Destination $bundle -Recurse -Force }
}
Copy-Item -LiteralPath (Join-Path $DiagnosticsDirectory 'api-status.json') -Destination $bundle -Force
$log = Join-Path $DiagnosticsDirectory 'application.log'
if (Test-Path $log) {
    $inputStream = [System.IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try {
        $outputStream = [System.IO.File]::Create((Join-Path $bundle 'application.log'))
        try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
    } finally { $inputStream.Dispose() }
}
Compress-Archive -Path (Join-Path $bundle '*') -DestinationPath $OutputPath -Force
$status.sources | Select-Object bookmaker, status, message | Format-Table -Wrap
Write-Host "Created $OutputPath"
