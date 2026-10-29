package br.com.balaio.profile.account.security;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("balaio.auth")
public record AutenticacaoProperties(Jwt jwt, @DefaultValue Cookie cookie, String allowedOrigins) {
    public AutenticacaoProperties {
        if (jwt == null || cookie == null) {
            throw configuracaoInvalida();
        }
        validarOrigens(allowedOrigins);
    }

    public record Jwt(String privateKeyLocation, String issuer, String audience) {
        public Jwt {
            if (privateKeyLocation == null || privateKeyLocation.isBlank()
                    || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
                throw configuracaoInvalida();
            }
        }

        @Override
        public String toString() {
            return "Jwt[REDACTED]";
        }
    }

    public record Cookie(@DefaultValue("true") boolean secure) {
    }

    public Set<String> origensPermitidas() {
        return validarOrigens(allowedOrigins);
    }

    private static Set<String> validarOrigens(String valor) {
        if (valor == null || valor.isBlank()) {
            throw configuracaoInvalida();
        }
        Set<String> origens = new HashSet<>();
        for (String item : valor.split(",", -1)) {
            String origem = item.strip();
            try {
                URI uri = new URI(origem);
                String autoridade = uri.getHost();
                if (uri.getPort() != -1) {
                    autoridade += ":" + uri.getPort();
                }
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || uri.getPort() < -1 || uri.getPort() > 65535 || uri.getPort() == 0
                        || !autoridade.equals(uri.getRawAuthority()) || origem.contains("*")) {
                    throw configuracaoInvalida();
                }
                origens.add(origem);
            } catch (Exception exception) {
                throw configuracaoInvalida();
            }
        }
        return Set.copyOf(origens);
    }

    private static IllegalStateException configuracaoInvalida() {
        return new IllegalStateException("Configuração de autenticação inválida.");
    }

    @Override
    public String toString() {
        return "AutenticacaoProperties[REDACTED]";
    }
}
