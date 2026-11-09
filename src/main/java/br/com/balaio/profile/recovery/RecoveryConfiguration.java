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
    public record Properties(String frontendBaseUrl, String from, Duration tokenTtl) {
        public Properties {
            if (frontendBaseUrl == null || frontendBaseUrl.isBlank() || from == null || from.isBlank()
                    || tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()
                    || tokenTtl.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("Configuração de recuperação inválida.");
            }
        }
    }
    @Bean SecureRandom secureRandom() { return new SecureRandom(); }
}
