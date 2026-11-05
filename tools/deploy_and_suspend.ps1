# deploy_and_suspend.ps1
$ErrorActionPreference = "Stop"

Write-Host "Configurando prevencion de suspension de Windows durante el despliegue..." -ForegroundColor Cyan

$csPath = Join-Path $PSScriptRoot "SleepUtil.cs"
Add-Type -Path $csPath

[Win32Sleep.SleepUtil]::PreventSleep()
Write-Host "Bloqueo de suspension activado: la PC permanecera encendida durante todo el despliegue." -ForegroundColor Green

try {
    Write-Host "Iniciando deploy_all.ps1..." -ForegroundColor Cyan
    & .\deploy_all.ps1
    Write-Host "Despliegue finalizado con exito total!" -ForegroundColor Green

    [Win32Sleep.SleepUtil]::AllowSleep()

    Write-Host "Suspendiendo la PC en 5 segundos..." -ForegroundColor Yellow
    Start-Sleep -Seconds 5
    rundll32.exe powrprof.dll,SetSuspendState 0,1,0
} catch {
    [Win32Sleep.SleepUtil]::AllowSleep()
    Write-Error "El despliegue fallo con error: $_"
}
