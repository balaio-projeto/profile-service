package br.com.balaio.profile.notification;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import br.com.balaio.profile.recovery.RecoveryConfiguration;

@Component
public class CorreioBalaio {
    private final JavaMailSender smtp;
    private final RecoveryConfiguration.Properties propriedades;

    public CorreioBalaio(JavaMailSender smtp, RecoveryConfiguration.Properties propriedades) {
        this.smtp = smtp;
        this.propriedades = propriedades;
    }

    public String frontend() {
        return propriedades.frontendBaseUrl().replaceAll("/+$", "");
    }

    public void enviar(String destinatario, EmailBalaio email) {
        var mensagem = smtp.createMimeMessage();
        try {
            var ajudante = new MimeMessageHelper(mensagem, true, StandardCharsets.UTF_8.name());
            ajudante.setFrom(propriedades.from());
            ajudante.setTo(destinatario);
            ajudante.setSubject(email.assunto());
            ajudante.setText(email.texto(frontend()), email.html(frontend()));
        } catch (MessagingException falha) {
            throw new MailPreparationException("Não foi possível montar o e-mail.", falha);
        }
        smtp.send(mensagem);
    }
}
