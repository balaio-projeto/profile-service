package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;

class MidiaPerfilServiceTest {
    final PerfilArtesaoService perfis = mock(PerfilArtesaoService.class);
    final MidiasPerfilPort midias = mock(MidiasPerfilPort.class);
    final ArmazenamentoMidiaPort storage = mock(ArmazenamentoMidiaPort.class);
    final MidiaPerfilService service = new MidiaPerfilService(perfis, midias, storage, new ValidarImagem());
    final UUID perfil = UUID.randomUUID();
    final IdentidadeAutenticada dono = new IdentidadeAutenticada(UUID.randomUUID());
    @BeforeEach void preparar() {
        when(perfis.consultarMeu(dono)).thenReturn(new PerfilArtesao(perfil,dono.contaId(),"slug","Nome","Atelie",
                "","","",MidiaPerfil.PLACEHOLDER));
        when(midias.candidatasLimpeza()).thenReturn(List.of());
    }
    static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    @Test void armazenaRecuperaEAssociaAoProprietario() throws Exception {
        byte[] bytes = png();
        var m = service.armazenar(dono, bytes);
        assertThat(m.perfilId()).isEqualTo(perfil);
        assertThat(m.url()).contains(m.id().toString()).doesNotContain("slug");
        assertThat(m.tipo()).isEqualTo("image/png");
        var order = inOrder(midias, storage);
        order.verify(midias).reservar(m);
        order.verify(storage).enviar(eq(m), aryEq(bytes));
        order.verify(midias).ativar(m);
        when(midias.vinculada(m.id())).thenReturn(Optional.of(m));
        when(storage.recuperar(m)).thenReturn(bytes);
        assertThat(service.recuperar(m.id())).isEqualTo(bytes);
    }
    @Test void falhaS3NaoTrocaReferencia() throws Exception {
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).enviar(any(),any());
        assertThatThrownBy(() -> service.armazenar(dono,png())).isInstanceOf(MidiaException.class);
        verify(midias,never()).ativar(any());
        verify(storage,never()).excluir(any());
    }
    @Test void falhaPostgresMantemObjetoPendenteParaReconciliacao() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(midias).ativar(any());
        assertThatThrownBy(() -> service.armazenar(dono,png())).isInstanceOf(IllegalStateException.class);
        verify(midias).reservar(any());
        verify(storage,never()).excluir(any());
    }
    @Test void removeAssociacaoAntesDeExcluirObjetoEReagendaFalhaFisica() {
        var m = new MidiaPerfil(UUID.randomUUID(),perfil,"profile/x","image/png",1,"a".repeat(64));
        when(midias.candidatasLimpeza()).thenReturn(List.of(m));
        when(midias.prepararExclusao(m)).thenReturn(true);
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).excluir(m);
        service.remover(dono);
        service.limpar();
        var order=inOrder(midias,storage);
        order.verify(midias).remover(perfil);
        order.verify(midias).candidatasLimpeza();
        order.verify(midias).prepararExclusao(m);
        order.verify(storage).excluir(m);
        verify(midias,never()).concluirExclusao(any());
        doNothing().when(storage).excluir(m);
        service.limpar();
        verify(midias).concluirExclusao(m.id());
    }
    @Test void placeholderNaoPossuiConteudoGerenciado() {
        when(midias.vinculada(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recuperar(UUID.randomUUID())).isInstanceOf(MidiaException.class);
        verifyNoInteractions(storage);
    }
    @Test void recusaConteudoCorrompido() {
        var id=UUID.randomUUID();
        var m=new MidiaPerfil(id,perfil,"profile/x","image/png",1,"a".repeat(64));
        when(midias.vinculada(id)).thenReturn(Optional.of(m));
        when(storage.recuperar(m)).thenReturn(new byte[]{1});
        assertThatThrownBy(() -> service.recuperar(id)).isInstanceOf(MidiaException.class);
    }
    @Test void recusaArquivoVazioGrandeOuFalsoSemEscreverStorage() {
        for(byte[] b : List.of(new byte[0],new byte[ValidarImagem.MAX_BYTES+1],"not an image".getBytes())) {
            assertThatThrownBy(() -> service.armazenar(dono,b)).isInstanceOf(MidiaException.class);
        }
        verifyNoInteractions(storage);
    }
    @Test void usaAutorizacaoExistenteAntesDoUpload() {
        when(perfis.consultarMeu(dono)).thenThrow(new IllegalArgumentException("denied"));
        assertThatThrownBy(() -> service.armazenar(dono,new byte[1])).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(midias,storage);
    }
    @Test void decodificaJpegPngEWebpReal() throws Exception {
        var validator=new ValidarImagem();
        assertThat(validator.tipo(png())).isEqualTo("image/png");
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"jpeg",out);
        assertThat(validator.tipo(out.toByteArray())).isEqualTo("image/jpeg");
        byte[] webp=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertThat(validator.tipo(webp)).isEqualTo("image/webp");
        assertThatThrownBy(() -> validator.tipo(Arrays.copyOf(png(),12))).isInstanceOf(MidiaException.class);
    }
}
