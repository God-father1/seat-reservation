package com.seatreserve.inventory.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatreserve.inventory.domain.ClaimResult;
import com.seatreserve.inventory.domain.DeclineReason;
import com.seatreserve.inventory.domain.ReserveCommand;
import com.seatreserve.inventory.port.SeatClaimer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcSeatClaimer implements SeatClaimer {
    private final JdbcClient jdbcClient;
    private final ObjectMapper mapper;

    public JdbcSeatClaimer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public ClaimResult claim(ReserveCommand command) {
        try {
            Map<String, Object> result = jdbcClient.sql("SELECT out_status, out_body FROM reserve_seats(:p_show_id, :p_user_id, :p_seats, :p_idem, :p_hash, :p_mode)")
                    .param("p_show_id", command.showId())
                    .param("p_user_id", command.userId())
                    .param("p_seats", command.seats().toArray(String[]::new))
                    .param("p_idem", command.idempotencyKey())
                    .param("p_hash", command.requestHash())
                    .param("p_mode", command.mode().wireValue())
                    .query().singleRow();

            Integer status = (Integer) result.get("out_status");
            String bodyJson = result.get("out_body").toString();
            Map<String, Object> body = mapper.readValue(bodyJson, new TypeReference<Map<String, Object>>() {});
            
            boolean replayed = Boolean.TRUE.equals(body.get("replayed"));
            
            if (status == 201) {
                List<String> seats = (List<String>) body.get("seats");
                List<ClaimResult.LineItem> lineItems = List.of();
                if (body.containsKey("line_items")) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("line_items");
                    lineItems = items.stream()
                        .map(item -> new ClaimResult.LineItem(
                            (String) item.get("seat"), 
                            ((Number) item.get("price_paise")).longValue()))
                        .toList();
                }
                
                String expiresAtStr = (String) body.get("expires_at");
                OffsetDateTime expiresAt = expiresAtStr != null ? OffsetDateTime.parse(expiresAtStr) : null;
                
                return new ClaimResult.Confirmed(
                        UUID.fromString((String) body.get("reservation_id")),
                        UUID.fromString((String) body.get("show_id")),
                        UUID.fromString((String) body.get("user_id")),
                        seats,
                        ((Number) body.get("amount_paise")).longValue(),
                        lineItems,
                        (String) body.get("status"),
                        expiresAt,
                        replayed
                );
            } else {
                String code = (String) body.get("code");
                DeclineReason reason = DeclineReason.fromWireCode(code).orElse(DeclineReason.NOT_FOUND);
                return new ClaimResult.Declined(reason, body, replayed);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to claim seats", e);
        }
    }
}
