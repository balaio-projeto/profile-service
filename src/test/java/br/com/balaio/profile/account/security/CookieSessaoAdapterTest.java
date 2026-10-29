package br.com.balaio.profile.account.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import br.com.balaio.profile.AutenticacaoTestFixtures;

class CookieSessaoAdapterTest {
    @Test
    void seguroPorPadraoMesmoQuandoRequisicaoEhHttp() {
        var adapter = new CookieSessaoAdapter(AutenticacaoConfigTest.propriedades("fixture-local.pem", true), new MockEnvironment());
        AutenticacaoTestFixtures.assertCookie(adapter.criar("fixture-token", false).toString(), true, 3600);
        AutenticacaoTestFixtures.assertCookie(adapter.limpar(false).toString(), true, 0);
    }

    @Test
    void somenteLocalPermiteHttpSemSecureMasHttpsContinuaSecure() {
        var ambiente = new MockEnvironment();
        ambiente.setActiveProfiles("local");
        var adapter = new CookieSessaoAdapter(propriedadesLocaisHttp(), ambiente);
        AutenticacaoTestFixtures.assertCookie(adapter.criar("fixture-token", false).toString(), false, 3600);
        AutenticacaoTestFixtures.assertCookie(adapter.limpar(false).toString(), false, 0);
        AutenticacaoTestFixtures.assertCookie(adapter.criar("fixture-token", true).toString(), true, 3600);
        AutenticacaoTestFixtures.assertCookie(adapter.limpar(true).toString(), true, 0);
    }

    @ParameterizedTest
    @MethodSource("perfisInseguros")
    void secureFalseForaDeLocalExclusivoFalhaNaInicializacao(String[] perfis) {
        var ambiente = new MockEnvironment();
        ambiente.setActiveProfiles(perfis);
        assertThatThrownBy(() -> new CookieSessaoAdapter(propriedadesLocaisHttp(), ambiente))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void localComOrigemHttpsNaoPermiteDesabilitarSecure() {
        var ambiente = new MockEnvironment();
        ambiente.setActiveProfiles("local");
        assertThatThrownBy(() -> new CookieSessaoAdapter(AutenticacaoConfigTest.propriedades("fixture-local.pem", false), ambiente))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    private static AutenticacaoProperties propriedadesLocaisHttp() {
        return new AutenticacaoProperties(new AutenticacaoProperties.Jwt("fixture-local.pem", AutenticacaoTestFixtures.ISSUER,
                AutenticacaoTestFixtures.AUDIENCE), new AutenticacaoProperties.Cookie(false), "http://localhost:4321");
    }

    private static Stream<Arguments> perfisInseguros() {
        return Stream.of(new String[0], new String[] { "prod" }, new String[] { "dev" }, new String[] { "LOCAL" },
                new String[] { "local", "prod" }, new String[] { "prod", "local" })
                .map(perfis -> Arguments.of((Object) perfis));
    }
}
