package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class MediaCleanupLoggingTest {
    private final MidiasPerfilPort midias = mock(MidiasPerfilPort.class);
    private final ArmazenamentoMidiaPort storage = mock(ArmazenamentoMidiaPort.class);
    private final MidiaPerfilService service = new MidiaPerfilService(mock(PerfilArtesaoService.class),
            midias, storage, new ValidarImagem());
    private final MidiaPerfil midia = new MidiaPerfil(UUID.randomUUID(), UUID.randomUUID(),
            "profile/private-original-name.png", "image/png", 1, "a".repeat(64));

    @BeforeEach void preparar() {
        when(midias.candidatasLimpeza()).thenReturn(List.of(midia));
        when(midias.prepararExclusao(midia)).thenReturn(true);
    }

    @Test void informaEtapaStorageSemCopiarMensagemDaExcecao(CapturedOutput output) {
        doThrow(new IllegalStateException("secret-token image-content original-name.png"))
                .when(storage).excluir(midia);
        service.limpar();
        assertThat(output.getOut()).contains(midia.id().toString(), "etapa=EXCLUIR_STORAGE", "categoria=INESPERADA")
                .doesNotContain("secret-token", "image-content", "original-name.png", midia.chave());
    }

    @Test void classificaFalhaNaConfirmacaoDoBanco(CapturedOutput output) {
        doThrow(new DataAccessResourceFailureException("sensitive SQL"))
                .when(midias).concluirExclusao(midia.id());
        service.limpar();
        assertThat(output.getOut()).contains(midia.id().toString(), "etapa=CONFIRMAR_EXCLUSAO", "categoria=PERSISTENCIA")
                .doesNotContain("sensitive SQL");
    }

    @Test void informaFalhaNaPreparacaoSemExcluirObjeto(CapturedOutput output) {
        when(midias.prepararExclusao(midia)).thenThrow(new DataAccessResourceFailureException("sensitive SQL"));
        service.limpar();
        assertThat(output.getOut()).contains(midia.id().toString(), "etapa=PREPARAR_EXCLUSAO", "categoria=PERSISTENCIA");
        verifyNoInteractions(storage);
    }

    @Test void falhaNaSelecaoNaoInventaIdentificador(CapturedOutput output) {
        when(midias.candidatasLimpeza()).thenThrow(new DataAccessResourceFailureException("sensitive SQL"));
        service.limpar();
        assertThat(output.getOut()).contains("midiaId=indisponivel", "etapa=SELECIONAR_CANDIDATAS", "categoria=PERSISTENCIA")
                .doesNotContain("sensitive SQL");
    }

    @Test void classificaErroDeDominioDoStorage(CapturedOutput output) {
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).excluir(midia);
        service.limpar();
        assertThat(output.getOut()).contains(midia.id().toString(), "categoria=STORAGE_INDISPONIVEL");
    }
}
