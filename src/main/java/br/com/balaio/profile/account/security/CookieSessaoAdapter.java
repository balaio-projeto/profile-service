package br.com.balaio.profile.account.security;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieSessaoAdapter {
    public static final String NOME = "balaio_sessao";
    private final boolean secure;

    public CookieSessaoAdapter(AutenticacaoProperties properties, Environment environment) {
        secure = properties.cookie().secure();
        String[] perfis = environment.getActiveProfiles();
        if (!secure && (!(perfis.length == 1 && "local".equals(perfis[0]))
                || properties.origensPermitidas().stream().anyMatch(origem -> !origem.startsWith("http://")))) {
            throw new IllegalStateException("Configuração do cookie de autenticação inválida.");
        }
    }

    public ResponseCookie criar(String token, boolean https) {
        return cookie(token, https).maxAge(EmissorJwtAdapter.DURACAO_SEGUNDOS).build();
    }

    public ResponseCookie limpar(boolean https) {
        return cookie("", https).maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String token, boolean https) {
        return ResponseCookie.from(NOME, token).httpOnly(true).secure(secure || https)
                .sameSite("Lax").path("/");
    }
}
