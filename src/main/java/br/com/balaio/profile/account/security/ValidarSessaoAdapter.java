package br.com.balaio.profile.account.security;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;

@Component
public class ValidarSessaoAdapter {
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)$");
    private final JwtDecoder decoder;

    public ValidarSessaoAdapter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    public IdentidadeAutenticada validarBearer(List<String> autorizacoes) {
        if (autorizacoes == null || autorizacoes.size() != 1) {
            throw naoAutenticado();
        }
        var bearer = BEARER.matcher(autorizacoes.getFirst());
        if (!bearer.matches()) {
            throw naoAutenticado();
        }
        try {
            return new IdentidadeAutenticada(UUID.fromString(decoder.decode(bearer.group(1)).getSubject()));
        } catch (JwtException | IllegalArgumentException exception) {
            throw naoAutenticado();
        }
    }

    private static AcessoNegadoException naoAutenticado() {
        return new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
    }
}
