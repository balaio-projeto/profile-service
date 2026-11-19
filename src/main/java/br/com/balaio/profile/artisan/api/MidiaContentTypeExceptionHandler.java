package br.com.balaio.profile.artisan.api;

import jakarta.servlet.http.HttpServletRequest;
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
                || !"/api/v1/artesao/me/imagem".equals(request.getRequestURI())) {
            throw exception;
        }
        var erro = new PerfilArtesaoExceptionHandler.Erro("FORMATO_NAO_SUPORTADO",
                "Envie multipart/form-data com o campo arquivo.", null);
        return ResponseEntity.status(415).header("Cache-Control", "no-store")
                .body(new PerfilArtesaoExceptionHandler.RespostaErro(null, erro));
    }
}
