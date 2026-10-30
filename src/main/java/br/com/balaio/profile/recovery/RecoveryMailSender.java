package br.com.balaio.profile.recovery;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RecoveryMailSender {
    private static final Logger log = LoggerFactory.getLogger(RecoveryMailSender.class);
    private final JavaMailSender sender;
    private final RecoveryConfiguration.Properties properties;

    public RecoveryMailSender(JavaMailSender sender, RecoveryConfiguration.Properties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public record MailRequested(String email, String token) {}

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(MailRequested event) {
        String url = properties.frontendBaseUrl().replaceAll("/+$", "") + "/login/reset?token="
                + URLEncoder.encode(event.token(), StandardCharsets.UTF_8);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(event.email());
        message.setSubject("Redefina sua senha no Balaio");
        message.setText("Para criar uma nova senha, acesse o link abaixo. Ele expira em "
                + properties.tokenTtl().toMinutes() + " minutos.\n\n" + url);
        try {
            sender.send(message);
        } catch (Exception exception) {
            log.error("Falha no envio de e-mail de recuperação; verifique o servidor SMTP.");
        }
    }
}
