package br.com.balaio.profile.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.account.security.AutenticacaoProperties;

class OriginAutenticacaoInterceptorTest {
    private final OriginAutenticacaoInterceptor interceptor = new OriginAutenticacaoInterceptor(
            new AutenticacaoProperties(new AutenticacaoProperties.Jwt("fixture-local.pem", AutenticacaoTestFixtures.ISSUER,
                    AutenticacaoTestFixtures.AUDIENCE), new AutenticacaoProperties.Cookie(true), AutenticacaoTestFixtures.ORIGIN));

    @Test
    void cliSemOriginESemCookiePodeContinuar() {
        assertThat(interceptor.preHandle(post(), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void cookieVisitanteNaoRepresentaSessaoAutenticada() {
        var request = post();
        request.setCookies(new Cookie("balaio_cliente", "visitante-fixture"));
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void originPermitidoPodeContinuarComCookieDeSessao() {
        var request = post();
        request.addHeader("Origin", AutenticacaoTestFixtures.ORIGIN);
        request.setCookies(new Cookie("balaio_sessao", "fixture"));
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "null", "https://evil.example.test", "https://frontend.example.test/",
            "https://frontend.example.test, https://evil.example.test" })
    void originPresentePrecisaCorresponderExatamente(String origin) {
        var request = post();
        request.addHeader("Origin", origin);
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(OrigemNaoPermitidaException.class);
    }

    @Test
    void multiplosHeadersOriginSaoRejeitadosMesmoQuandoTodosPermitidos() {
        var request = post();
        request.addHeader("Origin", AutenticacaoTestFixtures.ORIGIN);
        request.addHeader("Origin", AutenticacaoTestFixtures.ORIGIN);
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(OrigemNaoPermitidaException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "fixture" })
    void presencaDoCookieExigeOriginMesmoComBearer(String valor) {
        var request = post();
        request.setCookies(new Cookie("balaio_sessao", valor));
        request.addHeader("Authorization", "Bearer fixture");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(OrigemNaoPermitidaException.class);
    }

    private static MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/api/v1/auth/login");
    }
}
