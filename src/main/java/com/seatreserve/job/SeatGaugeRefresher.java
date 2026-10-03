package com.seatreserve.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SeatGaugeRefresher {
    private static final Logger log = LoggerFactory.getLogger(SeatGaugeRefresher.class);
    private final JdbcTemplate jdbcTemplate;
    private final MultiGauge seatsGauge;

    public SeatGaugeRefresher(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.seatsGauge = MultiGauge.builder("seats_available").register(registry);
    }

    @Scheduled(fixedDelayString = "${seats.metrics.gauge-refresh-ms:2000}")
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> rows = jdbcTemplate.query(
                "SELECT show_id, count(*) as c FROM seats WHERE status = 'available' GROUP BY show_id",
                (rs, rowNum) -> MultiGauge.Row.of(io.micrometer.core.instrument.Tags.of("show_id", rs.getString("show_id")), rs.getInt("c"))
            );
            seatsGauge.register(rows, true);
        } catch (Exception e) {
            log.error("SeatGaugeRefresher failed", e);
        }
    }
}
