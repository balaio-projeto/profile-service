package br.com.balaio.profile.recovery;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.balaio.profile.notification.CorreioBalaio;
import br.com.balaio.profile.notification.EmailBalaio;

@Component
public class RecoveryMailSender {
    private static final Logger log = LoggerFactory.getLogger(RecoveryMailSender.class);
    private final CorreioBalaio correio;
    private final RecoveryConfiguration.Properties properties;

    public RecoveryMailSender(CorreioBalaio correio, RecoveryConfiguration.Properties properties) {
        this.correio = correio;
        this.properties = properties;
    }

    public record MailRequested(String email, String token) {
        @Override public String toString() { return "MailRequested[REDACTED]"; }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(MailRequested event) {
        String url = correio.frontend() + "/login/reset?token=" + URLEncoder.encode(event.token(), StandardCharsets.UTF_8);
        var email = new EmailBalaio("Redefina sua senha no Balaio", "Use o link para criar uma nova senha.",
                "Redefina sua senha",
                List.of("Recebemos um pedido para redefinir a senha da sua conta no Balaio.",
                        "O link abaixo expira em " + properties.tokenTtl().toMinutes() + " minutos e só pode ser usado uma vez."),
                List.of(), "Criar nova senha", url,
                "Se você não pediu a redefinição, ignore este e-mail. Sua senha atual continua valendo.");
        try {
            correio.enviar(event.email(), email);
        } catch (Exception exception) {
            log.error("Falha no envio de e-mail de recuperação; verifique o servidor SMTP.");
        }
    }
}
