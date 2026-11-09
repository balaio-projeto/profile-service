package br.com.balaio.profile.account.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import br.com.balaio.profile.account.domain.Papel;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Confere o JSON assinado antes que coerções de claims relaxem o contrato. */
final class ValidadorClaimsJwt implements OAuth2TokenValidator<Jwt> {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final OAuth2TokenValidatorResult INVALIDO = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Sessão inválida.", null));
    private final AutenticacaoProperties properties;
    private final Clock relogio;

    ValidadorClaimsJwt(AutenticacaoProperties properties, Clock relogio) {
        this.properties = properties;
        this.relogio = relogio;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String[] partes = jwt.getTokenValue().split("\\.", -1);
            if (partes.length != 3 || partes[2].isEmpty()) {
                return INVALIDO;
            }
            JsonNode cabecalho = JSON.readTree(Base64.getUrlDecoder().decode(partes[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(partes[1]));
            if (!cabecalho.isObject() || !claims.isObject() || !"RS256".equals(texto(cabecalho, "alg"))
                    || cabecalho.has("jwk") || cabecalho.has("jku") || cabecalho.has("x5u") || cabecalho.has("x5c")
                    || !uuidCanonico(texto(claims, "sub"))
                    || !properties.jwt().issuer().equals(texto(claims, "iss"))
                    || !audienciaValida(claims.get("aud")) || texto(claims, "jti").isBlank()) {
                return INVALIDO;
            }
            Papel.fromCodigo(texto(claims, "papel"));
            Instant emitidoEm = dataNumerica(claims.get("iat"));
            Instant expiraEm = dataNumerica(claims.get("exp"));
            Instant agora = relogio.instant();
            if (emitidoEm.isAfter(agora.plusSeconds(30)) || !expiraEm.isAfter(agora)
                    || !expiraEm.isAfter(emitidoEm)
                    || expiraEm.isAfter(emitidoEm.plusSeconds(EmissorJwtAdapter.DURACAO_SEGUNDOS))
                    || (claims.has("nbf") && dataNumerica(claims.get("nbf")).isAfter(agora))) {
                return INVALIDO;
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return INVALIDO;
        }
    }

    private boolean audienciaValida(JsonNode audiencia) {
        if (audiencia == null) {
            return false;
        }
        if (audiencia.isString()) {
            return properties.jwt().audience().equals(audiencia.stringValue());
        }
        if (!audiencia.isArray() || audiencia.isEmpty()) {
            return false;
        }
        boolean corresponde = false;
        for (JsonNode valor : audiencia) {
            if (!valor.isString()) {
                return false;
            }
            corresponde |= properties.jwt().audience().equals(valor.stringValue());
        }
        return corresponde;
    }

    private static Instant dataNumerica(JsonNode valor) {
        if (valor == null || !valor.isIntegralNumber() || !valor.canConvertToLong() || valor.longValue() < 0) {
            throw new IllegalArgumentException();
        }
        return Instant.ofEpochSecond(valor.longValue());
    }

    private static String texto(JsonNode objeto, String chave) {
        JsonNode valor = objeto.get(chave);
        return valor != null && valor.isString() ? valor.stringValue() : "";
    }

    private static boolean uuidCanonico(String valor) {
        try {
            return UUID.fromString(valor).toString().equals(valor);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
