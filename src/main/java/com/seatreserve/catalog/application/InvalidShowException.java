package com.seatreserve.catalog.application;

import java.util.List;

public class InvalidShowException extends RuntimeException {
    private final String code;
    private final List<String> offendingLabels;

    public InvalidShowException(String code, List<String> offendingLabels) {
        super(code);
        this.code = code;
        this.offendingLabels = offendingLabels;
    }

    public String getCode() {
        return code;
    }

    public List<String> getOffendingLabels() {
        return offendingLabels;
    }
}
