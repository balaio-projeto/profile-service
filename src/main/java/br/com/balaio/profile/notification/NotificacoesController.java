package br.com.balaio.profile.notification;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.security.ValidarSessaoAdapter;

@RestController
@RequestMapping("/api/v1/notificacoes")
public class NotificacoesController {
    public record Caixa(List<Notificacoes.Notificacao> itens, int naoLidas) { }

    private final Notificacoes notificacoes;
    private final ValidarSessaoAdapter sessoes;

    public NotificacoesController(Notificacoes notificacoes, ValidarSessaoAdapter sessoes) {
        this.notificacoes = notificacoes;
        this.sessoes = sessoes;
    }

    private UUID conta(HttpServletRequest request) {
        return sessoes.validarBearer(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION))).contaId();
    }

    @GetMapping
    public ResponseEntity<CadastroPublicoResposta<Caixa>> listar(HttpServletRequest request) {
        UUID conta = conta(request);
        return semCache(new Caixa(notificacoes.listar(conta), notificacoes.naoLidas(conta)));
    }

    @PostMapping("/leitura")
    public ResponseEntity<CadastroPublicoResposta<Caixa>> marcarTodas(HttpServletRequest request) {
        UUID conta = conta(request);
        notificacoes.marcarTodasLidas(conta);
        return semCache(new Caixa(notificacoes.listar(conta), 0));
    }

    @PatchMapping("/{id}/leitura")
    public ResponseEntity<CadastroPublicoResposta<Caixa>> marcarLida(@PathVariable UUID id, HttpServletRequest request) {
        UUID conta = conta(request);
        if (!notificacoes.marcarLida(conta, id))
            return erro(HttpStatus.NOT_FOUND, "RECURSO_NAO_ENCONTRADO", "Notificação não encontrada.");
        return semCache(new Caixa(notificacoes.listar(conta), notificacoes.naoLidas(conta)));
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<CadastroPublicoResposta<Caixa>> acessoNegado(AcessoNegadoException exception) {
        return erro(HttpStatus.UNAUTHORIZED, "NAO_AUTENTICADO", "Autenticação necessária.");
    }

    private static ResponseEntity<CadastroPublicoResposta<Caixa>> semCache(Caixa caixa) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CadastroPublicoResposta<>(caixa, null));
    }

    private static ResponseEntity<CadastroPublicoResposta<Caixa>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
