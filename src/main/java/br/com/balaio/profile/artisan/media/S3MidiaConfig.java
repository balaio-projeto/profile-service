package br.com.balaio.profile.artisan.media;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableScheduling
public class S3MidiaConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${MEDIA_S3_ENDPOINT:}' != ''")
    S3Client mediaS3Client(@Value("${MEDIA_S3_ENDPOINT}") String endpoint,
            @Value("${MEDIA_S3_REGION:us-east-1}") String region,
            @Value("${MEDIA_S3_ACCESS_KEY_ID:}") String access,
            @Value("${MEDIA_S3_SECRET_ACCESS_KEY:}") String secret) {
        URI uri = URI.create(endpoint);
        if (access.isBlank() || secret.isBlank() || uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalStateException("Configuracao MEDIA_S3 incompleta ou invalida.");
        }
        return S3Client.builder().endpointOverride(uri).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }
}
