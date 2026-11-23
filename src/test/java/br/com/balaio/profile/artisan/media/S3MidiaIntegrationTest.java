package br.com.balaio.profile.artisan.media;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="MEDIA_S3_ENDPOINT",matches=".+")
class S3MidiaIntegrationTest {
    @Test void roundTripComCredencialConsumidora() throws Exception {
        try (var client=new S3MidiaConfig().mediaS3Client(System.getenv("MEDIA_S3_ENDPOINT"),
                System.getenv("MEDIA_S3_REGION"),System.getenv("MEDIA_S3_ACCESS_KEY_ID"),
                System.getenv("MEDIA_S3_SECRET_ACCESS_KEY"))) {
            @SuppressWarnings("unchecked") ObjectProvider<S3Client> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(client);
            var adapter=new S3MidiaAdapter(provider,System.getenv("MEDIA_S3_BUCKET"));
            byte[] bytes=MidiaPerfilServiceTest.png();
            UUID id=UUID.randomUUID();
            var m=new MidiaPerfil(id,UUID.randomUUID(),"profile/diagnostics/pi214-java-"+id,
                    "image/png",bytes.length,MidiaPerfilService.hash(bytes));
            try {
                adapter.enviar(m,bytes);
                assertThat(adapter.recuperar(m)).isEqualTo(bytes);
            } finally { adapter.excluir(m); }
        }
    }
}
