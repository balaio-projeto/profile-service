package br.com.balaio.profile.recovery;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import br.com.balaio.profile.account.api.OrigemNaoPermitidaException;

@RestControllerAdvice(assignableTypes = RecoveryController.class)
public class ValidationHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalid() {
        return ResponseEntity.badRequest().body(ApiResponse.error("REQUISICAO_INVALIDA", "Dados inválidos."));
    }

    @ExceptionHandler({IllegalArgumentException.class, RecoveryPasswordException.class})
    public ResponseEntity<ApiResponse<Void>> invalidValue() {
        return invalid();
    }

    @ExceptionHandler(OrigemNaoPermitidaException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden() {
        return ResponseEntity.status(403).body(ApiResponse.error("SEM_PERMISSAO", "Operação não permitida."));
    }
}
