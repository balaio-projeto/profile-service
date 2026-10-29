package br.com.balaio.profile.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;
import br.com.balaio.profile.account.domain.Papel;

class EmissorJwtAdapterTest {
    private static final Instant AGORA = Instant.parse("2026-10-28T12:34:56.987Z");

    @ParameterizedTest
    @EnumSource(Papel.class)
    void assinaRs256ComClaimsExatosValidadeDe60MinutosERelogioDeterministico(Papel papel) throws Exception {
        var propriedades = AutenticacaoConfigTest.propriedades(AutenticacaoTestFixtures.privateKeyLocation(), true);
        var configuracao = new AutenticacaoConfig();
        var privada = configuracao.chavePrivadaJwt(propriedades);
        var publica = configuracao.chavePublicaJwt(privada);
        var emissor = new EmissorJwtAdapter(configuracao.jwtEncoder(publica, privada), Clock.fixed(AGORA, ZoneOffset.UTC), propriedades);
        var conta = new ContaPublicaCadastrada(UUID.randomUUID(), "Nome privado da fixture", "fixture@example.test", papel);

        String primeiro = emissor.emitir(conta);
        String segundo = emissor.emitir(conta);

        var claims = AutenticacaoTestFixtures.assertJwt(primeiro, conta.id(), papel.codigo(), AGORA, AGORA);
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-10-28T12:34:56Z"));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(Instant.parse("2026-10-28T13:34:56Z"));
        assertThat(claims.getJWTID()).isNotEqualTo(SignedJWT.parse(segundo).getJWTClaimsSet().getJWTID());
        assertThat(claims.getClaims().toString()).doesNotContain(conta.nome(), conta.email(), "senha", "hash");
    }

    @Test
    void assinaturaNaoVerificaComChaveDiferenteOuPayloadAdulterado() throws Exception {
        var propriedades = AutenticacaoConfigTest.propriedades(AutenticacaoTestFixtures.privateKeyLocation(), true);
        var config = new AutenticacaoConfig();
        var privada = config.chavePrivadaJwt(propriedades);
        var emissor = new EmissorJwtAdapter(config.jwtEncoder(config.chavePublicaJwt(privada), privada),
                Clock.fixed(AGORA, ZoneOffset.UTC), propriedades);
        String token = emissor.emitir(new ContaPublicaCadastrada(UUID.randomUUID(), "Maria", "maria@example.test", Papel.COMPRADOR));
        var outro = AutenticacaoTestFixtures.gerarRsa(3072);
        assertThat(SignedJWT.parse(token).verify(new RSASSAVerifier((java.security.interfaces.RSAPublicKey) outro.getPublic())))
                .isFalse();
        String[] partes = token.split("\\.");
        String payload = new Payload("{\"sub\":\"fixture-adulterada\",\"papel\":\"admin\"}").toBase64URL().toString();
        JWSObject adulterado = JWSObject.parse(partes[0] + "." + payload + "." + partes[2]);
        assertThat(adulterado.verify(new RSASSAVerifier(AutenticacaoTestFixtures.publicKey()))).isFalse();
    }

    @Test
    void falhaDoEncoderNaoExpoeContaOuCausaSensivel() {
        JwtEncoder encoder = mock(JwtEncoder.class);
        when(encoder.encode(any())).thenThrow(new IllegalStateException("fixture-privada-na-causa"));
        var emissor = new EmissorJwtAdapter(encoder, Clock.fixed(AGORA, ZoneOffset.UTC),
                AutenticacaoConfigTest.propriedades(AutenticacaoTestFixtures.privateKeyLocation(), true));

        assertThatThrownBy(() -> emissor.emitir(new ContaPublicaCadastrada(UUID.randomUUID(), "Maria", "maria@example.test", Papel.COMPRADOR)))
                .isInstanceOf(RuntimeException.class).hasNoCause()
                .hasMessageNotContaining("fixture-privada-na-causa");
    }
}
