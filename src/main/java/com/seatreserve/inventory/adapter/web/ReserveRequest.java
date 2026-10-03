package com.seatreserve.inventory.adapter.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReserveRequest(
        @NotEmpty @Size(max = 10) List<String> seats,
        String idempotencyKey,
        String mode
) {}
