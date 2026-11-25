package br.com.balaio.profile.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;

@RestController
public class OrigemFreteInternaController {
    public record OrigemFrete(String cepOrigem, int prazoPadraoDias, boolean encomendasPausadas,
            boolean aceitaRetirada, boolean aceitaEntregaLocal, String retiradaBairro, String retiradaCidade) {
        @Override public String toString() { return "OrigemFrete[REDACTED]"; }
    }

    private static final int PRAZO_PADRAO_SEM_CONFIGURACAO = 1;

    private final JdbcTemplate jdbc;
    private final String tokenInterno;

    public OrigemFreteInternaController(JdbcTemplate jdbc, @Value("${balaio.internal-token:}") String tokenInterno) {
        this.jdbc = jdbc;
        this.tokenInterno = tokenInterno;
    }

    @GetMapping("/internal/v1/contas/{id}/origem-frete")
    public ResponseEntity<CadastroPublicoResposta<OrigemFrete>> origem(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        if (!tokenValido(token))
            return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação interna não permitida.");
        var origem = jdbc.query("""
                SELECT c.cep_origem, c.prazo_padrao_dias, c.encomendas_pausadas, c.aceita_retirada,
                       c.aceita_entrega_local, c.retirada_bairro, c.retirada_cidade
                FROM perfis_artesao p LEFT JOIN configuracoes_atelie c ON c.perfil_id = p.id
                WHERE p.conta_id = ?
                """, (rs, linha) -> {
                    String cep = rs.getString("cep_origem");
                    Integer prazo = rs.getObject("prazo_padrao_dias", Integer.class);
                    return new OrigemFrete(cepSomenteDigitos(cep),
                            prazo == null ? PRAZO_PADRAO_SEM_CONFIGURACAO : prazo, rs.getBoolean("encomendas_pausadas"),
                            rs.getBoolean("aceita_retirada"), rs.getBoolean("aceita_entrega_local"),
                            textoOuNulo(rs.getString("retirada_bairro")), textoOuNulo(rs.getString("retirada_cidade")));
                }, id).stream().findFirst();
        if (origem.isEmpty())
            return erro(HttpStatus.NOT_FOUND, "PERFIL_NAO_ENCONTRADO", "Ateliê não encontrado.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(origem.get(), null));
    }

    private static String textoOuNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto;
    }

    private static String cepSomenteDigitos(String cep) {
        if (cep == null) return null;
        String digitos = cep.replaceAll("\\D", "");
        return digitos.length() == 8 ? digitos : null;
    }

    private boolean tokenValido(String recebido) {
        return tokenInterno.length() >= 32 && recebido != null && MessageDigest.isEqual(
                tokenInterno.getBytes(StandardCharsets.UTF_8), recebido.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<CadastroPublicoResposta<OrigemFrete>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
