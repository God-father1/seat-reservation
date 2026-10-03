package com.seatreserve.inventory.port;

import com.seatreserve.inventory.domain.ClaimResult;
import com.seatreserve.inventory.domain.ReserveCommand;

public interface SeatClaimer {
    ClaimResult claim(ReserveCommand command);
}
