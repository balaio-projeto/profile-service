package br.com.balaio.profile.account.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
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
    public RSAPrivateKey chavePrivadaJwt(AutenticacaoProperties properties) {
        byte[] codificada = null;
        try {
            String local = properties.jwt().privateKeyLocation();
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
