package br.com.balaio.profile.recovery;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RecoveryConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"https://balaio.example.test","https://balaio.example.test/market/","http://localhost:4321/","http://127.0.0.1:4321","http://[::1]:4321"})
    void aceitaUrlsAbsolutasHttpsOuLoopback(String url) {
        assertThatCode(()->new RecoveryConfiguration.Properties(url,"teste@example.test",Duration.ofHours(1))).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"/login","balaio.example.test","http://balaio.example.test","javascript:alert(1)","https://user:secret@balaio.example.test","https://balaio.example.test?token=secret","https://balaio.example.test#fragment","https://balaio.example.test/a/../b","https://balaio.example.test//path","https://balaio.example.test:0","https://balaio.example.test:99999"})
    void recusaUrlIncorretaSemExporSeuValor(String url) {
        assertThatThrownBy(()->new RecoveryConfiguration.Properties(url,"teste@example.test",Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(url);
    }
    @Test void permiteSmtpLocalSemObrigarGmail() {
        assertThatCode(()->RecoveryConfiguration.validarSmtp(new MockEnvironment())).doesNotThrowAnyException();
        var local=new MockEnvironment().withProperty("spring.mail.host","mailpit");
        local.setActiveProfiles("local");
        assertThatCode(()->RecoveryConfiguration.validarSmtp(local)).doesNotThrowAnyException();
    }
    @Test void aceitaGmailComAutenticacaoETls() {
        assertThatCode(()->RecoveryConfiguration.validarSmtp(gmail())).doesNotThrowAnyException();
    }
    @Test void impedeGmailSemCredenciaisSemTlsOuComValidacaoTlsDesativada() {
        for(String key:new String[]{"spring.mail.username","spring.mail.password"}) {
            assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(gmail().withProperty(key,""))).isInstanceOf(IllegalArgumentException.class);
        }
        for(String key:new String[]{"spring.mail.properties.mail.smtp.auth","spring.mail.properties.mail.smtp.starttls.enable",
                "spring.mail.properties.mail.smtp.starttls.required","spring.mail.properties.mail.smtp.ssl.checkserveridentity"}) {
            assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(gmail().withProperty(key,"false"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(gmail().withProperty("spring.mail.properties.mail.smtp.ssl.trust","*"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(gmail().withProperty("spring.mail.properties.mail.debug","true"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(new MockEnvironment().withProperty("spring.mail.host","smtp.example.test"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejeitaTimeoutIlimitado() {
        for(String key:new String[]{"connectiontimeout","timeout","writetimeout"}) {
            assertThatThrownBy(()->RecoveryConfiguration.validarSmtp(gmail().withProperty("spring.mail.properties.mail.smtp."+key,"0"))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private MockEnvironment gmail() {
        return new MockEnvironment().withProperty("spring.mail.host","smtp.gmail.com").withProperty("spring.mail.port","587")
                .withProperty("spring.mail.username","fixture@example.test").withProperty("spring.mail.password","senha-sintetica-apenas-teste")
                .withProperty("spring.mail.properties.mail.smtp.auth","true").withProperty("spring.mail.properties.mail.smtp.starttls.enable","true");
    }
}
