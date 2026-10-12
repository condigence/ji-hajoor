package com.jihajoor.shared.observability.json;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** Applies {@link JiHajoorJson}'s settings to the {@code JsonMapper} Spring Boot builds. */
@AutoConfiguration
@ConditionalOnClass(JsonMapperBuilderCustomizer.class)
public class JiHajoorJacksonAutoConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer jihajoorJsonMapperBuilderCustomizer() {
        return JiHajoorJson::configure;
    }
}
