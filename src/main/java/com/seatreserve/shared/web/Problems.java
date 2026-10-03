package com.seatreserve.shared.web;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.Map;

public class Problems {
    public static ProblemDetail of(int status, String title, String code, Map<String, Object> details) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), title);
        pd.setType(URI.create("urn:seatreserve:error:" + code));
        pd.setProperty("code", code);
        if (details != null && !details.isEmpty()) {
            details.forEach(pd::setProperty);
        }
        return pd;
    }
}
