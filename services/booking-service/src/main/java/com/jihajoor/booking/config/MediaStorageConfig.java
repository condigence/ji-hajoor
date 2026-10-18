package com.jihajoor.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jihajoor.booking.media.MediaStorage;
import com.jihajoor.booking.media.S3MediaStorageAdapter;

/**
 * Wires the {@link MediaStorage} booking media is written to and read from. Which store it writes
 * to (S3 when configured and reachable, local disk otherwise, or a forced mode) is decided once,
 * here at startup, by {@link MediaStorage#create}; see there for the rules.
 *
 * <p>The S3 client is closed with the context ({@code destroyMethod}), which matters in a JVM that
 * hosts several services and may close one context while the others keep running.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaStorageConfig {

    @Bean(destroyMethod = "close")
    public MediaStorage mediaStorage(MediaStorageProperties properties) {
        return MediaStorage.create(properties, S3MediaStorageAdapter::create);
    }
}
