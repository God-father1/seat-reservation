package com.seatreserve.shared.observability;

import com.seatreserve.inventory.domain.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class ReservationMetrics {
    private final Counter confirmedTotal;
    private final Counter idempotentReplayTotal;
    private final Counter cancelledTotal;
    private final Counter holdsExpiredTotal;
    private final Counter quotaDriftTotal;
    private final Counter invariantViolationsTotal;
    private final Map<DeclineReason, Counter> declinedTotals = new EnumMap<>(DeclineReason.class);

    public ReservationMetrics(MeterRegistry registry) {
        this.confirmedTotal = Counter.builder("reservations_confirmed_total").register(registry);
        this.idempotentReplayTotal = Counter.builder("reservations_idempotent_replay_total").register(registry);
        this.cancelledTotal = Counter.builder("reservations_cancelled_total").register(registry);
        this.holdsExpiredTotal = Counter.builder("holds_expired_total").register(registry);
        this.quotaDriftTotal = Counter.builder("quota_drift_total").register(registry);
        this.invariantViolationsTotal = Counter.builder("seat_invariant_violations_total").register(registry);
        
        for (DeclineReason reason : DeclineReason.values()) {
            declinedTotals.put(reason, Counter.builder("reservations_declined_total")
                    .tag("reason", reason.name().toLowerCase())
                    .register(registry));
        }
    }

    public void recordConfirmed(boolean replayed) {
        if (replayed) {
            idempotentReplayTotal.increment();
        } else {
            confirmedTotal.increment();
        }
    }

    public void recordDeclined(DeclineReason reason, boolean replayed) {
        if (replayed) {
            idempotentReplayTotal.increment();
        } else {
            Counter counter = declinedTotals.get(reason);
            if (counter != null) {
                counter.increment();
            }
        }
    }
}
