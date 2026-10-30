package br.com.balaio.profile.account;

import br.com.balaio.profile.recovery.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final AuthController auth;
    public UserController(AuthController auth) { this.auth = auth; }

    @GetMapping("/api/v1/usuario")
    public ResponseEntity<ApiResponse<AuthController.PublicAccount>> user(HttpServletRequest request) {
        Account account = auth.accountFromSession(request);
        if (account == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("NAO_AUTENTICADO", "Entre na sua conta para continuar."));
        return ResponseEntity.ok(ApiResponse.ok(AuthController.PublicAccount.of(account)));
    }
}
