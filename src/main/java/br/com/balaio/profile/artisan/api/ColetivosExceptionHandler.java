package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.account.api.CadastroPublicoResposta;
import br.com.balaio.profile.account.application.AcessoNegadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ColetivosController.class)
public class ColetivosExceptionHandler {

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> acessoNegado(AcessoNegadoException exception) {
        boolean naoAutenticado = exception.motivo() == AcessoNegadoException.Motivo.NAO_AUTENTICADO
                || exception.motivo() == AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;
        boolean trocaSenhaPendente = exception.motivo() == AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA;
        HttpStatus status = naoAutenticado ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        String codigo = naoAutenticado ? "NAO_AUTENTICADO" : trocaSenhaPendente ? "TROCA_SENHA_OBRIGATORIA" : "SEM_PERMISSAO";
        String mensagem = naoAutenticado ? "Autenticação necessária."
                : trocaSenhaPendente ? exception.getMessage() : "Operação não permitida.";
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
