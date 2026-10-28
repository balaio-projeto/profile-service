package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContaPapelTest {
    private static final SenhaProtegida HASH = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void promoveParaAdminPreservandoOsDemaisDados(Papel papelAnterior) {
        Conta original = conta(papelAnterior, true);

        Conta promovida = original.alterarPapel(Papel.ADMIN, 1);

        assertThat(promovida.papel()).isEqualTo(Papel.ADMIN);
        conferirDemaisDados(promovida, original);
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void ultimoAdministradorNaoPodeSerDespromovido(Papel novoPapel) {
        Conta original = conta(Papel.ADMIN, false);

        assertThatThrownBy(() -> original.alterarPapel(novoPapel, 1))
                .isInstanceOf(UltimoAdministradorException.class).hasNoCause();
        assertThat(original.papel()).isEqualTo(Papel.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(value = Papel.class, names = { "COMPRADOR", "ARTESAO" })
    void podeDespromoverUmDeDoisAdministradoresPreservandoCredenciais(Papel novoPapel) {
        Conta original = conta(Papel.ADMIN, true);

        Conta alterada = original.alterarPapel(novoPapel, 2);

        assertThat(alterada.papel()).isEqualTo(novoPapel);
        conferirDemaisDados(alterada, original);
    }

    @ParameterizedTest
    @EnumSource(Papel.class)
    void papelInalteradoPreservaAMesmaConta(Papel papel) {
        Conta original = conta(papel, false);

        assertThat(original.alterarPapel(papel, 1)).isSameAs(original);
    }

    @Test
    void permiteAlteracaoEntrePapeisNaoAdministrativos() {
        Conta original = conta(Papel.COMPRADOR, false);

        Conta alterada = original.alterarPapel(Papel.ARTESAO, 1);

        assertThat(alterada.papel()).isEqualTo(Papel.ARTESAO);
        conferirDemaisDados(alterada, original);
    }

    @Test
    void rejeitaPapelNulo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta(Papel.ADMIN, false).alterarPapel(null, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, Long.MIN_VALUE })
    void rejeitaContagemNegativaMesmoQuandoOPapelNaoMuda(long total) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta(Papel.ADMIN, false).alterarPapel(Papel.ADMIN, total));
    }

    @Test
    void contagemInconsistenteZeroNaoPermiteEliminarUmAdminConhecido() {
        assertThatThrownBy(() -> conta(Papel.ADMIN, false).alterarPapel(Papel.COMPRADOR, 0))
                .isInstanceOf(UltimoAdministradorException.class);
    }

    private Conta conta(Papel papel, boolean trocaObrigatoria) {
        return new Conta(UUID.randomUUID(), "Conta de teste", new Email("conta@example.test"), HASH, papel, trocaObrigatoria);
    }

    private void conferirDemaisDados(Conta alterada, Conta original) {
        assertThat(alterada.id()).isEqualTo(original.id());
        assertThat(alterada.nome()).isEqualTo(original.nome());
        assertThat(alterada.email()).isEqualTo(original.email());
        assertThat(alterada.senhaProtegida()).isEqualTo(original.senhaProtegida());
        assertThat(alterada.trocaSenhaObrigatoria()).isEqualTo(original.trocaSenhaObrigatoria());
    }
}
