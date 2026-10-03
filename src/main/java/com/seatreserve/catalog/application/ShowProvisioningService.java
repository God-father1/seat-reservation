package com.seatreserve.catalog.application;

import com.seatreserve.catalog.adapter.web.CreateShowRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class ShowProvisioningService {
    private final JdbcTemplate jdbcTemplate;

    public ShowProvisioningService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID provisionShow(CreateShowRequest request) {
        if (request.seatSpecs() == null || request.seatSpecs().isEmpty()) {
            throw new InvalidShowException("empty_seat_set", List.of());
        }

        List<String> allLabels = request.seatSpecs().stream().map(CreateShowRequest.SeatSpec::label).toList();
        long distinctCount = allLabels.stream().distinct().count();
        if (distinctCount != allLabels.size()) {
            throw new InvalidShowException("duplicate_seat_label", allLabels);
        }

        UUID showId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shows (id, name, price_paise, total_seats, per_user_limit, hold_ttl_sec) VALUES (?, ?, ?, ?, ?, ?)",
                showId, request.name(), request.pricePaise(), allLabels.size(), request.perUserLimit(), request.holdTtlSec());

        String sql = "INSERT INTO seats (show_id, seat_no, label, section, price_paise) " +
                     "SELECT ?, ordinality, label, section, price_paise " +
                     "FROM unnest(?::text[], ?::text[], ?::bigint[]) WITH ORDINALITY AS t(label, section, price_paise)";

        String[] labels = request.seatSpecs().stream().map(CreateShowRequest.SeatSpec::label).toArray(String[]::new);
        String[] sections = request.seatSpecs().stream().map(CreateShowRequest.SeatSpec::section).toArray(String[]::new);
        Long[] prices = request.seatSpecs().stream().map(CreateShowRequest.SeatSpec::pricePaise).toArray(Long[]::new);

        jdbcTemplate.update(sql, showId, labels, sections, prices);

        return showId;
    }
}
