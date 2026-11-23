package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "balaio.media.cleanup-delay=3600000")
@Import(PostgreSQLTestConfiguration.class)
class MediaCleanupPersistenceTest {
    @MockitoBean(enforceOverride = true) InicializarAdministradorUseCase admin;
    @MockitoBean(enforceOverride = true) ArmazenamentoMidiaPort storage;
    @Autowired MidiasPerfilPort midias;
    @Autowired JdbcTemplate jdbc;
    @Autowired PerfilArtesaoService perfis;
    @Autowired MidiaPerfilService application;
    private UUID conta;
    private UUID perfil;

    @BeforeEach void criarPerfil() {
        conta = UUID.randomUUID();
        perfil = UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",
                conta, "Teste", conta + "@test.invalid", "{bcrypt}$2b$12$" + ".".repeat(53), "artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie) VALUES (?,?,?,?,?)",
                perfil, conta, "limpeza-" + perfil, "Teste", "Atelie");
    }

    @AfterEach void limparFixture() {
        // These rows use mocked S3. No user objects exist for this fixture.
        jdbc.update("DELETE FROM midias_perfil WHERE perfil_id=?", perfil);
        jdbc.update("DELETE FROM contas WHERE id=?", conta);
    }

    private MidiaPerfil reservar() {
        UUID id = UUID.randomUUID();
        var m = new MidiaPerfil(id, perfil, "profile/" + perfil + "/" + id,
                "image/png", 1, "a".repeat(64));
        midias.reservar(m);
        return m;
    }

    private void abandonar(MidiaPerfil m) {
        jdbc.update("UPDATE midias_perfil SET criada_em=CURRENT_TIMESTAMP-INTERVAL '48 hours', "
                + "proxima_tentativa=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?", m.id());
    }

    private void vencerPrazo(UUID id) {
        jdbc.update("UPDATE midias_perfil SET proxima_tentativa=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", id);
    }

    @Test void falhaPersistePrazoEReinicioDoWorkerRetomaDepoisDoIntervalo() {
        var m = reservar();
        abandonar(m);
        doThrow(new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL)).when(storage).excluir(m);
        application.limpar();
        assertThat(midias.candidatasLimpeza()).doesNotContain(m);
        assertThat(jdbc.queryForObject("SELECT estado FROM midias_perfil WHERE id=?", String.class, m.id()))
                .isEqualTo("DELETE");
        assertThat(jdbc.queryForObject("SELECT proxima_tentativa > CURRENT_TIMESTAMP FROM midias_perfil WHERE id=?",
                Boolean.class, m.id())).isTrue();

        // Recreate the worker with the same persisted queue, with no in-memory retry state.
        var reiniciado = new MidiaPerfilService(perfis, midias, storage, new ValidarImagem());
        reiniciado.limpar();
        verify(storage, times(1)).excluir(m);
        vencerPrazo(m.id());
        doNothing().when(storage).excluir(m);
        reiniciado.limpar();
        verify(storage, times(2)).excluir(m);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM midias_perfil WHERE id=?", Integer.class, m.id())).isZero();
    }

    @Test void vinteFalhasPersistentesNaoBloqueiamOProximoLote() {
        var falhas = new ArrayList<MidiaPerfil>();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            var m = reservar();
            abandonar(m);
            falhas.add(m);
            ids.add(m.id());
        }
        var seguinte = reservar();
        jdbc.update("UPDATE midias_perfil SET estado='DELETE' WHERE id=?", seguinte.id());
        doAnswer(call -> {
            MidiaPerfil m = call.getArgument(0);
            if (ids.contains(m.id())) throw new MidiaException(MidiaException.Motivo.STORAGE_INDISPONIVEL);
            return null;
        }).when(storage).excluir(any());
        application.limpar();
        verify(storage, never()).excluir(seguinte);
        assertThat(midias.candidatasLimpeza()).containsExactly(seguinte);
        application.limpar();
        verify(storage).excluir(seguinte);
        assertThat(midias.candidatasLimpeza()).isEmpty();
        for (var m : falhas) verify(storage, times(1)).excluir(m);
    }

    @Test void revalidaCandidataQueFoiAtivadaAntesDaExclusao() {
        var m = reservar();
        abandonar(m);
        assertThat(midias.candidatasLimpeza()).contains(m);
        midias.ativar(m);
        assertThat(midias.prepararExclusao(m)).isFalse();
        application.limpar();
        verifyNoInteractions(storage);
        assertThat(midias.vinculada(m.id())).contains(m);
    }

    @Test void reservaPersistidaImpedeDoisWorkersERecuperaCrashAntesDoDelete() {
        var m = reservar();
        abandonar(m);
        assertThat(midias.prepararExclusao(m)).isTrue();
        assertThat(midias.prepararExclusao(m)).isFalse();
        assertThat(midias.candidatasLimpeza()).isEmpty();
        vencerPrazo(m.id());
        var reiniciado = new MidiaPerfilService(perfis, midias, storage, new ValidarImagem());
        reiniciado.limpar();
        verify(storage).excluir(m);
    }

    @Test void deleteFisicoConfirmadoMasFalhaNoBancoPodeSerRepetidoAposReinicio() {
        var m = reservar();
        abandonar(m);
        assertThat(midias.prepararExclusao(m)).isTrue();
        storage.excluir(m);
        // Crash before concluirExclusao leaves the claim in PostgreSQL.
        vencerPrazo(m.id());
        new MidiaPerfilService(perfis, midias, storage, new ValidarImagem()).limpar();
        verify(storage, times(2)).excluir(m);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM midias_perfil WHERE id=?", Integer.class, m.id())).isZero();
    }

    @Test void bloqueiaCascadeDaContaEPerfilAteConfirmarLimpezaFisica() {
        var m = reservar();
        midias.ativar(m);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM perfis_artesao WHERE id=?", perfil))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM contas WHERE id=?", conta))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(midias.vinculada(m.id())).contains(m);
        midias.remover(perfil);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM contas WHERE id=?", conta))
                .isInstanceOf(DataIntegrityViolationException.class);
        application.limpar();
        verify(storage).excluir(m);
        assertThat(jdbc.update("DELETE FROM contas WHERE id=?", conta)).isEqualTo(1);
    }
}
