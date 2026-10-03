# Fly.io Deployment Script for seat-reserve-service
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Fly.io Deployment — seat-reserve-service " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Install flyctl if missing
if (-not (Get-Command flyctl -ErrorAction SilentlyContinue)) {
    Write-Host "Installing flyctl CLI..." -ForegroundColor Yellow
    iwr https://fly.io/install.ps1 -useb | iex
    $env:Path += ";$env:USERPROFILE\.fly\bin"
}

# 2. Login check
Write-Host "Checking Fly.io authentication..." -ForegroundColor Yellow
flyctl auth whoami
if ($LASTEXITCODE -ne 0) {
    Write-Host "Please log in to Fly.io..." -ForegroundColor Yellow
    flyctl auth login
}

# 3. Create app and Postgres DB if not exist
Write-Host "Launching Fly.io app and Postgres database..." -ForegroundColor Yellow
flyctl apps create seat-reserve-service --region bom 2>$null

# 4. Attach or create Postgres database
Write-Host "Creating Fly Postgres database..." -ForegroundColor Yellow
flyctl postgres create --name seat-reserve-service-db --region bom --initial-cluster-size 1 --vm-size-shared-cpu-1x --volume-size 10 2>$null
flyctl postgres attach seat-reserve-service-db --app seat-reserve-service 2>$null

# 5. Set secrets
Write-Host "Setting secrets (JWT_SECRET)..." -ForegroundColor Yellow
flyctl secrets set JWT_SECRET="dev-secret-key-at-least-32-bytes-long-for-hs256" --app seat-reserve-service

# 6. Deploy app
Write-Host "Deploying container to Fly.io..." -ForegroundColor Green
flyctl deploy --app seat-reserve-service

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Deployment Complete!" -ForegroundColor Green
Write-Host " App URL: https://seat-reserve-service.fly.dev" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
