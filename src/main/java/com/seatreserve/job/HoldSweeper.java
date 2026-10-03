package com.seatreserve.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldSweeper {
    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);
    private final JdbcTemplate jdbcTemplate;

    public HoldSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${seats.jobs.sweep-interval-ms:1000}")
    @SchedulerLock(name = "expire-holds")
    public void sweep() {
        try {
            Integer released = jdbcTemplate.queryForObject("SELECT expire_holds(500)", Integer.class);
            if (released != null && released > 0) {
                log.info("Swept {} expired holds", released);
            }
        } catch (Exception e) {
            log.error("HoldSweeper failed", e);
        }
    }
}
