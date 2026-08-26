package com.miqroera.miqrokey.controlplane.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the alert evaluation schedule (G4.5). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
