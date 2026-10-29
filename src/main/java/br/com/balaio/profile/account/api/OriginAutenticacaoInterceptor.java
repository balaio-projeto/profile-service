package br.com.balaio.profile.account.api;

import java.util.Collections;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import br.com.balaio.profile.account.security.AutenticacaoProperties;
import br.com.balaio.profile.account.security.CookieSessaoAdapter;

@Component
public class OriginAutenticacaoInterceptor implements HandlerInterceptor {
    private final Set<String> origens;

    public OriginAutenticacaoInterceptor(AutenticacaoProperties properties) {
        origens = properties.origensPermitidas();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        var recebidas = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (!recebidas.isEmpty()) {
            if (recebidas.size() != 1 || !origens.contains(recebidas.getFirst())) {
                throw new OrigemNaoPermitidaException();
            }
            return true;
        }
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (CookieSessaoAdapter.NOME.equals(cookie.getName())) {
                    throw new OrigemNaoPermitidaException();
                }
            }
        }
        return true;
    }
}
