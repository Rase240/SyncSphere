package com.syncsphere.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Application-specific settings bound from the {@code syncsphere.*} keys in application.yml.
 * Each value can be overridden with an environment variable (see .env.example).
 *
 * @param instanceId     a human-readable name for this running instance (e.g. "app-1"). It is stamped
 *                       on every event so logs and the UI show which instance produced a change.
 * @param allowedOrigins browser origins allowed to open the WebSocket connection.
 * @param redisChannel   the Redis Pub/Sub channel all instances publish to and subscribe on.
 */
@Validated
@ConfigurationProperties(prefix = "syncsphere")
public record SyncSphereProperties(
        @NotBlank String instanceId,
        @NotEmpty List<String> allowedOrigins,
        @NotBlank String redisChannel) {
}
