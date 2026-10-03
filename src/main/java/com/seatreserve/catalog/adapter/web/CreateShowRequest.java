package com.seatreserve.catalog.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;

public record CreateShowRequest(
        @NotBlank String name,
        List<String> seats,
        @Positive long pricePaise,
        @Positive int perUserLimit,
        @Positive int holdTtlSec,
        List<Tier> tiers
) {
    public record Tier(String name, long pricePaise, List<String> seats) {}

    public record SeatSpec(String label, String section, Long pricePaise) {}

    public List<SeatSpec> seatSpecs() {
        List<SeatSpec> specs = new ArrayList<>();
        if (tiers != null) {
            for (Tier tier : tiers) {
                if (tier.seats() != null) {
                    for (String seat : tier.seats()) {
                        specs.add(new SeatSpec(seat, tier.name(), tier.pricePaise()));
                    }
                }
            }
        }
        if (seats != null) {
            for (String seat : seats) {
                specs.add(new SeatSpec(seat, null, null));
            }
        }
        return specs;
    }
}
