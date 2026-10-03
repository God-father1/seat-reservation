package com.seatreserve.availability.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Map;
import java.util.Optional;

@Service
public class ShowStateService {
    private final JdbcClient jdbcClient;

    public ShowStateService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Map<String, Object>> find(UUID showId) {
        return jdbcClient.sql("SELECT id, name, price_paise, total_seats, per_user_limit FROM shows WHERE id = ?")
                .param(showId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "id", rs.getObject("id"),
                        "name", rs.getString("name"),
                        "price_paise", rs.getLong("price_paise"),
                        "total_seats", rs.getInt("total_seats"),
                        "per_user_limit", rs.getInt("per_user_limit")
                ))
                .optional();
    }
}
