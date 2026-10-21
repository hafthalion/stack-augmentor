package com.hafnium.it.spring;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Prints the stack trace of the exception that {@link Shipping#ship} throws, which the tests check. */
@SpringBootApplication
public class SpringApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringApp.class, args);
    }

    @Bean
    CommandLineRunner run(Shipping shipping) {
        return args -> {
            try {
                shipping.ship("o-1");
            } catch (IllegalArgumentException e) {
                e.printStackTrace(System.out);
            }
        };
    }
}
