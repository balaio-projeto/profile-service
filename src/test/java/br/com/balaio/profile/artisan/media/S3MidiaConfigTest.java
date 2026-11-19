package br.com.balaio.profile.artisan.media;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.assertThat;

class S3MidiaConfigTest {
    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(S3MidiaConfig.class);

    @Test void storageDesabilitadoNaoExigeCredenciaisParaIniciar() {
        contexto.withPropertyValues("MEDIA_S3_ENDPOINT=")
                .run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(S3Client.class));
    }

    @Test void endpointHabilitadoSemCredenciaisFalhaSemFallbackAnonimo() {
        contexto.withPropertyValues("MEDIA_S3_ENDPOINT=http://seaweedfs:8333",
                        "MEDIA_S3_ACCESS_KEY_ID=", "MEDIA_S3_SECRET_ACCESS_KEY=")
                .run(app -> assertThat(app).hasFailed());
    }
}
