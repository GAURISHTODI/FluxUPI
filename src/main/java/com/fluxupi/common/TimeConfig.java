package com.fluxupi.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock} so anything date-sensitive — instalment
 * due-date roll-over, overdue detection — can be driven deterministically from
 * tests instead of reading the wall clock directly.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
