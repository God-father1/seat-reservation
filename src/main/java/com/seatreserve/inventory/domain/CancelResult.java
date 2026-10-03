package com.seatreserve.inventory.domain;

import java.util.Map;
import java.util.UUID;

public sealed interface CancelResult {
    record Cancelled(UUID reservationId, UUID showId, int seatsReleased, boolean replayed) implements CancelResult {}
    record Declined(DeclineReason reason, Map<String, Object> details) implements CancelResult {}
}
