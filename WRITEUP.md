# Technical Writeup: Seat Reservation Service

## 1. The Atomic Decision Mechanism
* **Exact Mechanism:** PostgreSQL Stored Procedure (`reserve_seats`) combined with explicit row-level locking (`SELECT ... FOR UPDATE`).
* **Why it's Race-Free:** 
  The reservation logic executes entirely inside an isolated PostgreSQL transaction. When a user requests seats, the procedure executes `SELECT ... FROM seats WHERE label = ANY(...) FOR UPDATE`. This acquires pessimistic row-level locks on the target seats before evaluating availability.
  - Only **one** concurrent transaction can hold the lock for a given seat at any instant.
  - The winner verifies seat status (`available` or `expired hold`) and atomically transitions status to `held` or `confirmed`.
  - Competing transactions block until the winner commits, then re-read the updated status, observe that the seat is taken, and fail cleanly with a domain `409 Conflict (seat_unavailable)`.
* **Multi-Seat Deadlock Avoidance:**
  When a user requests multiple seats (e.g. `["B10", "A1", "C5"]`), the stored procedure canonicalizes and sorts the labels deterministically (`ORDER BY s.seat_no` / `ORDER BY label`) before locking:
  ```sql
  SELECT s.seat_no, s.label, s.status ... FROM seats s
   WHERE s.label = ANY (v_labels)
   ORDER BY s.seat_no FOR UPDATE;
  ```
  Because every concurrent request acquires locks in the exact same global numerical order, circular wait conditions are impossible, completely preventing database deadlocks under high concurrency.

---

## 2. Idempotency Implementation
* **Storage Location:** `idempotency_keys` table in PostgreSQL with composite primary key `(user_id, key, scope)`.
* **Exactly-Once Enforcement:**
  When a request arrives, the procedure attempts an atomic `INSERT INTO idempotency_keys`:
  - **First Attempt (New Key):** Row is inserted with `status_code = 0` (in-flight lock). Reservation logic proceeds and saves the resulting HTTP status and JSON response payload in `idempotency_keys`.
  - **Retried Attempt (Same Key + Same Payload):** The `ON CONFLICT` clause catches the existing key. The procedure compares `sha256(show_id || sorted_seats || mode)`. If identical, it returns the cached `response_body` with HTTP `201 Created` and `Idempotent-Replay: true` header without executing any new seat reservations.
  - **Same Key + Different Payload:** If `request_hash` differs, the procedure rejects the call with `409 Conflict (idempotency_key_conflict)`.

---

## 3. Holds & Expiry Architecture
* **Hold Model:** Time-boxed holds with automatic expiration (`hold_ttl_sec`, default 120 seconds).
* **Expiry & Sweeper:**
  - Holds passively expire when `hold_expires_at <= now()`.
  - A background sweeper (`expire_holds()`) runs on a fixed schedule (coordinated across app instances via ShedLock) to sweep expired holds and restore seat availability and user quotas.
  - **Self-Stealing / Immediate Re-booking:** If a user attempts to book a seat whose hold has expired, the `reserve_seats` procedure detects `hold_expires_at <= now()` and allows the new reservation to claim it immediately without waiting for the background sweeper.
* **Resurrection Safety:** Once a seat transitions to `status = 'confirmed'`, `hold_expires_at` is cleared (`NULL`). Expiry jobs explicitly target `WHERE status = 'held'`, ensuring confirmed seats can never be accidentally expired or returned to availability.

---

## 4. Consistency vs Availability Under a Partition (CAP Theorem)
* **Choice: CP (Consistency over Availability).**
* **Rationale:** In event ticketing, double-selling a physical seat is an unrecoverable real-world failure. 
* **Partition Behavior:** The architecture relies on a single authoritative primary PostgreSQL cluster for ACID state transitions. If a network partition isolates an application instance from the primary database:
  - Spring Boot Actuator's `/actuator/health/readiness` probe fails closed (`503 Service Unavailable`).
  - Load balancers remove the disconnected instance from routing.
  - The service chooses **Consistency** (rejecting bookings when DB is unreachable) over **Availability** (risking split-brain double bookings).

---

## 5. Observability & 2 AM Pager Rules
* **What triggers a 2 AM Pager Call (Critical Alerts):**
  1. **High 5xx Error Rate:** `http_server_requests_seconds_count{status=~"5.."}` > 1% over 5 minutes. (Domain declines like 409/400 must never trigger pages).
  2. **Database Connection Pool Exhaustion:** HikariCP pending connections > 10 for > 30 seconds.
  3. **Readiness Failure:** `/actuator/health/readiness` returning `503` across > 25% of app instances.
  4. **Background Sweeper Lag:** `expire_holds` execution lag exceeding 3x `hold_ttl_sec`.
* **Non-Pageable Metrics (Informational/Warning):**
  - High rate of `409 Conflict` (Expected behavior during high-demand ticket drops).

---

## 6. AI Usage (Honest & Specific Breakdown)
* **Decided & Enforced by Engineers:**
  - Core architectural decisions: CP over AP model, PostgreSQL stored procedure approach for zero-5xx high concurrency, deterministic lock ordering strategy for multi-seat deadlock prevention, Flyway migration design, and JWT security model.
* **Directed by Engineers, Assisted by AI:**
  - Generating Spring Boot controller boilerplate and Jackson JSON serializer configurations.
  - Crafting stress test scripts (`scenario-test.js`, `Burst.java`) to simulate concurrent connection storms.
  - Formatting project documentation, Actuator metric wrappers, and OpenAPI schemas.

---

## 7. Next Steps & Production Improvements
1. **Read-Replicas for Queries:** Route `GET /shows/{id}` and seat map queries to PostgreSQL read-replicas to keep primary DB CPU free for `POST /reserve` transactions.
2. **API Gateway Rate Limiting:** Implement token-bucket rate limiting (e.g. Redis / Envoy) to absorb abusive DDoS traffic before hitting application threads.
3. **WebSockets for Real-Time Seat Maps:** Push live seat hold/release state updates to connected frontend clients via WebSockets.
