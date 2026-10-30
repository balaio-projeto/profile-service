package br.com.balaio.profile.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class RecoveryController {
    private final RecoveryService service;
    public RecoveryController(RecoveryService service) { this.service = service; }

    public record Request(@Email @NotBlank String email) {}
    public record Reset(String token, @NotBlank @Size(min = 8) String novaSenha) {}
    public record Receipt(boolean recebida) {}
    public record ResetReceipt(boolean redefinida) {}

    @PostMapping("/recuperacao")
    public ResponseEntity<ApiResponse<Receipt>> request(@Valid @RequestBody Request request) {
        service.request(request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new Receipt(true)));
    }

    @PostMapping("/redefinicao")
    public ResponseEntity<ApiResponse<ResetReceipt>> reset(@Valid @RequestBody Reset request) {
        if (request.token() == null || request.token().isBlank() || request.token().length() > 128
                || !service.reset(request.token(), request.novaSenha()))
            return ResponseEntity.badRequest().body(ApiResponse.error("TOKEN_INVALIDO", "Link inválido ou expirado. Solicite outro link."));
        return ResponseEntity.ok(ApiResponse.ok(new ResetReceipt(true)));
    }
}
