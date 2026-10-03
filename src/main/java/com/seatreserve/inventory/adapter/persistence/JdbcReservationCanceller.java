package com.seatreserve.inventory.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreserve.inventory.domain.CancelResult;
import com.seatreserve.inventory.domain.DeclineReason;
import com.seatreserve.inventory.port.ReservationCanceller;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcReservationCanceller implements ReservationCanceller {
    private final JdbcClient jdbcClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcReservationCanceller(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public CancelResult cancel(UUID reservationId, UUID userId) {
        try {
            Map<String, Object> result = jdbcClient.sql("SELECT out_status, out_body FROM cancel_reservation(:p_reservation_id, :p_user_id)")
                    .param("p_reservation_id", reservationId)
                    .param("p_user_id", userId)
                    .query().singleRow();

            Integer status = (Integer) result.get("out_status");
            String bodyJson = result.get("out_body").toString();
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
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to cancel reservation", e);
        }
    }
}
