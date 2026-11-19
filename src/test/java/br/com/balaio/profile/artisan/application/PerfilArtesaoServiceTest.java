package br.com.balaio.profile.artisan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;
import br.com.balaio.profile.artisan.api.PerfilArtesaoResponse;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import jakarta.validation.Validator;

class PerfilArtesaoServiceTest {
    private static final SenhaProtegida HASH = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));

    private final PerfisEmMemoria perfis = new PerfisEmMemoria();
    private final ContasPort contas = mock(ContasPort.class);
    private final PerfilArtesaoService service = new PerfilArtesaoService(perfis, contas, mock(Validator.class));

    @Test
    void perfilCriadoNoCadastroUsaOSlugDerivadoDaConta() {
        var conta = conta(Papel.ARTESAO);

        service.criarParaConta(conta);

        var perfil = perfis.buscarPorContaId(conta.id()).orElseThrow();
        assertThat(perfil.slug()).isEqualTo("artesao-" + conta.id());
        assertThat(perfil.atelie()).isEqualTo("Ateliê de Ana Souza");
    }

    @Test
    void artesaoPromovidoSemPerfilRecebePerfilAoConsultarOProprio() {
        var conta = conta(Papel.ARTESAO);
        var identidade = new IdentidadeAutenticada(conta.id());

        var primeiro = service.consultarMeu(identidade);
        var segundo = service.consultarMeu(identidade);

        assertThat(primeiro.slug()).isEqualTo("artesao-" + conta.id());
        assertThat(segundo.id()).isEqualTo(primeiro.id());
        assertThat(perfis.salvos).hasSize(1);
    }

    @Test
    void contaQueNaoEArtesaRecebeAcessoNegadoSemCriarPerfil() {
        var conta = conta(Papel.COMPRADOR);

        assertThatThrownBy(() -> service.consultarMeu(new IdentidadeAutenticada(conta.id())))
                .isInstanceOfSatisfying(AcessoNegadoException.class, erro ->
                        assertThat(erro.motivo()).isEqualTo(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE));
        assertThat(perfis.salvos).isEmpty();
    }

    @Test
    void respostaExpoeOIdDaContaEOSlugQueOCatalogoValida() {
        var conta = conta(Papel.ARTESAO);
        service.criarParaConta(conta);

        var resposta = PerfilArtesaoResponse.de(perfis.buscarPorContaId(conta.id()).orElseThrow());

        assertThat(resposta.id()).isEqualTo(conta.id());
        assertThat(resposta.slug()).isEqualTo("artesao-" + conta.id());
    }

    @Test
    void outroArtesaoNaoEditaPerfilMesmoConhecendoSlug() {
        var dono = conta(Papel.ARTESAO);
        var outro = conta(Papel.ARTESAO);
        service.criarParaConta(dono);
        var original = perfis.buscarPorContaId(dono.id()).orElseThrow();

        assertThatThrownBy(() -> service.atualizarParcial(new IdentidadeAutenticada(outro.id()),
                original.slug(), java.util.Map.of("historia", "Alteracao indevida")))
                .isInstanceOfSatisfying(AcessoNegadoException.class, erro ->
                        assertThat(erro.motivo()).isEqualTo(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE));
        assertThat(perfis.buscarPorContaId(dono.id()).orElseThrow()).isEqualTo(original);
    }

    private Conta conta(Papel papel) {
        var conta = new Conta(UUID.randomUUID(), "Ana Souza", new Email(UUID.randomUUID() + "@example.test"),
                HASH, papel, false);
        when(contas.buscarPorId(conta.id())).thenReturn(Optional.of(conta));
        return conta;
    }

    private static final class PerfisEmMemoria implements PerfilArtesaoPort {
        private final List<PerfilArtesao> salvos = new ArrayList<>();

        @Override
        public List<PerfilArtesao> listar() {
            return List.copyOf(salvos);
        }

        @Override
        public Optional<PerfilArtesao> buscarPorSlug(String slug) {
            return salvos.stream().filter(perfil -> perfil.slug().equals(slug)).findFirst();
        }

        @Override
        public Optional<PerfilArtesao> buscarPorSlugParaAtualizar(String slug) {
            return buscarPorSlug(slug);
        }

        @Override
        public Optional<PerfilArtesao> buscarPorContaId(UUID contaId) {
            return salvos.stream().filter(perfil -> perfil.contaId().equals(contaId)).findFirst();
        }

        @Override
        public PerfilArtesao salvar(PerfilArtesao perfil) {
            salvos.removeIf(existente -> existente.id().equals(perfil.id()));
            salvos.add(perfil);
            return perfil;
        }
    }
}
