package br.com.balaio.profile.account.security;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.account.application.AutenticacaoException;
import br.com.balaio.profile.account.application.ContaPublicaCadastrada;

@Component
public class EmissorJwtAdapter {
    public static final long DURACAO_SEGUNDOS = 3600;
    private final JwtEncoder encoder;
    private final Clock relogio;
    private final AutenticacaoProperties properties;

    public EmissorJwtAdapter(JwtEncoder encoder, Clock relogio, AutenticacaoProperties properties) {
        this.encoder = encoder;
        this.relogio = relogio;
        this.properties = properties;
    }

    public String emitir(ContaPublicaCadastrada conta) {
        try {
            var agora = relogio.instant().truncatedTo(ChronoUnit.SECONDS);
            var claims = JwtClaimsSet.builder()
                    .subject(conta.id().toString())
                    .claim("papel", conta.papel().codigo())
                    .issuer(properties.jwt().issuer())
                    .audience(List.of(properties.jwt().audience()))
                    .issuedAt(agora)
                    .expiresAt(agora.plusSeconds(DURACAO_SEGUNDOS))
                    .id(UUID.randomUUID().toString())
                    .build();
            var cabecalho = JwsHeader.with(SignatureAlgorithm.RS256).build();
            return encoder.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
        } catch (RuntimeException exception) {
            throw new AutenticacaoException();
        }
    }
}
