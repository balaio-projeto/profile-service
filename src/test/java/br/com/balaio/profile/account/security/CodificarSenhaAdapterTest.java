package br.com.balaio.profile.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import br.com.balaio.profile.account.domain.SenhaProtegida;

class CodificarSenhaAdapterTest {
    @Test
    void configuracaoRealGeraBcryptComPrefixoCusto12ESalAleatorio() {
        PasswordEncoder encoder = new SenhasConfig().passwordEncoder();
        var adapter = new CodificarSenhaAdapter(encoder);
        String senha = "Senha de fixture ${LITERAL} 2026";

        SenhaProtegida primeira = adapter.codificar(senha);
        SenhaProtegida segunda = adapter.codificar(senha);

        assertThat(primeira.valor()).matches("\\{bcrypt\\}\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");
        assertThat(segunda.valor()).matches("\\{bcrypt\\}\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");
        assertThat(primeira.valor()).isNotEqualTo(segunda.valor());
        assertThat(encoder.matches(senha, primeira.valor())).isTrue();
        assertThat(encoder.matches(senha, segunda.valor())).isTrue();
        assertThat(encoder.matches("Outra senha de fixture", primeira.valor())).isFalse();
    }

    @Test
    void entregaSenhaOriginalAoEncoderSemTrimOuInterpolacao() {
        AtomicReference<CharSequence> recebida = new AtomicReference<>();
        String hashSintetico = "{bcrypt}$2b$12$" + "A".repeat(53);
        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                recebida.set(rawPassword);
                return hashSintetico;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                throw new UnsupportedOperationException();
            }
        };
        String senha = "  senha ${SEGREDO} de fixture  ";

        SenhaProtegida protegida = new CodificarSenhaAdapter(encoder).codificar(senha);

        assertThat(recebida.get()).isEqualTo(senha);
        assertThat(protegida.valor()).isEqualTo(hashSintetico);
    }

    @Test
    void falhaDoEncoderNaoExpoeSenhaNemExcecaoOriginal() {
        String senha = "Segredo-de-fixture-na-excecao";
        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalArgumentException("Falha contendo " + rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                throw new UnsupportedOperationException();
            }
        };

        Throwable erro = catchThrowable(() -> new CodificarSenhaAdapter(encoder).codificar(senha));

        assertThat(erro).isInstanceOf(IllegalStateException.class)
                .hasMessage("Não foi possível proteger a senha.")
                .hasNoCause();
        assertThat(erro.getSuppressed()).isEmpty();
        StringWriter representacao = new StringWriter();
        erro.printStackTrace(new PrintWriter(representacao));
        assertThat(representacao.toString()).doesNotContain(senha, "Falha contendo");
    }
}
