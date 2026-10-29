package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SenhaProtegidaTest {
    private static final String CORPO_SINTETICO = "A".repeat(53);

    @Test
    void aceitaVariantesECustosDoFormatoContratadoSemGerarHash() {
        for (String variante : new String[] {"2a", "2b", "2y"}) {
            for (String custo : new String[] {"04", "12", "31"}) {
                String valor = "{bcrypt}$" + variante + "$" + custo + "$" + CORPO_SINTETICO;
                assertThat(new SenhaProtegida(valor).valor()).isEqualTo(valor);
            }
        }
    }

    @Test
    void rejeitaSenhaCruaOuHashForaDoContrato() {
        String[] invalidos = {
                null, "", "senha-inicial", "$2b$12$" + CORPO_SINTETICO,
                "{noop}senha-inicial", "{bcrypt}$2x$12$" + CORPO_SINTETICO,
                "{bcrypt}$2b$03$" + CORPO_SINTETICO, "{bcrypt}$2b$32$" + CORPO_SINTETICO,
                "{bcrypt}$2b$4$" + CORPO_SINTETICO, "{bcrypt}$2b$12$" + "A".repeat(52),
                "{bcrypt}$2b$12$" + "A".repeat(54), "{bcrypt}$2b$12$" + "A".repeat(52) + "+",
                " {bcrypt}$2b$12$" + CORPO_SINTETICO, "{bcrypt}$2b$12$" + CORPO_SINTETICO + "\n"
        };

        for (String valor : invalidos) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SenhaProtegida(valor));
        }
    }

    @Test
    void redigeHashEmRepresentacaoTextual() {
        SenhaProtegida senha = new SenhaProtegida("{bcrypt}$2b$12$" + CORPO_SINTETICO);

        assertThat(senha.toString()).contains("REDACTED").doesNotContain(senha.valor(), CORPO_SINTETICO);
    }
}
