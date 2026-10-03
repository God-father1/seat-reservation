package com.seatreserve.inventory.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreserve.inventory.domain.CancelResult;
import com.seatreserve.inventory.domain.DeclineReason;
import com.seatreserve.inventory.port.ReservationCanceller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcReservationCanceller implements ReservationCanceller {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcReservationCanceller(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CancelResult cancel(UUID reservationId, UUID userId) {
        SimpleJdbcCall call = new SimpleJdbcCall(jdbcTemplate)
                .withFunctionName("cancel_reservation")
                .declareParameters(
                        new SqlOutParameter("out_status", Types.INTEGER),
                        new SqlOutParameter("out_body", Types.VARCHAR)
                );

        try {
            Map<String, Object> result = call.execute(reservationId, userId);

            Integer status = (Integer) result.get("out_status");
            String bodyJson = (String) result.get("out_body");
            Map<String, Object> body = mapper.readValue(bodyJson, new TypeReference<Map<String, Object>>() {});
            
            if (status == 200) {
                int released = (Integer) body.getOrDefault("seats_released", 0);
                boolean replayed = Boolean.TRUE.equals(body.get("replayed"));
                return new CancelResult.Cancelled(reservationId, null, released, replayed);
            } else {
                String code = (String) body.get("code");
                DeclineReason reason = DeclineReason.fromWireCode(code).orElse(DeclineReason.NOT_FOUND);
                return new CancelResult.Declined(reason, body);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to cancel reservation", e);
        }
    }
}
