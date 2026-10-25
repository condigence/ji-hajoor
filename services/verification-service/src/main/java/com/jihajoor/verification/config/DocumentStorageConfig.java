package com.jihajoor.verification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jihajoor.verification.storage.DocumentStorage;
import com.jihajoor.verification.storage.S3DocumentStorageAdapter;

/**
 * Wires the {@link DocumentStorage} verification documents are written to and read from. Which
 * store it writes to (S3 when configured and reachable, local disk otherwise, or a forced mode) is
 * decided once, here at startup, by {@link DocumentStorage#create}; see there for the rules.
 *
 * <p>The S3 client is closed with the context ({@code destroyMethod}), which matters in a JVM that
 * hosts several services and may close one context while the others keep running.
 */
@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class DocumentStorageConfig {

    @Bean(destroyMethod = "close")
    public DocumentStorage documentStorage(DocumentStorageProperties properties) {
        return DocumentStorage.create(properties, S3DocumentStorageAdapter::create);
    }
}
