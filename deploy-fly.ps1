$flyExe = $env:USERPROFILE + '\.fly\bin\flyctl.exe'

if (-not (Test-Path $flyExe)) {
    Write-Host 'Downloading flyctl binary...'
    New-Item -ItemType Directory -Force -Path ($env:USERPROFILE + '\.fly\bin') | Out-Null
    Invoke-WebRequest -Uri 'https://github.com/superfly/flyctl/releases/download/v0.3.165/flyctl_0.3.165_Windows_x86_64.zip' -OutFile ($env:USERPROFILE + '\.fly\bin\flyctl.zip')
    Expand-Archive -Path ($env:USERPROFILE + '\.fly\bin\flyctl.zip') -DestinationPath ($env:USERPROFILE + '\.fly\bin') -Force
    Remove-Item ($env:USERPROFILE + '\.fly\bin\flyctl.zip')
}

Write-Host '==========================================' -ForegroundColor Cyan
Write-Host ' Fly.io Deployment - seat-reserve-service ' -ForegroundColor Cyan
Write-Host '==========================================' -ForegroundColor Cyan

& $flyExe version

Write-Host 'Checking Fly.io authentication...' -ForegroundColor Yellow
& $flyExe auth whoami
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Please authenticate with Fly.io...' -ForegroundColor Yellow
    & $flyExe auth login
}

Write-Host 'Creating Fly app seat-reserve-service...' -ForegroundColor Yellow
& $flyExe apps create seat-reserve-service --region bom

Write-Host 'Creating Fly Postgres database...' -ForegroundColor Yellow
& $flyExe postgres create --name seat-reserve-service-db --region bom --initial-cluster-size 1 --vm-size-shared-cpu-1x --volume-size 10
& $flyExe postgres attach seat-reserve-service-db --app seat-reserve-service

Write-Host 'Setting JWT_SECRET secret...' -ForegroundColor Yellow
& $flyExe secrets set JWT_SECRET='dev-secret-key-at-least-32-bytes-long-for-hs256' --app seat-reserve-service

Write-Host 'Deploying container to Fly.io...' -ForegroundColor Green
& $flyExe deploy --app seat-reserve-service

Write-Host '==========================================' -ForegroundColor Cyan
Write-Host ' Deployment Complete!' -ForegroundColor Green
Write-Host ' App URL: https://seat-reserve-service.fly.dev' -ForegroundColor Cyan
Write-Host '==========================================' -ForegroundColor Cyan
