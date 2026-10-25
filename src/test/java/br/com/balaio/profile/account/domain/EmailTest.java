package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EmailTest {
    @Test
    void normalizaExtremidadesECaixaSemRemoverPontosOuAlias() {
        Email email = new Email(" \tNome.Sobrenome+Tag@SUB.EXEMPLO.COM\n");

        assertThat(email.valor()).isEqualTo("nome.sobrenome+tag@sub.exemplo.com");
        assertThat(email).isEqualTo(new Email("nome.sobrenome+tag@sub.exemplo.com"));
        assertThat(email).isNotEqualTo(new Email("nomesobrenome@sub.exemplo.com"));
    }

    @Test
    void aceitaEnderecoComLimiteDe254Caracteres() {
        String endereco = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

        assertThat(endereco).hasSize(254);
        assertThat(new Email(endereco).valor()).isEqualTo(endereco);
    }

    @Test
    void rejeitaEnderecoComMaisDe254Caracteres() {
        String endereco = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);

        assertThat(endereco).hasSize(255);
        assertThatIllegalArgumentException().isThrownBy(() -> new Email(endereco));
    }

    @Test
    void rejeitaAusenciaEspacosArrobasInvalidasEDominiosInvalidos() {
        String[] invalidos = {
                null, "", " \t\n", "sem-arroba", "@exemplo.com", "nome@", "a@@exemplo.com",
                "nome sobrenome@exemplo.com", "nome@exem plo.com", "nome@exemplo.com\noutra",
                "nome@localhost", "nome@.exemplo.com", "nome@exemplo..com", "nome@exemplo.com.",
                "nome@-exemplo.com", "nome@exemplo-.com", "nome@exemplo_com.br",
                ".nome@exemplo.com", "nome.@exemplo.com", "no..me@exemplo.com",
                "nome@" + "a".repeat(64) + ".com"
        };

        for (String endereco : invalidos) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Email(endereco));
        }
    }
}
