package com.seatreserve.shared.web;

import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

public class TokenSubject {
    public static UUID of(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new InvalidSubjectException("Invalid token subject: must be a UUID", e);
        }
    }
}
