package com.seatreserve.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidSubjectException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSubject(InvalidSubjectException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Problems.of(401, "Unauthenticated", "unauthenticated", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAll(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            if (errorResponse.getStatusCode().is4xxClientError()) {
                String code = errorResponse.getStatusCode().toString().toLowerCase().replaceAll("[^a-z]", "_");
                return ResponseEntity.status(errorResponse.getStatusCode())
                        .body(Problems.of(errorResponse.getStatusCode().value(), errorResponse.getBody().getTitle(), code, Map.of()));
            }
        }
        
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Problems.of(500, "Internal Server Error", "internal_error", Map.of()));
    }
}
