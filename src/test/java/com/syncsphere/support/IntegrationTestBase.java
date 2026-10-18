package com.syncsphere.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Base class for tests that need the real infrastructure: a PostgreSQL and a Redis container started by
 * Testcontainers (Docker must be running).
 *
 * <p>The containers are started once per test JVM in the static block ("singleton container" pattern) and
 * shared by all test classes; Testcontainers removes them when the JVM exits. All subclasses use the same
 * Spring configuration, so Spring also reuses a single application context between them.
 *
 * <p>Tests do not clean the database between methods; each test creates its own documents with fresh
 * UUIDs, so they cannot interfere with each other.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "syncsphere.instance-id=" + IntegrationTestBase.PRIMARY_INSTANCE_ID)
public abstract class IntegrationTestBase {

    public static final String PRIMARY_INSTANCE_ID = "test-instance-1";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("syncsphere")
            .withUsername("syncsphere")
            .withPassword("test-only-password");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        infrastructureProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** Connection settings for the shared containers; also used to boot a second instance in MultiInstanceSyncTest. */
    protected static Map<String, String> infrastructureProperties() {
        return Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "spring.datasource.hikari.maximum-pool-size", "5",
                "spring.data.redis.host", REDIS.getHost(),
                "spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
    }
}
