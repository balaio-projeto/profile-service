package br.com.balaio.profile.account.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.AlteracaoPapelException;
import br.com.balaio.profile.account.application.AlteracaoPapelException.Motivo;
import br.com.balaio.profile.account.application.BuscaContaAdminException;

@RestControllerAdvice(assignableTypes = OrganizadorAdminController.class)
public class OrganizadorAdminExceptionHandler {

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> acessoNegado(AcessoNegadoException exception) {
        boolean naoAutenticado = exception.motivo() == AcessoNegadoException.Motivo.NAO_AUTENTICADO
                || exception.motivo() == AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;
        if (exception.motivo() == AcessoNegadoException.Motivo.TROCA_SENHA_OBRIGATORIA) {
            return erro(HttpStatus.FORBIDDEN, "TROCA_SENHA_OBRIGATORIA", exception.getMessage());
        }
        return naoAutenticado
                ? erro(HttpStatus.UNAUTHORIZED, "NAO_AUTENTICADO", "Autenticação necessária.")
                : erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Operação não permitida.");
    }

    @ExceptionHandler({ OrganizadorAdminController.OrganizadorInvalidoException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<CadastroPublicoResposta<Void>> entradaInvalida() {
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Dados da requisição inválidos.");
    }

    @ExceptionHandler(AlteracaoPapelException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> alteracaoPapel(AlteracaoPapelException exception) {
        Motivo motivo = exception.motivo();
        return switch (motivo) {
            case COMANDO_INVALIDO -> erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", exception.getMessage());
            case CONTA_ALVO_INDISPONIVEL -> erro(HttpStatus.NOT_FOUND, "CONTA_NAO_ENCONTRADA", exception.getMessage());
            case TRANSICAO_NAO_PERMITIDA -> erro(HttpStatus.CONFLICT, "TRANSICAO_PAPEL_INVALIDA", exception.getMessage());
            case CONFLITO_ATUALIZACAO -> erro(HttpStatus.CONFLICT, "CONFLITO_ATUALIZACAO", exception.getMessage());
            case FALHA_ALTERACAO -> erro(HttpStatus.INTERNAL_SERVER_ERROR, "ALTERACAO_PAPEL_INDISPONIVEL", exception.getMessage());
        };
    }

    @ExceptionHandler(BuscaContaAdminException.class)
    public ResponseEntity<CadastroPublicoResposta<Void>> buscaConta(BuscaContaAdminException exception) {
        return switch (exception.motivo()) {
            case EMAIL_INVALIDO -> erro(HttpStatus.BAD_REQUEST, "EMAIL_INVALIDO", exception.getMessage());
            case CONTA_NAO_ENCONTRADA -> erro(HttpStatus.NOT_FOUND, "CONTA_NAO_ENCONTRADA", exception.getMessage());
            case FALHA_CONSULTA -> erro(HttpStatus.INTERNAL_SERVER_ERROR, "CONSULTA_CONTA_INDISPONIVEL", exception.getMessage());
        };
    }

    private static ResponseEntity<CadastroPublicoResposta<Void>> erro(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new CadastroPublicoResposta<>(null, new CadastroPublicoResposta.Erro(codigo, mensagem)));
    }
}
