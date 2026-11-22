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
        HttpStatus status = naoAutenticado ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(
                        naoAutenticado ? "NAO_AUTENTICADO" : "SEM_PERMISSAO",
                        naoAutenticado ? "Autenticação necessária." : "Operação não permitida.")));
    }
}
