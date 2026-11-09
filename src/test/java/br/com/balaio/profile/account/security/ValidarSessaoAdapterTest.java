package br.com.balaio.profile.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.domain.Papel;
import tools.jackson.databind.json.JsonMapper;

class ValidarSessaoAdapterTest {
    private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");
    private static final UUID ID = UUID.fromString("390cb2de-c118-4535-a2e7-79dacb143abc");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ValidarSessaoAdapter sessoes = new ValidarSessaoAdapter(new AutenticacaoConfig()
            .jwtDecoder(AutenticacaoTestFixtures.publicKey(), SessaoJwtTestFixtures.properties(), RELOGIO));

    @ParameterizedTest
    @ValueSource(strings = { "comprador", "artesao", "admin" })
    void validaTokenRealDoEmissorComOsSeteClaims(String papel) {
        var config = new AutenticacaoConfig();
        var propriedades = SessaoJwtTestFixtures.properties();
        var encoder = config.jwtEncoder(AutenticacaoTestFixtures.publicKey(), config.chavePrivadaJwt(propriedades));
        var emissor = new EmissorJwtAdapter(encoder, RELOGIO, propriedades);
        String token = emissor.emitir(new ContaPublicaCadastrada(ID, "Pessoa", "fixture@example.test", Papel.fromCodigo(papel)));

        assertThat(sessoes.validarBearer(List.of("Bearer " + token)).contaId()).isEqualTo(ID);
    }

    @ParameterizedTest
    @MethodSource("claimsInvalidos")
    void rejeitaClaimsAusentesTipoInvalidoEValoresForaDoContrato(String descricao, Map<String, Object> claims) {
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.token(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = { "jku", "jwk", "x5u", "x5c" })
    void naoAceitaMaterialOuLocalizacaoDeChaveNoCabecalho(String campo) {
        String cabecalho = JSON.writeValueAsString(Map.of("alg", "RS256", campo, "https://attacker.example.test/key"));
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.assinar(cabecalho, JSON.writeValueAsString(claims())));
    }

    @ParameterizedTest
    @ValueSource(strings = { "none", "HS256", "RS384", "RS512", "ES256" })
    void rejeitaQualquerAlgoritmoDiferenteDeRs256(String algoritmo) {
        String cabecalho = JSON.writeValueAsString(Map.of("alg", algoritmo));
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.assinar(cabecalho, JSON.writeValueAsString(claims())));
    }

    @Test
    void rejeitaAssinaturaDeOutraChaveEConteudoAdulterado() {
        String texto = JSON.writeValueAsString(claims());
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.assinar("{\"alg\":\"RS256\"}", texto,
                AutenticacaoTestFixtures.gerarRsa(3072).getPrivate()));
        String token = SessaoJwtTestFixtures.token(claims());
        String[] partes = token.split("\\.");
        var alterados = claims();
        alterados.put("sub", UUID.randomUUID().toString());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsBytes(alterados));
        assertNaoAutenticado("Bearer " + partes[0] + "." + payload + "." + partes[2]);
    }

    @Test
    void rejeitaClaimsECabecalhosDuplicadosNoJsonAssinado() {
        String claims = JSON.writeValueAsString(claims());
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.assinar("{\"alg\":\"RS256\"}",
                claims.substring(0, claims.length() - 1) + ",\"papel\":\"admin\"}"));
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.assinar("{\"alg\":\"RS256\",\"alg\":\"RS256\"}", claims));
    }

    @Test
    void aceitaAudienciaTextualEClockSkewSomenteParaIatFuturo() {
        var claims = claims();
        claims.put("aud", AutenticacaoTestFixtures.AUDIENCE);
        claims.put("iat", AGORA.plusSeconds(30).getEpochSecond());
        claims.put("exp", AGORA.plusSeconds(3630).getEpochSecond());
        assertThat(sessoes.validarBearer(List.of("bearer " + SessaoJwtTestFixtures.token(claims))).contaId()).isEqualTo(ID);

        claims.put("iat", AGORA.minusSeconds(3600).getEpochSecond());
        claims.put("exp", AGORA.getEpochSecond());
        assertNaoAutenticado("Bearer " + SessaoJwtTestFixtures.token(claims));
    }

    @Test
    void rejeitaAusenciaDuplicidadeECredencialAmbiguaSemExporToken() {
        assertThatThrownBy(() -> sessoes.validarBearer(List.of())).isInstanceOf(AcessoNegadoException.class);
        String token = SessaoJwtTestFixtures.token(claims());
        assertThatThrownBy(() -> sessoes.validarBearer(List.of("Bearer " + token, "Bearer " + token)))
                .isInstanceOf(AcessoNegadoException.class).hasNoCause().hasMessage("Autenticação necessária.");
        for (String valor : List.of("", "Basic fixture", "Bearer", "Bearer ", "Bearer fixture", "Bearer  " + token,
                "Bearer " + token + ", Bearer " + token, "Bearer " + token + " ")) {
            assertNaoAutenticado(valor);
        }
    }

    private void assertNaoAutenticado(String autorizacao) {
        assertThatThrownBy(() -> sessoes.validarBearer(List.of(autorizacao)))
                .isInstanceOf(AcessoNegadoException.class).hasNoCause().hasMessage("Autenticação necessária.");
    }

    private static Map<String, Object> claims() {
        return SessaoJwtTestFixtures.claims(ID, AGORA);
    }

    static Stream<Arguments> claimsInvalidos() {
        var casos = new ArrayList<Arguments>();
        for (String obrigatorio : List.of("sub", "papel", "iss", "aud", "iat", "exp", "jti")) {
            var ausente = claims();
            ausente.remove(obrigatorio);
            casos.add(Arguments.of("ausente " + obrigatorio, ausente));
            var nulo = claims();
            nulo.put(obrigatorio, null);
            casos.add(Arguments.of("nulo " + obrigatorio, nulo));
        }
        Map<String, List<Object>> valores = Map.of(
                "sub", List.of("invalido", "1-1-1-1-1", ID.toString().toUpperCase(), 123),
                "papel", List.of("ADMIN", "organizador", "", 123),
                "iss", List.of("https://outro.example.test", 123),
                "aud", List.of("outra", List.of("outra"), List.of(), List.of(AutenticacaoTestFixtures.AUDIENCE, 123)),
                "jti", List.of("", "  ", 123),
                "iat", List.of("1793188800", 1.5, -1, AGORA.plusSeconds(31).getEpochSecond()),
                "exp", List.of("1793192400", 1.5, -1, AGORA.getEpochSecond(), AGORA.minusSeconds(1).getEpochSecond(),
                        AGORA.plusSeconds(3601).getEpochSecond()));
        valores.forEach((nome, invalidos) -> invalidos.forEach(valor -> {
            var alterados = claims();
            alterados.put(nome, valor);
            casos.add(Arguments.of(nome + " inválido: " + valor, alterados));
        }));
        var intervaloInvalido = claims();
        intervaloInvalido.put("iat", AGORA.plusSeconds(20).getEpochSecond());
        intervaloInvalido.put("exp", AGORA.plusSeconds(10).getEpochSecond());
        casos.add(Arguments.of("exp anterior a iat", intervaloInvalido));
        var nbfFuturo = claims();
        nbfFuturo.put("nbf", AGORA.plusSeconds(1).getEpochSecond());
        casos.add(Arguments.of("nbf futuro", nbfFuturo));
        return casos.stream();
    }
}
