package br.com.balaio.profile.account.api;

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

@RestController
public class ContatoInternoController {
    public record Contato(UUID id, String nome, String email) { }

    private final JdbcTemplate jdbc;
    private final String tokenInterno;

    public ContatoInternoController(JdbcTemplate jdbc, @Value("${balaio.internal-token:}") String tokenInterno) {
        this.jdbc = jdbc;
        this.tokenInterno = tokenInterno;
    }

    @GetMapping("/internal/v1/contas/{id}/contato")
    public ResponseEntity<CadastroPublicoResposta<Contato>> contato(@PathVariable UUID id,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        if (!tokenValido(token))
            return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação interna não permitida.");
        var contato = jdbc.query("SELECT id, nome, email FROM contas WHERE id = ?",
                (rs, row) -> new Contato(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("email")), id)
                .stream().findFirst();
        if (contato.isEmpty())
            return erro(HttpStatus.NOT_FOUND, "CONTA_NAO_ENCONTRADA", "Conta não encontrada.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(contato.get(), null));
    }

    private boolean tokenValido(String recebido) {
        return tokenInterno.length() >= 32 && recebido != null && MessageDigest.isEqual(
                tokenInterno.getBytes(StandardCharsets.UTF_8), recebido.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<CadastroPublicoResposta<Contato>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
