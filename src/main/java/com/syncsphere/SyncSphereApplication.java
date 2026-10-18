package com.syncsphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @EnableScheduling} is needed for the presence heartbeat
 * (see {@link com.syncsphere.collaboration.PresenceService#refreshInstanceHeartbeat()}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SyncSphereApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncSphereApplication.class, args);
    }
}
