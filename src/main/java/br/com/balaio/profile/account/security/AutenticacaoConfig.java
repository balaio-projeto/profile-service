package br.com.balaio.profile.account.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(AutenticacaoProperties.class)
public class AutenticacaoConfig {
    @Bean
    public Clock relogioAutenticacao() {
        return Clock.systemUTC();
    }

    @Bean
    public RSAPrivateKey chavePrivadaJwt(AutenticacaoProperties properties, Environment environment) {
        if ("local-generated".equals(properties.jwt().privateKeyLocation())
                && !environment.matchesProfiles("local"))
            throw new IllegalStateException("Chave JWT local permitida apenas no perfil local.");
        return chavePrivadaJwt(properties);
    }

    public RSAPrivateKey chavePrivadaJwt(AutenticacaoProperties properties) {
        byte[] codificada = null;
        try {
            String local = properties.jwt().privateKeyLocation();
            if ("local-generated".equals(local)) {
                return chaveLocal(Path.of("/run/keys-private/jwt-private.pem"),
                        Path.of("/run/keys-public/jwt-public.pem"));
            }
            Path arquivo;
            if (local.startsWith("file:")) {
                URI uri = new URI(local);
                if (!"file".equals(uri.getScheme()) || uri.getRawAuthority() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException();
                }
                arquivo = Path.of(uri);
            } else {
                // Uma letra seguida de dois pontos é o prefixo de unidade no Windows.
                if (local.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !local.matches("^[A-Za-z]:[\\\\/].*")) {
                    throw new IllegalArgumentException();
                }
                arquivo = Path.of(local);
                if (local.startsWith("\\\\") || local.startsWith("//")) {
                    throw new IllegalArgumentException();
                }
            }
            if (!Files.isRegularFile(arquivo) || Files.size(arquivo) > 16384) {
                throw new IllegalArgumentException();
            }
            String pem = Files.readString(arquivo, StandardCharsets.US_ASCII).strip();
            String inicio = "-----BEGIN PRIVATE KEY-----";
            String fim = "-----END PRIVATE KEY-----";
            if (!pem.startsWith(inicio) || !pem.endsWith(fim)) {
                throw new IllegalArgumentException();
            }
            String base64 = pem.substring(inicio.length(), pem.length() - fim.length()).replaceAll("\\s", "");
            codificada = Base64.getDecoder().decode(base64);
            var chave = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(codificada));
            if (!(chave instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < 3072) {
                throw new IllegalArgumentException();
            }
            return rsa;
        } catch (Exception exception) {
            throw new IllegalStateException("Configuração da chave de autenticação inválida.");
        } finally {
            if (codificada != null) {
                Arrays.fill(codificada, (byte) 0);
            }
        }
    }

    RSAPrivateKey chaveLocal(Path privadaLocal, Path publicaLocal) throws Exception {
        Files.createDirectories(privadaLocal.getParent());
        Files.createDirectories(publicaLocal.getParent());
        if (!Files.exists(privadaLocal)) {
            var gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(3072);
            var par = gerador.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(par.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(privadaLocal, pem, StandardCharsets.US_ASCII);
        }
        // A chave publica é sempre derivada da privada persistida, inclusive após reinício.
        var privada = lerChavePrivada(privadaLocal);
        var rsa = (RSAPrivateCrtKey) privada;
        var publica = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        String pemPublico = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publica.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(publicaLocal, pemPublico, StandardCharsets.US_ASCII);
        return privada;
    }

    private RSAPrivateKey lerChavePrivada(Path arquivo) throws Exception {
        String pem = Files.readString(arquivo, StandardCharsets.US_ASCII).strip();
        String base64 = pem.substring("-----BEGIN PRIVATE KEY-----".length(),
                pem.length() - "-----END PRIVATE KEY-----".length()).replaceAll("\\s", "");
        var chave = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        if (!(chave instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < 3072)
            throw new IllegalArgumentException();
        return rsa;
    }

    @Bean
    public RSAPublicKey chavePublicaJwt(RSAPrivateKey privada) {
        byte[] desafio = new byte[32];
        byte[] assinatura = null;
        try {
            if (!(privada instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < 3072) {
                throw new IllegalArgumentException();
            }
            var publica = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
            new SecureRandom().nextBytes(desafio);
            Signature verificador = Signature.getInstance("SHA256withRSA");
            verificador.initSign(privada);
            verificador.update(desafio);
            assinatura = verificador.sign();
            verificador.initVerify(publica);
            verificador.update(desafio);
            if (!verificador.verify(assinatura)) {
                throw new IllegalArgumentException();
            }
            return publica;
        } catch (Exception exception) {
            throw new IllegalStateException("Configuração da chave de autenticação inválida.");
        } finally {
            Arrays.fill(desafio, (byte) 0);
            if (assinatura != null) {
                Arrays.fill(assinatura, (byte) 0);
            }
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAPublicKey publica, RSAPrivateKey privada) {
        return NimbusJwtEncoder.withKeyPair(publica, privada).algorithm(SignatureAlgorithm.RS256).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publica, AutenticacaoProperties properties, Clock relogio) {
        var decoder = NimbusJwtDecoder.withPublicKey(publica).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new ValidadorClaimsJwt(properties, relogio));
        return decoder;
    }
}
