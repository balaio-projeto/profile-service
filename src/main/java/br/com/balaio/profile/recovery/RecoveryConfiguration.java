package br.com.balaio.profile.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import java.security.SecureRandom;

@Configuration
@EnableAsync
@EnableConfigurationProperties(RecoveryConfiguration.Properties.class)
public class RecoveryConfiguration {
    @ConfigurationProperties(prefix = "recovery")
    public record Properties(String frontendBaseUrl, String from, Duration tokenTtl) {}
    @Bean SecureRandom secureRandom() { return new SecureRandom(); }
}
