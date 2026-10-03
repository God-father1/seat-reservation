package com.seatreserve.inventory.domain;

public enum ClaimMode {
    CONFIRM, HOLD;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static ClaimMode parse(String val) {
        if (val == null) return CONFIRM;
        return switch (val.toLowerCase()) {
            case "hold" -> HOLD;
            default -> CONFIRM;
        };
    }
}
