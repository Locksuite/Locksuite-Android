$ErrorActionPreference = "Stop"
$serviceAccountKey = Join-Path $PSScriptRoot "locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json"

Write-Host "Configurando credenciales de Firebase..." -ForegroundColor Yellow
$env:GOOGLE_APPLICATION_CREDENTIALS = $serviceAccountKey

Push-Location $PSScriptRoot
try {
    Write-Host "Desplegando Firebase Hosting y Database..." -ForegroundColor Cyan
    cmd.exe /c firebase deploy --only hosting,database
    Write-Host "Firebase Hosting y Database desplegados con exito!" -ForegroundColor Green
} finally {
    Pop-Location
}
