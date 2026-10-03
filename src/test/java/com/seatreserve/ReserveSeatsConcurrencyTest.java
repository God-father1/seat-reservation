package com.seatreserve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class ReserveSeatsConcurrencyTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private DataSource dataSource;

    private ReserveFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ReserveFixture(dataSource);
    }

    @RepeatedTest(5)
    void exactOneWinnerFor500ConcurrentClaims() throws InterruptedException {
        UUID showId = fixture.createShow("show-" + UUID.randomUUID(), 1000, 10, 4, 120);
        
        int concurrency = 500;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrency);
        
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        UUID userId = UUID.randomUUID();
                        byte[] hash = new byte[]{1,2,3};
                        Map<String, Object> res = fixture.reserve(showId, userId, List.of("S1"), UUID.randomUUID().toString(), hash, "confirm");
                        int status = (Integer) res.get("status");
                        statusCounts.computeIfAbsent(status, k -> new AtomicInteger(0)).incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                        statusCounts.computeIfAbsent(500, k -> new AtomicInteger(0)).incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            endLatch.await();
        }

        assertEquals(1, statusCounts.getOrDefault(201, new AtomicInteger(0)).get());
        assertEquals(concurrency - 1, statusCounts.getOrDefault(409, new AtomicInteger(0)).get());
    }
}
