package br.com.balaio.profile.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import br.com.balaio.profile.AutenticacaoTestFixtures;

class AutenticacaoConfigTest {
    @TempDir
    Path temporario;

    @Test
    void carregaArquivoPkcs8Rsa3072EDerivaPublicaSemOutroArquivo() {
        runner(AutenticacaoTestFixtures.privateKeyLocation()).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            RSAPrivateKey privada = contexto.getBean(RSAPrivateKey.class);
            RSAPublicKey publica = contexto.getBean(RSAPublicKey.class);
            assertThat(privada).isInstanceOf(RSAPrivateCrtKey.class);
            assertThat(privada.getModulus().bitLength()).isGreaterThanOrEqualTo(3072);
            assertThat(publica.getModulus()).isEqualTo(privada.getModulus());
            assertThat(publica.getEncoded()).isEqualTo(AutenticacaoTestFixtures.publicKey().getEncoded());
        });
    }

    @Test
    void aceitaCaminhoNormalDoArquivoLocal() {
        String caminho = Path.of(java.net.URI.create(AutenticacaoTestFixtures.privateKeyLocation())).toString();
        runner(caminho).run(contexto -> assertThat(contexto).hasNotFailed());
    }

    @Test
    void chaveAusenteFalhaNaInicializacaoSemFixtureGlobal() {
        runner(temporario.resolve("nao-existe.pem").toString()).run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void rejeitaPublicaPkcs1EcRsaFracaEConteudoInvalido() throws Exception {
        var rsa = AutenticacaoTestFixtures.gerarRsa(3072);
        var fraca = AutenticacaoTestFixtures.gerarRsa(1024);
        var ecGerador = KeyPairGenerator.getInstance("EC");
        ecGerador.initialize(256);
        var ec = ecGerador.generateKeyPair();
        List<String> invalidas = List.of(
                AutenticacaoTestFixtures.pem("PUBLIC KEY", rsa.getPublic().getEncoded()),
                AutenticacaoTestFixtures.pem("RSA PRIVATE KEY", pkcs1((RSAPrivateCrtKey) rsa.getPrivate())),
                AutenticacaoTestFixtures.pem("PRIVATE KEY", ec.getPrivate().getEncoded()),
                AutenticacaoTestFixtures.pem("PRIVATE KEY", fraca.getPrivate().getEncoded()),
                "-----BEGIN PRIVATE KEY-----\n%%%%INVALIDO%%%%\n-----END PRIVATE KEY-----\n",
                "fixture-nao-e-chave-privada");
        for (int indice = 0; indice < invalidas.size(); indice++) {
            Path arquivo = temporario.resolve("invalida-" + indice + ".pem");
            Files.writeString(arquivo, invalidas.get(indice));
            runner(arquivo.toUri().toString()).run(contexto -> assertThat(contexto).hasFailed());
        }
    }

    @Test
    void rsaCrtParseavelMasInconsistenteFalhaNoStartupSemMaterialNoStackTrace() throws Exception {
        var original = (RSAPrivateCrtKey) AutenticacaoTestFixtures.gerarRsa(3072).getPrivate();
        var spec = new RSAPrivateCrtKeySpec(original.getModulus(), original.getPublicExponent(), original.getPrivateExponent(),
                original.getPrimeP(), original.getPrimeQ(), original.getPrimeExponentP().add(BigInteger.ONE),
                original.getPrimeExponentQ(), original.getCrtCoefficient());
        byte[] codificada = KeyFactory.getInstance("RSA").generatePrivate(spec).getEncoded();
        String material = AutenticacaoTestFixtures.pem("PRIVATE KEY", codificada);
        Path arquivo = temporario.resolve("crt-inconsistente.pem");
        Files.writeString(arquivo, material);

        runner(arquivo.toString()).run(contexto -> {
            assertThat(contexto).hasFailed();
            var texto = new StringWriter();
            contexto.getStartupFailure().printStackTrace(new PrintWriter(texto));
            boolean expoeMaterial = material.lines().filter(linha -> !linha.startsWith("-----") && linha.length() > 16)
                    .anyMatch(texto.toString()::contains);
            assertThat(expoeMaterial).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://keys.example.test/private.pem", "http://localhost/private.pem",
            "classpath:private.pem", "file://server/share/private.pem", "\\\\server\\share\\private.pem" })
    void rejeitaLocalizacaoRemotaOuClasspathSemCarregarRecurso(String local) {
        runner(local).run(contexto -> assertThat(contexto).hasFailed());
    }

    @ParameterizedTest
    @MethodSource("propriedadesObrigatorias")
    void configuracaoVaziaFalhaAntesDeOferecerServico(String propriedade) {
        runner(AutenticacaoTestFixtures.privateKeyLocation()).withPropertyValues(propriedade + "=")
                .run(contexto -> assertThat(contexto).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = { "*", "https://*.example.test", "https://frontend.example.test/",
            "https://frontend.example.test/path", "https://user@frontend.example.test",
            "https://frontend.example.test?x=1", "null", "https://frontend.example.test," })
    void allowlistInvalidaFalhaNaInicializacao(String origem) {
        runner(AutenticacaoTestFixtures.privateKeyLocation()).withPropertyValues("balaio.auth.allowed-origins=" + origem)
                .run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void propriedadesEErroDeLeituraNaoExibemMaterialOuCausa() throws Exception {
        String conteudo = "fixture-secreta-que-nao-pode-aparecer";
        Path arquivo = temporario.resolve("falha.pem");
        Files.writeString(arquivo, conteudo);
        var propriedades = propriedades(arquivo.toString(), true);
        assertThat(propriedades.toString()).doesNotContain(arquivo.toString(), conteudo);
        assertThat(propriedades.jwt().toString()).doesNotContain(arquivo.toString());
        assertThatThrownBy(() -> new AutenticacaoConfig().chavePrivadaJwt(propriedades))
                .isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessage("Configuração da chave de autenticação inválida.");
    }

    static AutenticacaoProperties propriedades(String local, boolean secure) {
        return new AutenticacaoProperties(new AutenticacaoProperties.Jwt(local, AutenticacaoTestFixtures.ISSUER,
                AutenticacaoTestFixtures.AUDIENCE), new AutenticacaoProperties.Cookie(secure), AutenticacaoTestFixtures.ORIGIN);
    }

    private ApplicationContextRunner runner(String local) {
        return new ApplicationContextRunner().withUserConfiguration(AutenticacaoConfig.class)
                .withPropertyValues("balaio.auth.jwt.private-key-location=" + local,
                        "balaio.auth.jwt.issuer=" + AutenticacaoTestFixtures.ISSUER,
                        "balaio.auth.jwt.audience=" + AutenticacaoTestFixtures.AUDIENCE,
                        "balaio.auth.cookie.secure=true", "balaio.auth.allowed-origins=" + AutenticacaoTestFixtures.ORIGIN);
    }

    private static Stream<String> propriedadesObrigatorias() {
        return Stream.of("balaio.auth.jwt.private-key-location", "balaio.auth.jwt.issuer", "balaio.auth.jwt.audience",
                "balaio.auth.allowed-origins");
    }

    private static byte[] pkcs1(RSAPrivateCrtKey chave) {
        List<BigInteger> valores = List.of(BigInteger.ZERO, chave.getModulus(), chave.getPublicExponent(),
                chave.getPrivateExponent(), chave.getPrimeP(), chave.getPrimeQ(), chave.getPrimeExponentP(),
                chave.getPrimeExponentQ(), chave.getCrtCoefficient());
        var corpo = new ByteArrayOutputStream();
        valores.forEach(valor -> corpo.writeBytes(der(2, valor.toByteArray())));
        return der(48, corpo.toByteArray());
    }

    private static byte[] der(int tag, byte[] conteudo) {
        var saida = new ByteArrayOutputStream();
        saida.write(tag);
        if (conteudo.length < 128) {
            saida.write(conteudo.length);
        } else if (conteudo.length < 256) {
            saida.write(129);
            saida.write(conteudo.length);
        } else {
            saida.write(130);
            saida.write(conteudo.length >> 8);
            saida.write(conteudo.length);
        }
        saida.writeBytes(conteudo);
        return saida.toByteArray();
    }
}
