package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

class PoliticaSenhaTest {
    @Test
    void aceitaLimitesDe12CaracteresE72BytesAscii() {
        assertThatCode(() -> PoliticaSenha.validar("a".repeat(12))).doesNotThrowAnyException();
        assertThatCode(() -> PoliticaSenha.validar("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void contaCodepointsEmVezDeUnidadesUtf16() {
        String emoji = "\uD83D\uDD12";

        assertThatCode(() -> PoliticaSenha.validar(emoji.repeat(12))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> PoliticaSenha.validar(emoji.repeat(11)));
    }

    @Test
    void aplicaLimiteDeBytesUtf8ParaCaracteresMultibyte() {
        assertThatCode(() -> PoliticaSenha.validar("é".repeat(36))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> PoliticaSenha.validar("é".repeat(37)));
        assertThatIllegalArgumentException().isThrownBy(() -> PoliticaSenha.validar("a".repeat(71) + "é"));
    }

    @Test
    void aceitaEspacosLiteraisSemTrimOuInterpretacaoDePlaceholders() {
        String senha = " ${SEGREDO} ";

        assertThat(senha.codePointCount(0, senha.length())).isEqualTo(12);
        assertThat(senha.strip().codePointCount(0, senha.strip().length())).isEqualTo(10);
        assertThatCode(() -> PoliticaSenha.validar(senha)).doesNotThrowAnyException();
    }

    @Test
    void rejeitaAusenciaVazioBrancosComprimentoInvalidoEControles() {
        String[] invalidas = {
                null, "", " \t\n", " ".repeat(12), "a".repeat(11), "a".repeat(73),
                "a".repeat(12) + "\n", "a".repeat(12) + "\t",
                "a".repeat(12) + '\0', "a".repeat(12) + (char) 127, "a".repeat(12) + (char) 0x85
        };

        for (String senha : invalidas) {
            assertThatIllegalArgumentException().isThrownBy(() -> PoliticaSenha.validar(senha))
                    .withMessage("Senha inválida.").withNoCause();
        }
    }

    @Test
    void rejeitaUtf16MalformadoSemSubstituirCaracteresNaCodificacao() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PoliticaSenha.validar("a".repeat(12) + "\uD800"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PoliticaSenha.validar("a".repeat(12) + "\uDC00"));
    }

    @Test
    void falhaNaoIncluiSenhaOuCausaNaMensagemOuStackTrace() {
        String segredo = "Segredo-literal-que-nao-deve-aparecer\n";

        Throwable falha = catchThrowable(() -> PoliticaSenha.validar(segredo));

        assertThat(falha).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Senha inválida.").hasNoCause();
        StringWriter representacao = new StringWriter();
        falha.printStackTrace(new PrintWriter(representacao));
        assertThat(representacao.toString()).doesNotContain(segredo, segredo.strip());
    }
}
