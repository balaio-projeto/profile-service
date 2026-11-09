package br.com.balaio.profile;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.balaio.profile.account.security.AutenticacaoConfig;
import br.com.balaio.profile.account.security.AutenticacaoProperties;
import tools.jackson.databind.json.JsonMapper;

/** Tokens assinados descartáveis; permite testar o JSON bruto sem coerção de tipos. */
public final class SessaoJwtTestFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrivateKey PRIVADA = new AutenticacaoConfig().chavePrivadaJwt(properties());

    private SessaoJwtTestFixtures() {
    }

    public static AutenticacaoProperties properties() {
        return new AutenticacaoProperties(new AutenticacaoProperties.Jwt(
                AutenticacaoTestFixtures.privateKeyLocation(), AutenticacaoTestFixtures.ISSUER,
                AutenticacaoTestFixtures.AUDIENCE), new AutenticacaoProperties.Cookie(true),
                AutenticacaoTestFixtures.ORIGIN);
    }

    public static Map<String, Object> claims(UUID id, Instant agora) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", id.toString());
        claims.put("papel", "comprador");
        claims.put("iss", AutenticacaoTestFixtures.ISSUER);
        claims.put("aud", List.of(AutenticacaoTestFixtures.AUDIENCE));
        claims.put("iat", agora.getEpochSecond());
        claims.put("exp", agora.plusSeconds(3600).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        return claims;
    }

    public static String token(Map<String, Object> claims) {
        return assinar("{\"alg\":\"RS256\"}", JSON.writeValueAsString(claims));
    }

    public static String assinar(String cabecalho, String claims) {
        return assinar(cabecalho, claims, PRIVADA);
    }

    public static String assinar(String cabecalho, String claims, PrivateKey privada) {
        try {
            var base64 = Base64.getUrlEncoder().withoutPadding();
            String conteudo = base64.encodeToString(cabecalho.getBytes(StandardCharsets.UTF_8)) + "."
                    + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            Signature assinatura = Signature.getInstance("SHA256withRSA");
            assinatura.initSign(privada);
            assinatura.update(conteudo.getBytes(StandardCharsets.US_ASCII));
            return conteudo + "." + base64.encodeToString(assinatura.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível assinar a fixture de sessão.");
        }
    }
}
