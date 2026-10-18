package com.syncsphere.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncsphere.config.SyncSphereProperties;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.support.IntegrationTestBase;
import com.syncsphere.support.StompTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Presence entries must not outlive the connections they describe. Both scenarios below were first
 * reproduced against the Docker Compose stack (killing a container, stopping Redis) before being fixed.
 */
class PresenceCleanupTest extends IntegrationTestBase {

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * After "docker kill" + restart, the instance comes back under the same ID before its heartbeat key
     * expires, so its old entries would look alive forever. The startup sweep must remove them.
     */
    @Test
    void entriesFromAPreviousRunOfThisInstanceAreRemovedAtStartup() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Crash leftovers", "");
        String key = "syncsphere:presence:" + doc.id();
        redis.opsForHash().put(key, "ghost", participantJson("ghost", "Ghost", PRIMARY_INSTANCE_ID));
        redis.opsForHash().put(key, "remote", participantJson("remote", "Remote", "some-other-instance"));
        // Give the other instance a heartbeat, otherwise listParticipants() would (correctly) prune it as dead.
        redis.opsForValue().set("syncsphere:instance:some-other-instance", "alive", Duration.ofMinutes(1));

        try (StompTestClient alice = StompTestClient.connect(port)) {
            String aliceId = alice.join(doc.id(), "Alice").at("/you/participantId").asText();

            int removed = presenceService.removeEntriesFromPreviousRun();

            assertThat(removed).isGreaterThanOrEqualTo(1);
            assertThat(redis.opsForHash().hasKey(key, "ghost")).as("leftover of this instance").isFalse();
            assertThat(redis.opsForHash().hasKey(key, aliceId)).as("live local connection").isTrue();
            assertThat(redis.opsForHash().hasKey(key, "remote")).as("other instances' entries are not ours").isTrue();
        }
    }

    /**
     * A participant whose removal fails (Redis unreachable at that moment) must be removed by a later heartbeat.
     *
     * <p>How the failure is simulated: pausing the Redis container does NOT work, because the timed-out HDEL
     * waits in the TCP buffer and runs as soon as Redis resumes (a timeout does not mean "not executed").
     * Instead, a separate PresenceService connects as a Redis ACL user that is temporarily denied HDEL,
     * so exactly that command fails for real.
     */
    @Test
    void removalThatFailedIsRetriedByTheHeartbeat() throws Exception {
        String user = "presence-test-" + UUID.randomUUID();
        acl("SETUSER", user, "on", ">test-only-password", "~*", "&*", "+@all", "-hdel");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername(user);
        config.setPassword("test-only-password");
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        try {
            PresenceService restrictedService = new PresenceService(new StringRedisTemplate(connectionFactory), objectMapper,
                    new SyncSphereProperties("cleanup-test-instance", List.of("http://localhost:*"), "unused-channel"));
            UUID documentId = UUID.randomUUID();
            String key = "syncsphere:presence:" + documentId;
            Participant bob = new Participant("bob", "Bob", "#3cb44b", "cleanup-test-instance", Instant.now());
            restrictedService.join(documentId, bob);

            assertThatThrownBy(() -> restrictedService.leave(documentId, "bob")).isInstanceOf(DataAccessException.class);
            assertThat(redis.opsForHash().hasKey(key, "bob")).as("removal really failed").isTrue();

            acl("SETUSER", user, "+hdel"); // "Redis is back"
            restrictedService.refreshInstanceHeartbeat();

            assertThat(redis.opsForHash().hasKey(key, "bob")).as("retried by the heartbeat").isFalse();
        } finally {
            connectionFactory.destroy();
            acl("DELUSER", user);
        }
    }

    /** Runs "redis-cli ACL ..." inside the Redis test container. */
    private static void acl(String... arguments) {
        String[] command = new String[arguments.length + 2];
        command[0] = "redis-cli";
        command[1] = "ACL";
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        try {
            var result = REDIS.execInContainer(command);
            assertThat(result.getStdout().strip()).as("redis-cli ACL output").isIn("OK", "1");
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String participantJson(String id, String name, String instanceId) {
        return """
                {"participantId":"%s","displayName":"%s","color":"#888888","instanceId":"%s","joinedAt":"2026-01-01T00:00:00Z"}
                """.formatted(id, name, instanceId).strip();
    }
}
