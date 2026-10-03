package com.seatreserve.inventory.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface ClaimResult {
    record Confirmed(UUID reservationId, UUID showId, UUID userId, List<String> seats,
                     long amountPaise, List<LineItem> lineItems, String status,
                     OffsetDateTime expiresAt, boolean replayed) implements ClaimResult {}
                     
    record Declined(DeclineReason reason, Map<String, Object> details, boolean replayed) implements ClaimResult {}
    
    record LineItem(String seat, long pricePaise) {}
}
