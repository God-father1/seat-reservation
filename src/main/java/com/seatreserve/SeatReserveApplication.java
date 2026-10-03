package com.seatreserve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
@EnableScheduling
public class SeatReserveApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatReserveApplication.class, args);
	}

}
