package com.seatreserve.shared.web;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthProbeController {

    private final HealthEndpoint healthEndpoint;

    public HealthProbeController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/livez")
    public String livez() {
        return "UP";
    }

    @GetMapping("/readyz")
    public ResponseEntity<String> readyz() {
        var health = healthEndpoint.healthForPath("readiness");
        if (health != null && org.springframework.boot.actuate.health.Status.UP.equals(health.getStatus())) {
            return ResponseEntity.ok("UP");
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("DOWN");
    }

    @GetMapping("/startupz")
    public ResponseEntity<String> startupz() {
        return readyz();
    }
}
