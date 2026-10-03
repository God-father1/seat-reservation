package com.seatreserve;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReserveFixture {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReserveFixture(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public UUID createShow(String name, long pricePaise, int totalSeats, int limit, int holdTtl) {
        UUID showId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shows (id, name, price_paise, total_seats, per_user_limit, hold_ttl_sec) VALUES (?, ?, ?, ?, ?, ?)",
                showId, name, pricePaise, totalSeats, limit, holdTtl);
        for (int i = 1; i <= totalSeats; i++) {
            jdbcTemplate.update("INSERT INTO seats (show_id, seat_no, label, status) VALUES (?, ?, ?, 'available')",
                    showId, i, "S" + i);
        }
        return showId;
    }

    public Map<String, Object> reserve(UUID showId, UUID userId, List<String> labels, String key, byte[] hash, String mode) {
        SimpleJdbcCall call = new SimpleJdbcCall(jdbcTemplate)
                .withFunctionName("reserve_seats")
                .declareParameters(
                        new SqlOutParameter("out_status", Types.INTEGER),
                        new SqlOutParameter("out_body", Types.VARCHAR)
                );
        
        try {
            java.sql.Array textArray = jdbcTemplate.getDataSource().getConnection().createArrayOf("text", labels.toArray());
            Map<String, Object> result = call.execute(showId, userId, textArray, key, hash, mode);
            
            Integer status = (Integer) result.get("out_status");
            String bodyJson = (String) result.get("out_body");
            Map<String, Object> body = bodyJson == null ? null : mapper.readValue(bodyJson, Map.class);
            return Map.of("status", status, "body", body != null ? body : Map.of());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
