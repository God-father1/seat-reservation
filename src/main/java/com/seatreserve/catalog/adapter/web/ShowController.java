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

    public ShowController(ShowProvisioningService showProvisioningService) {
        this.showProvisioningService = showProvisioningService;
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
}
