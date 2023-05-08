package moe.echo.bramblingnote.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;
import java.util.Random;

@Configuration
public class Config {
    @Value("${cache.ttl.min:3600}")
    private int minTtl;
    @Value("${cache.ttl.max:7200}")
    private int maxTtl;

    private int randomInt(int max, int min) {
        Random random = new Random();
        return random.nextInt((max - min) + 1) + min;
    }

    @Bean
    public RedisCacheConfiguration redisCacheConfiguration(CacheProperties properties) {
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig();
        CacheProperties.Redis redis = properties.getRedis();
        if (redis.getTimeToLive() != null) {
            configuration = configuration.entryTtl(
                    redis.getTimeToLive().plus(Duration.ofSeconds(randomInt(maxTtl, minTtl)))
            );
        } else {
            configuration = configuration.entryTtl(Duration.ofSeconds(randomInt(maxTtl, minTtl)));
        }

        return configuration;
    }
}
