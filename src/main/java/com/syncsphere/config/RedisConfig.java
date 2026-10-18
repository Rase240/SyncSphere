package com.syncsphere.config;

import com.syncsphere.sync.SyncEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the sync channel. The connection factory and StringRedisTemplate
 * (used for publishing and presence) are auto-configured by Spring Boot from spring.data.redis.*.
 *
 * <p>The container keeps a dedicated subscriber connection open. If Redis restarts, the container
 * logs the failure and re-subscribes automatically; messages published while it was disconnected are
 * lost, because Redis Pub/Sub does not store messages.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer syncEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                    SyncEventListener listener,
                                                                    SyncSphereProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(properties.redisChannel()));
        return container;
    }
}
