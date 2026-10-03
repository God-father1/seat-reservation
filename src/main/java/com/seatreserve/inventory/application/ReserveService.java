package com.seatreserve.inventory.application;

import com.seatreserve.inventory.domain.ClaimResult;
import com.seatreserve.inventory.domain.DeclineReason;
import com.seatreserve.inventory.domain.ReserveCommand;
import com.seatreserve.inventory.port.SeatClaimer;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReserveService {
    private final SeatClaimer seatClaimer;

    public ReserveService(SeatClaimer seatClaimer) {
        this.seatClaimer = seatClaimer;
    }

    @Retryable(
            retryFor = TransientDataAccessException.class,
            maxAttempts = 4,
            backoff = @Backoff(delay = 5, multiplier = 2, random = true)
    )
    public ClaimResult reserve(ReserveCommand command) {
        return seatClaimer.claim(command);
    }

    @Recover
    public ClaimResult recover(TransientDataAccessException e, ReserveCommand command) {
        return new ClaimResult.Declined(DeclineReason.RETRY_LATER, Map.of(), false);
    }
}
