package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContaTest {
    private static final UUID ID = UUID.fromString("e4cdd7f8-1586-4408-983b-05d56a1c9a00");
    private static final Email EMAIL = new Email("conta@exemplo.com");
    private static final SenhaProtegida SENHA = new SenhaProtegida("{bcrypt}$2b$12$" + "A".repeat(53));

    @Test
    void preservaIdentidadePapelETrocaObrigatoriaNormalizandoSomenteExtremidadesDoNome() {
        Conta conta = new Conta(ID, " \tNome  Sobrenome\n", EMAIL, SENHA, Papel.ADMIN, true);

        assertThat(conta.id()).isEqualTo(ID);
        assertThat(conta.nome()).isEqualTo("Nome  Sobrenome");
        assertThat(conta.email()).isEqualTo(EMAIL);
        assertThat(conta.senhaProtegida()).isEqualTo(SENHA);
        assertThat(conta.papel()).isEqualTo(Papel.ADMIN);
        assertThat(conta.trocaSenhaObrigatoria()).isTrue();
        assertThat(conta.toString()).doesNotContain(SENHA.valor());
    }

    @Test
    void aceitaNomeCom240Caracteres() {
        Conta conta = new Conta(ID, "a".repeat(240), EMAIL, SENHA, Papel.COMPRADOR, false);

        assertThat(conta.nome()).hasSize(240);
        assertThat(conta.trocaSenhaObrigatoria()).isFalse();
    }

    @Test
    void rejeitaCamposNulos() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta(null, "Nome", EMAIL, SENHA, Papel.COMPRADOR, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta(ID, null, EMAIL, SENHA, Papel.COMPRADOR, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta(ID, "Nome", null, SENHA, Papel.COMPRADOR, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta(ID, "Nome", EMAIL, null, Papel.COMPRADOR, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta(ID, "Nome", EMAIL, SENHA, null, false));
    }

    @Test
    void rejeitaNomeVazioOuAcimaDoLimite() {
        for (String nome : new String[] {"", " \t\n", "a".repeat(241)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Conta(ID, nome, EMAIL, SENHA, Papel.COMPRADOR, false));
        }
    }
}
