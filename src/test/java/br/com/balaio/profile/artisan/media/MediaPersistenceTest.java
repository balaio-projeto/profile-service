package br.com.balaio.profile.artisan.media;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties="balaio.media.cleanup-delay=3600000")
@Import(PostgreSQLTestConfiguration.class)
class MediaPersistenceTest {
    @MockitoBean(enforceOverride=true) InicializarAdministradorUseCase admin;
    @Autowired MidiasPerfilPort midias;
    @Autowired JdbcTemplate jdbc;
    @Autowired PerfilArtesaoService perfis;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean(enforceOverride=true) ArmazenamentoMidiaPort storage;
    @Autowired MidiaPerfilService application;
    @Test void preservaLegadoSubstituiAtomicamenteEProtegePatch() throws Exception {
        UUID conta=UUID.randomUUID(), perfil=UUID.randomUUID();
        String slug="teste-"+perfil;
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",conta,"Teste",
                conta+"@test.invalid","{bcrypt}$2b$12$"+".".repeat(53),"artesao");
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie) VALUES (?,?,?,?,?)",perfil,conta,slug,"Teste","Atelie");
        try {
            assertThat(perfis.consultar(slug).imagem()).isEqualTo(MidiaPerfil.PLACEHOLDER);
            var first=media(perfil); midias.reservar(first); midias.ativar(first);
            assertThat(midias.vinculada(first.id())).contains(first);
            var other=media(UUID.randomUUID());
            assertThatThrownBy(() -> midias.reservar(other)).isInstanceOf(RuntimeException.class);
            assertThat(perfis.consultar(slug).imagem()).isEqualTo(first.url());
            assertThatThrownBy(() -> midias.ativar(media(perfil))).isInstanceOf(MidiaException.class);
            assertThat(perfis.consultar(slug).imagem()).isEqualTo(first.url());
            var second=media(perfil); midias.reservar(second); midias.ativar(second);
            assertThat(midias.vinculada(first.id())).isEmpty();
            assertThat(midias.candidatasLimpeza()).contains(first);
            var identidade=new IdentidadeAutenticada(conta);
            perfis.atualizarParcial(identidade,slug,Map.of("historia","Nova historia"));
            assertThat(perfis.consultar(slug).imagem()).isEqualTo(second.url());
            assertThatThrownBy(() -> perfis.atualizarParcial(identidade,slug,Map.of("imagem",first.url())))
                    .isInstanceOf(RuntimeException.class);
            jdbc.update("UPDATE perfis_artesao SET slug=? WHERE id=?","novo-"+perfil,perfil);
            assertThat(midias.vinculada(second.id())).contains(second);
            assertThat(midias.prepararExclusao(second)).isFalse();
            midias.remover(perfil);
            assertThat(midias.vinculada(second.id())).isEmpty();
            assertThat(midias.prepararExclusao(second)).isTrue();
            midias.concluirExclusao(second.id());
            assertThat(perfis.consultar("novo-"+perfil).imagem()).isEqualTo(MidiaPerfil.PLACEHOLDER);
            // A PATCH transaction that already cached the profile must refresh after
            // a concurrent upload commits, rather than restoring the stale image.
            var terceira=media(perfil);
            midias.reservar(terceira);
            var carregado=new java.util.concurrent.CountDownLatch(1);
            var liberar=new java.util.concurrent.CountDownLatch(1);
            try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var patch=executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .executeWithoutResult(status -> {
                            perfis.consultar("novo-"+perfil);
                            carregado.countDown();
                            try {
                                if (!liberar.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                            perfis.atualizarParcial(identidade,"novo-"+perfil,Map.of("historia","Concorrente"));
                        }));
                try {
                    assertThat(carregado.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    midias.ativar(terceira);
                } finally { liberar.countDown(); }
                patch.get(10,java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) { throw new AssertionError(e); }
            assertThat(perfis.consultar("novo-"+perfil).imagem()).isEqualTo(terceira.url());
            org.mockito.Mockito.doAnswer(call -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()).isFalse();
                return null;
            }).when(storage).enviar(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            var quarta=application.armazenar(identidade,MidiaPerfilServiceTest.png());
            assertThat(midias.vinculada(quarta.id())).contains(quarta);
            application.remover(identidade);
            assertThat(perfis.consultar("novo-"+perfil).imagem()).isEqualTo(MidiaPerfil.PLACEHOLDER);
        } finally {
            // Test-only mocked storage has no physical objects. Production must
            // finish cleanup before deleting the owning profile/account.
            jdbc.update("DELETE FROM midias_perfil WHERE perfil_id=?",perfil);
            jdbc.update("DELETE FROM contas WHERE id=?",conta);
        }
    }
    private MidiaPerfil media(UUID perfil) {
        var id=UUID.randomUUID();
        return new MidiaPerfil(id,perfil,"profile/"+perfil+"/"+id,"image/png",1,"a".repeat(64));
    }
}
