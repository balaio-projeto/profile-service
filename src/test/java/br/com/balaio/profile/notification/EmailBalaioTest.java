package br.com.balaio.profile.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class EmailBalaioTest {
    private final EmailBalaio email = new EmailBalaio("Assunto", "Resumo", "Olá, <b>Ana</b>",
            List.of("Pedido \"A&B\" confirmado."), List.of(new EmailBalaio.Detalhe("Total", "R$ 10,00")),
            "Ver pedido", "/orders/1", "Nota final");

    @Test
    void htmlEscapaConteudoEUsaLinksAbsolutosDoFront() {
        String html = email.html("https://balaio.example/");
        assertThat(html).contains("Olá, &lt;b&gt;Ana&lt;/b&gt;", "Pedido &quot;A&amp;B&quot; confirmado.")
                .doesNotContain("<b>Ana</b>")
                .contains("href=\"https://balaio.example/orders/1\"", "src=\"https://balaio.example/balaio-logo.png\"")
                .contains("R$ 10,00", "Nota final", "Poiret One");
    }

    @Test
    void textoTrazOMesmoConteudoSemMarcacao() {
        String texto = email.texto("https://balaio.example");
        assertThat(texto).contains("Olá, <b>Ana</b>", "Total: R$ 10,00", "Ver pedido: https://balaio.example/orders/1",
                "Nota final").doesNotContain("<table");
    }
}
