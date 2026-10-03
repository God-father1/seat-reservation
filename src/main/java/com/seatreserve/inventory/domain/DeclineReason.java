package com.seatreserve.inventory.domain;

import java.util.Arrays;
import java.util.Optional;

public enum DeclineReason {
    SEAT_UNAVAILABLE("seat_unavailable", 409),
    PER_USER_LIMIT_EXCEEDED("per_user_limit_exceeded", 409),
    IDEMPOTENCY_KEY_CONFLICT("idempotency_key_conflict", 409),
    SALES_NOT_OPEN("sales_not_open", 409),
    RESERVATION_NOT_CANCELLABLE("reservation_not_cancellable", 409),
    TOO_MANY_SEATS("too_many_seats", 422),
    NO_SEATS_REQUESTED("no_seats_requested", 422),
    UNKNOWN_SEAT("unknown_seat", 422),
    SHOW_NOT_FOUND("show_not_found", 404),
    NOT_FOUND("not_found", 404),
    FORBIDDEN("forbidden", 403),
    RETRY_LATER("retry_later", 429);

    private final String wireCode;
    private final int httpStatus;

    DeclineReason(String wireCode, int httpStatus) {
        this.wireCode = wireCode;
        this.httpStatus = httpStatus;
    }

    public String getWireCode() {
        return wireCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static Optional<DeclineReason> fromWireCode(String wireCode) {
        return Arrays.stream(values())
                .filter(r -> r.wireCode.equals(wireCode))
                .findFirst();
    }
}
