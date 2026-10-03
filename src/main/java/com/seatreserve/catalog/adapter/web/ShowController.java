package com.seatreserve.catalog.adapter.web;

import com.seatreserve.catalog.application.ShowProvisioningService;
import com.seatreserve.catalog.application.InvalidShowException;
import com.seatreserve.shared.web.Problems;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowProvisioningService showProvisioningService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ShowController(ShowProvisioningService showProvisioningService, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.showProvisioningService = showProvisioningService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    public ResponseEntity<?> createShow(@Valid @RequestBody CreateShowRequest request) {
        try {
            UUID id = showProvisioningService.provisionShow(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (InvalidShowException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Problems.of(422, "Invalid Show", e.getCode(), Map.of("seats", e.getOffendingLabels())));
        }
    }

    @GetMapping("/{showId}")
    public ResponseEntity<?> getShow(@PathVariable UUID showId) {
        var rows = jdbcTemplate.queryForList(
            "SELECT s.id, s.name, s.price_paise, s.total_seats, s.per_user_limit, s.hold_ttl_sec, " +
            "  (SELECT count(*) FROM seats WHERE show_id = s.id AND status = 'available') AS available, " +
            "  (SELECT count(*) FROM seats WHERE show_id = s.id AND status = 'held') AS held, " +
            "  (SELECT count(*) FROM seats WHERE show_id = s.id AND status = 'confirmed') AS confirmed " +
            "FROM shows s WHERE s.id = ?", showId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> show = rows.get(0);
        long available = ((Number) show.get("available")).longValue();
        long held = ((Number) show.get("held")).longValue();
        long confirmed = ((Number) show.get("confirmed")).longValue();
        int totalSeats = ((Number) show.get("total_seats")).intValue();
        boolean invariantHolds = (available + held + confirmed) == totalSeats;

        return ResponseEntity.ok(Map.of(
            "id", show.get("id"),
            "name", show.get("name"),
            "price_paise", show.get("price_paise"),
            "total_seats", totalSeats,
            "per_user_limit", show.get("per_user_limit"),
            "hold_ttl_sec", show.get("hold_ttl_sec"),
            "seats_available", available,
            "seats_held", held,
            "seats_confirmed", confirmed,
            "invariant_holds", invariantHolds
        ));
    }
}
