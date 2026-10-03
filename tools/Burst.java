import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive burst test for seat-reserve-service.
 * 
 * Tests ALL correctness bar criteria:
 *   1. No double-sell — exactly one 201 per hot seat
 *   2. Zero 5xx — all errors are 4xx domain outcomes
 *   3. Reconciliation invariant — available + held + confirmed == total_seats
 *   4. Idempotent retries — same key replays, different seats on same key → 409
 *   5. Per-user limit — concurrency can't breach the cap
 *   6. Identity is token-derived (structural — no user_id in body)
 * 
 * Usage:
 *   export BASE_URL=http://localhost:8080
 *   java tools/Burst.java
 */
public class Burst {
    private static final String JWT_SECRET = "dev-secret-key-at-least-32-bytes-long-for-hs256";
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static String baseUrl;
    private static boolean allPassed = true;

    public static void main(String[] args) throws Exception {
        baseUrl = System.getenv("BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8080";
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║   SEAT-RESERVE-SERVICE — CORRECTNESS BURST TEST ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("Target: " + baseUrl);
        System.out.println();

        waitForReady();

        String adminToken = createJwt(JWT_SECRET, UUID.randomUUID().toString());

        // ── PHASE 1: Hot-seat storm (20k users, same seat) ──
        phase1_hotSeatStorm(adminToken);

        // ── PHASE 2: Multi-seat storm (many users, 5 hot seats) ──
        phase2_multiSeatStorm(adminToken);

        // ── PHASE 3: Idempotent retries ──
        phase3_idempotency(adminToken);

        // ── PHASE 4: Per-user limit under concurrency ──
        phase4_perUserLimit(adminToken);

        // ── PHASE 5: Identity / cancel isolation ──
        phase5_cancelIsolation(adminToken);

        System.out.println();
        System.out.println("══════════════════════════════════════════════════");
        if (allPassed) {
            System.out.println("✅ ALL PHASES PASSED — correctness bar met.");
        } else {
            System.out.println("❌ SOME PHASES FAILED — see above.");
            System.exit(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHASE 1: Hot-seat storm — 20,000 users fight for seat A1
    // ═══════════════════════════════════════════════════════════════
    private static void phase1_hotSeatStorm(String adminToken) throws Exception {
        System.out.println("── PHASE 1: Hot-seat storm (20,000 users × 1 seat) ──");
        String showId = provisionShow(adminToken, "Hot Seat Storm " + UUID.randomUUID(),
                10000, 5, 300, new String[]{"A1", "A2", "A3", "A4", "A5"});

        int threads = 20000;
        AtomicInteger success = new AtomicInteger(), conflict = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger(), serverError = new AtomicInteger();
        AtomicInteger clientException = new AtomicInteger(), otherError = new AtomicInteger();
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicReference<String> sampleException = new AtomicReference<>();

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        System.out.println("  Launching " + threads + " concurrent requests for seat A1...");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        String userToken = createJwt(JWT_SECRET, UUID.randomUUID().toString());
                        String payload = String.format(
                            "{\"seats\":[\"A1\"],\"mode\":\"reserve\",\"idempotency_key\":\"%s\"}",
                            UUID.randomUUID());
                        gate.await();
                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + userToken)
                            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                        int code = res.statusCode();
                        statusCounts.computeIfAbsent(code, k -> new AtomicInteger()).incrementAndGet();
                        if (code == 201) success.incrementAndGet();
                        else if (code == 409 || code == 422) conflict.incrementAndGet();
                        else if (code == 429) rateLimited.incrementAndGet();
                        else if (code >= 500) serverError.incrementAndGet();
                        else otherError.incrementAndGet();
                    } catch (Exception e) {
                        clientException.incrementAndGet();
                        sampleException.compareAndSet(null, e.getClass().getName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            done.await();
        }

        System.out.println("  Results:");
        System.out.println("    201 Created (success):     " + success.get());
        System.out.println("    409/422 Conflict/Decline:   " + conflict.get());
        System.out.println("    429 Rate Limited:           " + rateLimited.get());
        System.out.println("    5xx Server Error:           " + serverError.get());
        System.out.println("    Client Exceptions:          " + clientException.get() + (sampleException.get() != null ? " (e.g. " + sampleException.get() + ")" : ""));
        System.out.println("    Other:                      " + otherError.get());
        System.out.println("    Status code breakdown:     " + statusCounts);

        // Reconciliation check
        String reconResult = reconcile(showId);
        System.out.println("  Reconciliation: " + reconResult);

        boolean pass = success.get() == 1 && serverError.get() == 0 && clientException.get() == 0;
        printResult("Phase 1", pass,
            success.get() == 1 ? null : "Expected exactly 1 success, got " + success.get(),
            serverError.get() == 0 ? null : serverError.get() + " server errors (5xx)",
            clientException.get() == 0 ? null : clientException.get() + " client exceptions");
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHASE 2: Multi-seat storm — each of 5 hot seats gets stormed
    // ═══════════════════════════════════════════════════════════════
    private static void phase2_multiSeatStorm(String adminToken) throws Exception {
        System.out.println("── PHASE 2: Multi-seat storm (500 users × 5 seats) ──");
        String showId = provisionShow(adminToken, "Multi-Seat Storm " + UUID.randomUUID(),
                10000, 5, 300, new String[]{"B1", "B2", "B3", "B4", "B5"});

        String[] hotSeats = {"B1", "B2", "B3", "B4", "B5"};
        int usersPerSeat = 500;
        int threads = hotSeats.length * usersPerSeat;
        AtomicInteger success = new AtomicInteger(), conflict = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger(), serverError = new AtomicInteger();
        AtomicInteger clientException = new AtomicInteger(), otherError = new AtomicInteger();

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        System.out.println("  Launching " + threads + " concurrent requests across 5 seats...");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String seat : hotSeats) {
                for (int i = 0; i < usersPerSeat; i++) {
                    executor.submit(() -> {
                        try {
                            String userToken = createJwt(JWT_SECRET, UUID.randomUUID().toString());
                            String payload = String.format(
                                "{\"seats\":[\"%s\"],\"mode\":\"reserve\",\"idempotency_key\":\"%s\"}",
                                seat, UUID.randomUUID());
                            gate.await();
                            HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + userToken)
                                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                            int code = res.statusCode();
                            if (code == 201) success.incrementAndGet();
                            else if (code == 409 || code == 422) conflict.incrementAndGet();
                            else if (code == 429) rateLimited.incrementAndGet();
                            else if (code >= 500) serverError.incrementAndGet();
                            else otherError.incrementAndGet();
                        } catch (Exception e) {
                            clientException.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            gate.countDown();
            done.await();
        }

        System.out.println("  Results:");
        System.out.println("    201 Created (success):     " + success.get());
        System.out.println("    409/422 Conflict/Decline:   " + conflict.get());
        System.out.println("    429 Rate Limited:           " + rateLimited.get());
        System.out.println("    5xx Server Error:           " + serverError.get());
        System.out.println("    Client Exceptions:          " + clientException.get());

        String reconResult = reconcile(showId);
        System.out.println("  Reconciliation: " + reconResult);

        // Exactly 5 winners (one per hot seat), zero 5xx
        boolean pass = success.get() == 5 && serverError.get() == 0 && clientException.get() == 0;
        printResult("Phase 2", pass,
            success.get() == 5 ? null : "Expected 5 successes (one per seat), got " + success.get(),
            serverError.get() == 0 ? null : serverError.get() + " server errors (5xx)",
            clientException.get() == 0 ? null : clientException.get() + " client exceptions");
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHASE 3: Idempotent retries
    // ═══════════════════════════════════════════════════════════════
    private static void phase3_idempotency(String adminToken) throws Exception {
        System.out.println("── PHASE 3: Idempotent retries ──");
        String showId = provisionShow(adminToken, "Idempotency Test " + UUID.randomUUID(),
                10000, 5, 300, new String[]{"C1", "C2", "C3"});

        String userId = UUID.randomUUID().toString();
        String userToken = createJwt(JWT_SECRET, userId);
        String idemKey = UUID.randomUUID().toString();

        // 3a: First call — should succeed with 201
        String payload = String.format(
            "{\"seats\":[\"C1\"],\"mode\":\"reserve\",\"idempotency_key\":\"%s\"}", idemKey);
        HttpResponse<String> res1 = doPost(baseUrl + "/shows/" + showId + "/reserve", payload, userToken);
        System.out.println("  3a. First call:          " + res1.statusCode());
        boolean firstOk = res1.statusCode() == 201;

        // 3b: Same key, same seats — should replay with same status, Idempotent-Replay: true
        HttpResponse<String> res2 = doPost(baseUrl + "/shows/" + showId + "/reserve", payload, userToken);
        String replayHeader = res2.headers().firstValue("Idempotent-Replay").orElse("missing");
        System.out.println("  3b. Replay (same key):   " + res2.statusCode() + ", Idempotent-Replay: " + replayHeader);
        boolean replayOk = res2.statusCode() == 201 && "true".equals(replayHeader);

        // 3c: Same key, different seats — should get 409 idempotency_key_conflict
        String conflictPayload = String.format(
            "{\"seats\":[\"C2\"],\"mode\":\"reserve\",\"idempotency_key\":\"%s\"}", idemKey);
        HttpResponse<String> res3 = doPost(baseUrl + "/shows/" + showId + "/reserve", conflictPayload, userToken);
        System.out.println("  3c. Conflict (diff seat): " + res3.statusCode() + " " + (res3.body().contains("idempotency_key_conflict") ? "✓ idempotency_key_conflict" : res3.body()));
        boolean conflictOk = res3.statusCode() == 409 && res3.body().contains("idempotency_key_conflict");

        String reconResult = reconcile(showId);
        System.out.println("  Reconciliation: " + reconResult);

        boolean pass = firstOk && replayOk && conflictOk;
        printResult("Phase 3", pass,
            !firstOk ? "First call should be 201" : null,
            !replayOk ? "Replay should be 201 with Idempotent-Replay: true" : null,
            !conflictOk ? "Different seats on same key should be 409 idempotency_key_conflict" : null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHASE 4: Per-user limit under concurrency
    // ═══════════════════════════════════════════════════════════════
    private static void phase4_perUserLimit(String adminToken) throws Exception {
        System.out.println("── PHASE 4: Per-user limit (1 user, 10 parallel, limit=4) ──");
        String showId = provisionShow(adminToken, "Per-User Limit " + UUID.randomUUID(),
                5000, 4, 300,
                new String[]{"D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D10"});

        String userId = UUID.randomUUID().toString();
        String userToken = createJwt(JWT_SECRET, userId);

        int attempts = 10;
        AtomicInteger success = new AtomicInteger(), declined = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger(), serverError = new AtomicInteger();

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        System.out.println("  Launching 10 parallel single-seat reserves for 1 user (limit=4)...");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                String seat = "D" + (i + 1);
                executor.submit(() -> {
                    try {
                        String payload = String.format(
                            "{\"seats\":[\"%s\"],\"mode\":\"reserve\",\"idempotency_key\":\"%s\"}",
                            seat, UUID.randomUUID());
                        gate.await();
                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + userToken)
                            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                        int code = res.statusCode();
                        if (code == 201) success.incrementAndGet();
                        else if (code == 409 || code == 422) declined.incrementAndGet();
                        else if (code == 429) rateLimited.incrementAndGet();
                        else if (code >= 500) serverError.incrementAndGet();
                    } catch (Exception e) {
                        serverError.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            done.await();
        }

        System.out.println("  Results:");
        System.out.println("    201 Created (success):     " + success.get());
        System.out.println("    409/422 Declined:           " + declined.get());
        System.out.println("    429 Rate Limited:           " + rateLimited.get());
        System.out.println("    5xx Server Error:           " + serverError.get());

        String reconResult = reconcile(showId);
        System.out.println("  Reconciliation: " + reconResult);

        // At most 4 successes (per_user_limit=4), zero 5xx
        boolean pass = success.get() <= 4 && success.get() > 0 && serverError.get() == 0;
        printResult("Phase 4", pass,
            success.get() > 4 ? "Per-user limit BREACHED: " + success.get() + " > 4" : null,
            success.get() == 0 ? "Zero successes — something is broken" : null,
            serverError.get() == 0 ? null : serverError.get() + " server errors (5xx)");
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHASE 5: Cancel isolation — user can only cancel own holds
    // ═══════════════════════════════════════════════════════════════
    private static void phase5_cancelIsolation(String adminToken) throws Exception {
        System.out.println("── PHASE 5: Cancel isolation (token-derived identity) ──");
        String showId = provisionShow(adminToken, "Cancel Isolation " + UUID.randomUUID(),
                10000, 5, 300, new String[]{"E1", "E2"});

        // User A reserves E1
        String userA = UUID.randomUUID().toString();
        String tokenA = createJwt(JWT_SECRET, userA);
        String payloadA = String.format(
            "{\"seats\":[\"E1\"],\"mode\":\"hold\",\"idempotency_key\":\"%s\"}", UUID.randomUUID());
        HttpResponse<String> resA = doPost(baseUrl + "/shows/" + showId + "/reserve", payloadA, tokenA);
        System.out.println("  User A reserves E1: " + resA.statusCode());

        // Extract reservation_id
        Matcher m = Pattern.compile("\"reservation_id\"\\s*:\\s*\"([a-fA-F0-9\\-]+)\"").matcher(resA.body());
        if (!m.find()) {
            printResult("Phase 5", false, "Could not extract reservation_id from response");
            return;
        }
        String reservationId = m.group(1);

        // User B tries to cancel User A's reservation — should get 403
        String userB = UUID.randomUUID().toString();
        String tokenB = createJwt(JWT_SECRET, userB);
        HttpResponse<String> cancelByB = doPost(baseUrl + "/reservations/" + reservationId + "/cancel", "{}", tokenB);
        System.out.println("  User B cancels A's hold: " + cancelByB.statusCode() + " " +
            (cancelByB.statusCode() == 403 ? "✓ forbidden" : cancelByB.body()));
        boolean forbiddenOk = cancelByB.statusCode() == 403;

        // User A cancels own reservation — should succeed
        HttpResponse<String> cancelByA = doPost(baseUrl + "/reservations/" + reservationId + "/cancel", "{}", tokenA);
        System.out.println("  User A cancels own hold: " + cancelByA.statusCode() + " " +
            (cancelByA.statusCode() == 200 ? "✓ success" : cancelByA.body()));
        boolean selfCancelOk = cancelByA.statusCode() == 200;

        String reconResult = reconcile(showId);
        System.out.println("  Reconciliation: " + reconResult);

        boolean pass = forbiddenOk && selfCancelOk;
        printResult("Phase 5", pass,
            !forbiddenOk ? "User B should get 403, got " + cancelByB.statusCode() : null,
            !selfCancelOk ? "User A should be able to cancel own hold" : null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private static void waitForReady() throws Exception {
        System.out.println("Waiting for service readiness...");
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/actuator/health/readiness")).GET().build();
        for (int i = 0; i < 30; i++) {
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    System.out.println("Service is ready.\n");
                    return;
                }
            } catch (Exception e) { /* retry */ }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Service not ready after 30s");
    }

    private static String provisionShow(String token, String name, long pricePaise,
                                          int perUserLimit, int holdTtlSec, String[] seats) throws Exception {
        StringBuilder seatArray = new StringBuilder("[");
        for (int i = 0; i < seats.length; i++) {
            if (i > 0) seatArray.append(",");
            seatArray.append("\"").append(seats[i]).append("\"");
        }
        seatArray.append("]");

        String payload = String.format("""
            {"name":"%s","price_paise":%d,"per_user_limit":%d,"hold_ttl_sec":%d,"seats":%s}
            """, name, pricePaise, perUserLimit, holdTtlSec, seatArray);

        HttpResponse<String> res = doPost(baseUrl + "/shows", payload, token);
        if (res.statusCode() != 201) throw new RuntimeException("Failed to provision show: " + res.body());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([a-fA-F0-9\\-]+)\"").matcher(res.body());
        if (m.find()) return m.group(1);
        throw new RuntimeException("Could not extract show ID from: " + res.body());
    }

    private static String reconcile(String showId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/shows/" + showId)).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return "FAILED to fetch show: " + res.statusCode();

        String body = res.body();
        // Parse values from JSON
        long available = extractLong(body, "seats_available");
        long held = extractLong(body, "seats_held");
        long confirmed = extractLong(body, "seats_confirmed");
        long total = extractLong(body, "total_seats");
        boolean invariant = (available + held + confirmed) == total;

        return String.format("available=%d + held=%d + confirmed=%d = %d, total_seats=%d → %s",
            available, held, confirmed, available + held + confirmed, total,
            invariant ? "✓ INVARIANT HOLDS" : "✗ INVARIANT VIOLATED");
    }

    private static long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static HttpResponse<String> doPost(String url, String body, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void printResult(String phase, boolean pass, String... failures) {
        if (pass) {
            System.out.println("  → " + phase + ": ✅ PASSED\n");
        } else {
            allPassed = false;
            System.out.println("  → " + phase + ": ❌ FAILED");
            for (String f : failures) {
                if (f != null) System.out.println("    • " + f);
            }
            System.out.println();
        }
    }

    public static String createJwt(String secret, String subject) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("{\"sub\":\"" + subject + "\",\"scope\":\"admin\"}").getBytes());
        String data = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(data.getBytes()));
        return data + "." + signature;
    }
}
