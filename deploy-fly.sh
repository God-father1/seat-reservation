#!/usr/bin/env bash
set -euo pipefail

echo "=========================================="
echo " Fly.io Deployment — seat-reserve-service "
echo "=========================================="

if ! command -v flyctl &> /dev/null; then
    echo "Installing flyctl..."
    curl -L https://fly.io/install.sh | sh
    export PATH="$HOME/.fly/bin:$PATH"
fi

echo "Checking Fly.io login..."
if ! flyctl auth whoami &> /dev/null; then
    echo "Please log in to Fly.io:"
    flyctl auth login
fi

echo "Creating Fly app..."
flyctl apps create seat-reserve-service --region bom || true

echo "Creating Fly Postgres database..."
flyctl postgres create --name seat-reserve-service-db --region bom --initial-cluster-size 1 --vm-size-shared-cpu-1x --volume-size 10 || true
flyctl postgres attach seat-reserve-service-db --app seat-reserve-service || true

echo "Setting secret JWT_SECRET..."
flyctl secrets set JWT_SECRET="dev-secret-key-at-least-32-bytes-long-for-hs256" --app seat-reserve-service

echo "Deploying to Fly.io..."
flyctl deploy --app seat-reserve-service

echo "=========================================="
echo " Deployment Successful!"
echo " App URL: https://seat-reserve-service.fly.dev"
echo "=========================================="
