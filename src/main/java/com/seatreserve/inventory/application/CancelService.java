package com.seatreserve.inventory.application;

import com.seatreserve.inventory.domain.CancelResult;
import com.seatreserve.inventory.domain.DeclineReason;
import com.seatreserve.inventory.port.ReservationCanceller;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class CancelService {
    private final ReservationCanceller canceller;

    public CancelService(ReservationCanceller canceller) {
        this.canceller = canceller;
    }

    @Retryable(
            retryFor = TransientDataAccessException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 5, multiplier = 2, random = true)
    )
    public CancelResult cancel(UUID reservationId, UUID userId) {
        return canceller.cancel(reservationId, userId);
    }

    @Recover
    public CancelResult recover(TransientDataAccessException e, UUID reservationId, UUID userId) {
        return new CancelResult.Declined(DeclineReason.RETRY_LATER, Map.of());
    }
}
