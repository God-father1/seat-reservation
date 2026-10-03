package com.seatreserve.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QuotaReconciler {
    private static final Logger log = LoggerFactory.getLogger(QuotaReconciler.class);
    private final JdbcTemplate jdbcTemplate;

    public QuotaReconciler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${seats.jobs.reconcile-interval-ms:10000}")
    @SchedulerLock(name = "reconcile-quota")
    public void reconcile() {
        try {
            Integer fixed = jdbcTemplate.queryForObject("SELECT reconcile_quota(1000)", Integer.class);
            if (fixed != null && fixed > 0) {
                log.info("Reconciled quota for {} users", fixed);
            }
        } catch (Exception e) {
            log.error("QuotaReconciler failed", e);
        }
    }
}
