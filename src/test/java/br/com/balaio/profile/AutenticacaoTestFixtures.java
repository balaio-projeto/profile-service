package br.com.balaio.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Material descartavel gerado em execucao; nenhuma chave privada e versionada. */
public final class AutenticacaoTestFixtures {
    public static final String ISSUER = "https://profile.example.test";
    public static final String AUDIENCE = "balaio-api-test";
    public static final String ORIGIN = "https://frontend.example.test";
    private static final KeyPair CHAVES = gerarRsa(3072);
    private static final Path PRIVADA = escreverPrivadaTemporaria();

    private AutenticacaoTestFixtures() {
    }

    public static String privateKeyLocation() {
        return PRIVADA.toUri().toString();
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) CHAVES.getPublic();
    }

    public static KeyPair gerarRsa(int bits) {
        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(bits);
            return gerador.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Nao foi possivel gerar a fixture RSA.");
        }
    }

    public static String pem(String tipo, byte[] conteudo) {
        return "-----BEGIN " + tipo + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(conteudo)
                + "\n-----END " + tipo + "-----\n";
    }

    public static String assertCookie(String cookie, boolean secure, int maxAge) {
        String[] partes = cookie.split(";\\s*");
        assertThat(partes[0]).startsWith("balaio_sessao=");
        Map<String, String> atributos = Arrays.stream(partes).skip(1).map(parte -> parte.split("=", 2))
                .collect(Collectors.toMap(parte -> parte[0].toLowerCase(Locale.ROOT),
                        parte -> parte.length == 2 ? parte[1] : ""));
        assertThat(atributos).containsEntry("path", "/").containsEntry("max-age", Integer.toString(maxAge))
                .containsKey("httponly");
        assertThat(atributos.get("samesite")).isEqualToIgnoringCase("Lax");
        assertThat(atributos.containsKey("secure")).isEqualTo(secure);
        assertThat(atributos).doesNotContainKey("domain");
        String token = partes[0].substring("balaio_sessao=".length());
        if (maxAge == 0) {
            assertThat(token).isEmpty();
        } else {
            assertThat(token).isNotBlank();
        }
        return token;
    }

    public static JWTClaimsSet assertJwt(String token, UUID id, String papel, Instant antes, Instant depois) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier(publicKey()))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims()).containsOnlyKeys("sub", "papel", "iss", "aud", "iat", "exp", "jti");
        assertThat(UUID.fromString(claims.getSubject())).isEqualTo(id);
        assertThat(claims.getStringClaim("papel")).isEqualTo(papel);
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).containsExactly(AUDIENCE);
        assertThat(claims.getIssueTime().toInstant()).isBetween(antes.minusSeconds(1), depois.plusSeconds(1));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(claims.getIssueTime().toInstant().plusSeconds(3600));
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(jwt.getHeader().toJSONObject()).doesNotContainKey("jwk");
        return claims;
    }

    private static Path escreverPrivadaTemporaria() {
        try {
            Path diretorio = Files.createTempDirectory("balaio-auth-test-");
            diretorio.toFile().deleteOnExit();
            Path arquivo = diretorio.resolve("rsa-runtime.pem");
            Files.writeString(arquivo, pem("PRIVATE KEY", CHAVES.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
            arquivo.toFile().deleteOnExit();
            return arquivo;
        } catch (Exception exception) {
            throw new IllegalStateException("Nao foi possivel preparar a chave descartavel de teste.");
        }
    }
}
