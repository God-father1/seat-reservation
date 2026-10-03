package com.seatreserve.inventory.port;

import com.seatreserve.inventory.domain.CancelResult;
import java.util.UUID;

public interface ReservationCanceller {
    CancelResult cancel(UUID reservationId, UUID userId);
}
