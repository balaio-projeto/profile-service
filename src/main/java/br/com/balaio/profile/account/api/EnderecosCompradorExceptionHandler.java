package br.com.balaio.profile.account.api;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = EnderecosCompradorController.class)
public class EnderecosCompradorExceptionHandler {

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> acessoNegado(AcessoNegadoException exception) {
        boolean naoAutenticado = exception.motivo() == AcessoNegadoException.Motivo.NAO_AUTENTICADO
                || exception.motivo() == AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;
        return erro(naoAutenticado ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
                naoAutenticado ? "NAO_AUTENTICADO" : "SEM_PERMISSAO",
                naoAutenticado ? "Autenticação necessária." : "Operação não permitida.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> statusDeNegocio(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String codigo = switch (status) {
            case FORBIDDEN -> "SEM_PERMISSAO";
            case NOT_FOUND -> "RECURSO_NAO_ENCONTRADO";
            case BAD_REQUEST -> "REQUISICAO_INVALIDA";
            default -> "ERRO";
        };
        return erro(status, codigo, exception.getReason() == null ? "Requisição não concluída." : exception.getReason());
    }

    private static ResponseEntity<CadastroPublicoResposta<Void>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
