package com.seatreserve.inventory.adapter.web;

import com.seatreserve.inventory.application.ReserveService;
import com.seatreserve.inventory.domain.ClaimMode;
import com.seatreserve.inventory.domain.ClaimResult;
import com.seatreserve.inventory.domain.ReserveCommand;
import com.seatreserve.shared.web.Problems;
import com.seatreserve.shared.web.TokenSubject;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/shows/{showId}/reserve")
public class ReservationController {

    private final ReserveService reserveService;
    private final com.seatreserve.shared.observability.ReservationMetrics metrics;

    public ReservationController(ReserveService reserveService, com.seatreserve.shared.observability.ReservationMetrics metrics) {
        this.reserveService = reserveService;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<?> reserve(
            @PathVariable UUID showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ReserveRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = TokenSubject.of(jwt);
        String idempotencyKey = headerKey != null ? headerKey : request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Problems.of(400, "Idempotency Key Required", "idempotency_key_required", Map.of()));
        }

        ReserveCommand command = new ReserveCommand(
                showId,
                userId,
                request.seats(),
                idempotencyKey,
                ClaimMode.parse(request.mode())
        );

        ClaimResult result = reserveService.reserve(command);

        if (result instanceof ClaimResult.Confirmed confirmed) {
            metrics.recordConfirmed(confirmed.replayed());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Idempotent-Replay", String.valueOf(confirmed.replayed()))
                    .body(Map.of(
                            "reservation_id", confirmed.reservationId(),
                            "show_id", confirmed.showId(),
                            "user_id", confirmed.userId(),
                            "seats", confirmed.seats(),
                            "amount_paise", confirmed.amountPaise(),
                            "status", confirmed.status(),
                            "expires_at", confirmed.expiresAt() != null ? confirmed.expiresAt().toString() : Map.of(),
                            "line_items", confirmed.lineItems()
                    ));
        } else if (result instanceof ClaimResult.Declined declined) {
            metrics.recordDeclined(declined.reason(), declined.replayed());
            var problem = Problems.of(declined.reason().getHttpStatus(), declined.reason().name(), declined.reason().getWireCode(), declined.details());
            return ResponseEntity.status(declined.reason().getHttpStatus())
                    .header("Idempotent-Replay", String.valueOf(declined.replayed()))
                    .body(problem);
        }

        return ResponseEntity.internalServerError().build();
    }
}
