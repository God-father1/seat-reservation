# High-Concurrency Seat Reservation Service

A production-grade, race-condition-proof seat reservation backend built with **Java 21 (Spring Boot 3)** and **PostgreSQL**.

- **Live Service URL:** `https://seat-reservation-production-fee9.up.railway.app`
- **Health Endpoint:** `https://seat-reservation-production-fee9.up.railway.app/actuator/health`
- **Metrics Endpoint:** `https://seat-reservation-production-fee9.up.railway.app/actuator/prometheus`

---

## Key Features

1. **Zero Double-Sell Guarantee:** Atomic row-level locks (`SELECT ... FOR UPDATE ORDER BY seat_no`) in a PostgreSQL stored procedure ensure exactly one winner per hot seat.
2. **Zero 5xx Under Load:** All domain conflicts (seat unavailable, per-user limit exceeded, idempotency key mismatch) return clean `4xx` responses (`409`, `400`, `422`).
3. **Strict Idempotency:** Exactly-once reservation enforcement via `idempotency_keys` table with SHA-256 payload verification.
4. **Time-Boxed Holds & Auto-Expiry:** Automatic background sweeping of expired holds via ShedLock.
5. **Observability:** Prometheus metrics (`reservation_confirmed_total`, `reservation_declined_total`, seat gauges) and MDC request tracing with correlation IDs.

---

## One-Command Burst Test

You can run the high-concurrency burst test targeting the live production deployment or a local setup with a single command:

### Run Java Burst Test (Recommended)
```bash
./burst.sh https://seat-reservation-production-fee9.up.railway.app
```

### Run Node.js Scenario Test
```bash
node scenario-test.js https://seat-reservation-production-fee9.up.railway.app
```

The script will:
1. Provision a fresh multi-tier show with 1,000 seats.
2. Simulate thousands of concurrent users storming the same hot seats.
3. Print status code distribution (`201 Created`, `409 Conflict`, `5xx Errors`).
4. Reconcile database state (`available + held + confirmed == total_seats`).

---

## Running Locally with Docker Compose

### Prerequisites
- Docker Engine & Docker Compose

### Start Service & PostgreSQL
```bash
docker-compose up --build
```
The application will start on `http://localhost:8080`.

### Health & Readiness Endpoints
- **Liveness:** `GET http://localhost:8080/actuator/health/liveness`
- **Readiness:** `GET http://localhost:8080/actuator/health/readiness` (fails closed if DB is unreachable)
- **Prometheus Metrics:** `GET http://localhost:8080/actuator/prometheus`

---

## Documentation

Detailed architectural decisions, concurrency guarantees, CAP theorem trade-offs, 2 AM pager rules, and AI usage breakdown are documented in [WRITEUP.md](file:///c:/Users/balah/.gemini/antigravity-ide/scratch/seat-reserve-service/WRITEUP.md).
