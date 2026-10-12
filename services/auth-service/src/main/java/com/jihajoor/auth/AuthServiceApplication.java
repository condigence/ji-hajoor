package com.jihajoor.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.jihajoor.auth.config.AuthTokenProperties;
import com.jihajoor.auth.config.EmailAuthProperties;
import com.jihajoor.auth.config.OtpProperties;
import com.jihajoor.auth.config.PasswordLoginProperties;
import com.jihajoor.auth.config.SocialLoginProperties;
import com.jihajoor.auth.seed.DevSeedProperties;

/**
 * Entry point for the Ji-Hajoor Auth Service.
 *
 * <p>Registration and JWT issuance service. The shared security, observability, and
 * outbox libraries are wired automatically via their Spring Boot auto-configurations
 * simply by being on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties({OtpProperties.class, AuthTokenProperties.class, SocialLoginProperties.class,
        PasswordLoginProperties.class, DevSeedProperties.class, EmailAuthProperties.class})
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
