package br.com.balaio.profile.artisan.api;

import br.com.balaio.profile.account.imagem.ImagemContaController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A consumes mismatch happens before Spring selects a controller for scoped advice. */
@RestControllerAdvice
public class MidiaContentTypeExceptionHandler {
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<PerfilArtesaoExceptionHandler.RespostaErro> formato(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request)
            throws HttpMediaTypeNotSupportedException {
        if (!"PUT".equals(request.getMethod())
                || !Set.of("/api/v1/artesao/me/imagem", ImagemContaController.CAMINHO).contains(request.getRequestURI())) {
            throw exception;
        }
        var erro = new PerfilArtesaoExceptionHandler.Erro("FORMATO_NAO_SUPORTADO",
                "Envie multipart/form-data com o campo arquivo.", null);
        return ResponseEntity.status(415).header("Cache-Control", "no-store")
                .body(new PerfilArtesaoExceptionHandler.RespostaErro(null, erro));
    }
}
