package br.com.balaio.profile.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;

@RestController
public class NotificacoesInternasController {
    public record Registro(boolean criada) { }

    private static final Pattern TIPO = Pattern.compile("[a-z_]{3,40}");

    private final Notificacoes notificacoes;
    private final String tokenInterno;

    public NotificacoesInternasController(Notificacoes notificacoes,
            @Value("${balaio.internal-token:}") String tokenInterno) {
        this.notificacoes = notificacoes;
        this.tokenInterno = tokenInterno;
    }

    @PostMapping("/internal/v1/notificacoes")
    public ResponseEntity<CadastroPublicoResposta<Registro>> registrar(@RequestBody Map<String, Object> dados,
            @RequestHeader(value = "X-Balaio-Service-Token", required = false) String token) {
        if (!tokenValido(token))
            return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação interna não permitida.");
        UUID conta;
        String link;
        String chave;
        try {
            conta = UUID.fromString(String.valueOf(dados.get("conta")));
            link = opcional(dados.get("link"), 300);
            chave = opcional(dados.get("chave"), 160);
        } catch (IllegalArgumentException invalida) {
            return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Notificação inválida.");
        }
        String tipo = texto(dados.get("tipo"), 40);
        String titulo = texto(dados.get("titulo"), 120);
        String mensagem = texto(dados.get("mensagem"), 500);
        if (tipo == null || !TIPO.matcher(tipo).matches() || titulo == null || mensagem == null
                || (link != null && !link.startsWith("/")))
            return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Notificação inválida.");
        if (!notificacoes.contaExiste(conta))
            return erro(HttpStatus.NOT_FOUND, "CONTA_NAO_ENCONTRADA", "Conta não encontrada.");
        boolean criada = notificacoes.registrar(new Notificacoes.NovaNotificacao(conta, tipo, titulo, mensagem, link, chave));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(new Registro(criada), null));
    }

    private static String texto(Object valor, int limite) {
        return valor instanceof String texto && !texto.isBlank() && texto.trim().length() <= limite ? texto.trim() : null;
    }

    private static String opcional(Object valor, int limite) {
        if (valor == null) return null;
        String texto = texto(valor, limite);
        if (texto == null) throw new IllegalArgumentException("Campo opcional inválido.");
        return texto;
    }

    private boolean tokenValido(String recebido) {
        return tokenInterno.length() >= 32 && recebido != null && MessageDigest.isEqual(
                tokenInterno.getBytes(StandardCharsets.UTF_8), recebido.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<CadastroPublicoResposta<Registro>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
