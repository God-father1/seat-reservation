#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
export BASE_URL

echo "Running burst test against $BASE_URL"
echo "Requires: Java 21+ with virtual threads"
echo ""

java tools/Burst.java
