package br.com.balaio.profile.notification;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AvisosContaListener {
    private static final Logger log = LoggerFactory.getLogger(AvisosContaListener.class);
    private static final ZoneId RECIFE = ZoneId.of("America/Recife");
    private static final DateTimeFormatter DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"));

    record Destinatario(String nome, String email, String papel) {
        String primeiroNome() {
            String limpo = nome == null ? "" : nome.trim();
            int espaco = limpo.indexOf(' ');
            return espaco > 0 ? limpo.substring(0, espaco) : limpo;
        }
    }

    private final JdbcTemplate jdbc;
    private final Notificacoes notificacoes;
    private final CorreioBalaio correio;
    private final Clock relogio = Clock.systemUTC();

    public AvisosContaListener(JdbcTemplate jdbc, Notificacoes notificacoes, CorreioBalaio correio) {
        this.jdbc = jdbc;
        this.notificacoes = notificacoes;
        this.correio = correio;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void avisar(AvisoConta aviso) {
        var destinatario = jdbc.query("SELECT nome, email, papel FROM contas WHERE id = ?",
                (rs, linha) -> new Destinatario(rs.getString("nome"), rs.getString("email"), rs.getString("papel")),
                aviso.conta()).stream().findFirst().orElse(null);
        if (destinatario == null) return;
        try {
            notificacoes.registrar(notificacao(aviso.conta(), aviso.tipo(), destinatario));
        } catch (RuntimeException falha) {
            log.warn("Falha ao registrar a notificação {} da conta: {}", aviso.tipo(), falha.getClass().getSimpleName());
        }
        try {
            correio.enviar(destinatario.email(), email(aviso.tipo(), destinatario));
        } catch (RuntimeException falha) {
            log.error("Falha no envio do e-mail {}; verifique o servidor SMTP.", aviso.tipo());
        }
    }

    Notificacoes.NovaNotificacao notificacao(UUID conta, AvisoConta.Tipo tipo, Destinatario destinatario) {
        boolean artesao = "artesao".equals(destinatario.papel());
        return switch (tipo) {
            case BOAS_VINDAS -> new Notificacoes.NovaNotificacao(conta, "boas_vindas", "Boas-vindas ao Balaio",
                    artesao ? "Sua conta de artesão está pronta. Complete o perfil do ateliê e cadastre a primeira peça."
                            : "Sua conta está pronta. Descubra peças feitas à mão por artesãos de Pernambuco.",
                    artesao ? "/dashboard/settings" : "/", "boas_vindas:" + conta);
            case SENHA_ALTERADA -> new Notificacoes.NovaNotificacao(conta, "senha_alterada", "Senha alterada",
                    "A senha da sua conta foi alterada. Se não foi você, redefina a senha imediatamente.",
                    "/account/details", null);
            case SENHA_REDEFINIDA -> new Notificacoes.NovaNotificacao(conta, "senha_redefinida", "Senha redefinida",
                    "A senha da sua conta foi redefinida pelo link de recuperação.", "/account/details", null);
        };
    }

    EmailBalaio email(AvisoConta.Tipo tipo, Destinatario destinatario) {
        String nome = destinatario.primeiroNome();
        String quando = ZonedDateTime.now(relogio).withZoneSameInstant(RECIFE).format(DATA_HORA);
        boolean artesao = "artesao".equals(destinatario.papel());
        return switch (tipo) {
            case BOAS_VINDAS -> artesao
                    ? new EmailBalaio("Boas-vindas ao Balaio", "Sua conta de artesão está pronta.",
                            nome.isEmpty() ? "Boas-vindas ao Balaio!" : "Boas-vindas, " + nome + "!",
                            List.of("Sua conta de artesão no Balaio está pronta.",
                                    "Para começar a vender, complete o perfil do ateliê com foto, história e CEP de origem "
                                            + "e cadastre a primeira peça. Você recebe um aviso a cada pedido novo."),
                            List.of(), "Configurar meu ateliê", "/dashboard/settings", null)
                    : new EmailBalaio("Boas-vindas ao Balaio", "Sua conta está pronta.",
                            nome.isEmpty() ? "Boas-vindas ao Balaio!" : "Boas-vindas, " + nome + "!",
                            List.of("Sua conta no Balaio está pronta.",
                                    "Aqui você encontra peças únicas e sob encomenda feitas por artesãos de Pernambuco, "
                                            + "acompanha cada etapa do pedido e conversa direto com quem produz."),
                            List.of(), "Conhecer a vitrine", "/", null);
            case SENHA_ALTERADA -> new EmailBalaio("Sua senha foi alterada", "A senha da sua conta foi alterada.",
                    "Sua senha foi alterada",
                    List.of((nome.isEmpty() ? "Olá" : "Olá, " + nome) + ". A senha da sua conta no Balaio foi alterada em "
                            + quando + ".", "Se foi você, não é preciso fazer mais nada."),
                    List.of(), "Redefinir senha", "/login/recover",
                    "Não reconhece essa alteração? Redefina a senha agora e confira os dados da sua conta.");
            case SENHA_REDEFINIDA -> new EmailBalaio("Sua senha foi redefinida", "A senha da sua conta foi redefinida.",
                    "Sua senha foi redefinida",
                    List.of((nome.isEmpty() ? "Olá" : "Olá, " + nome) + ". A senha da sua conta no Balaio foi redefinida em "
                            + quando + " pelo link de recuperação.", "Use a nova senha no próximo acesso."),
                    List.of(), "Entrar no Balaio", "/login",
                    "Não pediu essa redefinição? Redefina a senha novamente e confira os dados da sua conta.");
        };
    }
}
