package com.seatreserve.shared.web;

public class InvalidSubjectException extends RuntimeException {
    public InvalidSubjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
