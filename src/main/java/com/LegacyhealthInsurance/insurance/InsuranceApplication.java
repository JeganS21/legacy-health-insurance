package com.LegacyhealthInsurance.insurance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // turns on the 5-minute Google Drive sync (@Scheduled)
public class InsuranceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsuranceApplication.class, args);
	}

}
