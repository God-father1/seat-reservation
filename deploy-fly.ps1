Write-Output '=========================================='
Write-Output ' Fly.io Deployment — seat-reserve-service '
Write-Output '=========================================='

$flyDir = $env:USERPROFILE + '\.fly\bin'
if (-not (Get-Command flyctl -ErrorAction SilentlyContinue)) {
    if (Test-Path ($flyDir + '\flyctl.exe')) {
        $env:Path = $env:Path + ';' + $flyDir
    } else {
        Write-Output 'Installing flyctl...'
        iwr https://fly.io/install.ps1 -useb | iex
        $env:Path = $env:Path + ';' + $flyDir
    }
}

Write-Output 'Checking Fly.io login...'
flyctl auth whoami

Write-Output 'Creating Fly app...'
flyctl apps create seat-reserve-service --region bom

Write-Output 'Creating Fly Postgres database...'
flyctl postgres create --name seat-reserve-service-db --region bom --initial-cluster-size 1 --vm-size-shared-cpu-1x --volume-size 10
flyctl postgres attach seat-reserve-service-db --app seat-reserve-service

Write-Output 'Setting JWT_SECRET secret...'
flyctl secrets set JWT_SECRET='dev-secret-key-at-least-32-bytes-long-for-hs256' --app seat-reserve-service

Write-Output 'Deploying to Fly.io...'
flyctl deploy --app seat-reserve-service

Write-Output '=========================================='
Write-Output ' Deployment Complete: https://seat-reserve-service.fly.dev'
Write-Output '=========================================='
