package br.com.balaio.profile.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LimpezaNotificacoes {
    private static final Logger log = LoggerFactory.getLogger(LimpezaNotificacoes.class);

    private final Notificacoes notificacoes;

    public LimpezaNotificacoes(Notificacoes notificacoes) {
        this.notificacoes = notificacoes;
    }

    @Scheduled(fixedDelayString = "${balaio.notificacoes.limpeza-intervalo:3600000}",
            initialDelayString = "${balaio.notificacoes.limpeza-intervalo:3600000}")
    public void removerLidasAntigas() {
        int removidas = notificacoes.removerLidasHaMaisDeUmDia();
        if (removidas > 0) log.info("{} notificações lidas há mais de 24 horas foram removidas.", removidas);
    }
}
