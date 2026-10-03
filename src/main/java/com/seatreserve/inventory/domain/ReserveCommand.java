package com.seatreserve.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

public record ReserveCommand(UUID showId, UUID userId, List<String> seats, String idempotencyKey, ClaimMode mode) {
    public ReserveCommand {
        seats = seats.stream().distinct().sorted().toList();
    }

    public byte[] requestHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(showId.toString().getBytes(StandardCharsets.UTF_8));
            md.update(String.join(",", seats).getBytes(StandardCharsets.UTF_8));
            md.update(mode.wireValue().getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
