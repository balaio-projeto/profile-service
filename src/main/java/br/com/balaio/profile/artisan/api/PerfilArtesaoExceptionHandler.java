package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.api.OrigemNaoPermitidaException;
import br.com.balaio.profile.artisan.application.PerfilDuplicadoException;
import br.com.balaio.profile.artisan.application.PerfilInvalidoException;
import br.com.balaio.profile.artisan.application.PerfilNaoEncontradoException;
import br.com.balaio.profile.artisan.media.MidiaException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice(assignableTypes = {PerfilArtesaoController.class, MidiaPerfilController.class})
public class PerfilArtesaoExceptionHandler {
    public record Erro(String codigo, String mensagem, Map<String,String> campos) { }
    public record RespostaErro(Object dados, Erro erro) { }

    @ExceptionHandler(MidiaException.class)
    ResponseEntity<RespostaErro> midia(MidiaException e) {
        HttpStatus status = switch (e.motivo()) {
            case ARQUIVO_INVALIDO -> HttpStatus.BAD_REQUEST;
            case ARQUIVO_MUITO_GRANDE -> HttpStatus.CONTENT_TOO_LARGE;
            case FORMATO_NAO_SUPORTADO -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case MIDIA_NAO_ENCONTRADA -> HttpStatus.NOT_FOUND;
            case CONFLITO -> HttpStatus.CONFLICT;
            case STORAGE_INDISPONIVEL -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return erro(status, e.motivo().name(), e.getMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<RespostaErro> tamanho() {
        return erro(HttpStatus.CONTENT_TOO_LARGE, "ARQUIVO_MUITO_GRANDE",
                "Imagem limitada a 2 MiB e requisicao multipart a 3 MiB.", null);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<RespostaErro> requisicaoInvalida() {
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Requisicao invalida.", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<RespostaErro> persistencia() {
        return erro(HttpStatus.SERVICE_UNAVAILABLE, "SERVICO_INDISPONIVEL",
                "Servico de imagens temporariamente indisponivel.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<RespostaErro> validacao(MethodArgumentNotValidException e) {
        Map<String,String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(erro ->
                campos.putIfAbsent(erro.getField(), erro.getDefaultMessage()));
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Campos inválidos.", campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<RespostaErro> jsonInvalido() {
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "JSON inválido.", Map.of());
    }

    @ExceptionHandler(PerfilInvalidoException.class)
    ResponseEntity<RespostaErro> dominioInvalido(PerfilInvalidoException e) {
        return erro(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Campos inválidos.", e.campos());
    }

    @ExceptionHandler(PerfilNaoEncontradoException.class)
    ResponseEntity<RespostaErro> naoEncontrado() {
        return erro(HttpStatus.NOT_FOUND, "RECURSO_NAO_ENCONTRADO", "Perfil não encontrado.", null);
    }

    @ExceptionHandler(PerfilDuplicadoException.class)
    ResponseEntity<RespostaErro> duplicado() {
        return erro(HttpStatus.CONFLICT, "PERFIL_JA_EXISTE", "Esta conta já possui um perfil.", null);
    }

    @ExceptionHandler(AcessoNegadoException.class)
    ResponseEntity<RespostaErro> acesso(AcessoNegadoException e) {
        boolean naoAutenticado = e.motivo() == AcessoNegadoException.Motivo.NAO_AUTENTICADO
                || e.motivo() == AcessoNegadoException.Motivo.CONTA_INDISPONIVEL;
        return erro(naoAutenticado ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
                naoAutenticado ? "NAO_AUTENTICADO" : "SEM_PERMISSAO",
                naoAutenticado ? "Autenticação necessária." : "Operação não permitida.", null);
    }

    @ExceptionHandler(OrigemNaoPermitidaException.class)
    ResponseEntity<RespostaErro> origem() {
        return erro(HttpStatus.FORBIDDEN, "SEM_PERMISSAO", "Origem não permitida.", null);
    }

    private ResponseEntity<RespostaErro> erro(HttpStatus status, String codigo, String mensagem,
                                              Map<String,String> campos) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new RespostaErro(null, new Erro(codigo, mensagem, campos)));
    }
}
