package com.seatreserve.inventory.adapter.web;

import com.seatreserve.inventory.application.CancelService;
import com.seatreserve.inventory.domain.CancelResult;
import com.seatreserve.shared.web.Problems;
import com.seatreserve.shared.web.TokenSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/reservations/{reservationId}/cancel")
public class CancelController {

    private final CancelService cancelService;

    public CancelController(CancelService cancelService) {
        this.cancelService = cancelService;
    }

    @PostMapping
    public ResponseEntity<?> cancel(
            @PathVariable UUID reservationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = TokenSubject.of(jwt);
        CancelResult result = cancelService.cancel(reservationId, userId);

        if (result instanceof CancelResult.Cancelled cancelled) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replay", String.valueOf(cancelled.replayed()))
                    .body(Map.of(
                            "reservation_id", cancelled.reservationId(),
                            "status", "cancelled",
                            "seats_released", cancelled.seatsReleased()
                    ));
        } else if (result instanceof CancelResult.Declined declined) {
            var problem = Problems.of(declined.reason().getHttpStatus(), declined.reason().name(), declined.reason().getWireCode(), declined.details());
            return ResponseEntity.status(declined.reason().getHttpStatus()).body(problem);
        }

        return ResponseEntity.internalServerError().build();
    }
}
