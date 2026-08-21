package com.fatfreecrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Spring Boot service that incrementally takes over
 * {@code /api/v1} from the Rails application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FatFreeCrmApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FatFreeCrmApiApplication.class, args);
    }
}
