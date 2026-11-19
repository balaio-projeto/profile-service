package br.com.balaio.profile.artisan.media;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3MidiaAdapterTest {
    @Test void semConfiguracaoFalhaComMensagemPublicaSegura() {
        @SuppressWarnings("unchecked") ObjectProvider<S3Client> provider=mock(ObjectProvider.class);
        var adapter=new S3MidiaAdapter(provider,"balaio-media");
        var m=new MidiaPerfil(UUID.randomUUID(),UUID.randomUUID(),"profile/test","image/png",1,"a".repeat(64));
        assertThatThrownBy(() -> adapter.enviar(m,new byte[]{1})).isInstanceOf(MidiaException.class)
                .hasMessage("Armazenamento de imagens indisponivel.").hasNoCause();
        assertThatThrownBy(() -> adapter.recuperar(m)).isInstanceOf(MidiaException.class);
        assertThatThrownBy(() -> adapter.excluir(m)).isInstanceOf(MidiaException.class);
    }
}
