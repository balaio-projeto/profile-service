package br.com.balaio.profile.recovery;

import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import java.security.SecureRandom;

@Configuration
@EnableAsync
@EnableConfigurationProperties(RecoveryConfiguration.Properties.class)
public class RecoveryConfiguration {
    @ConfigurationProperties(prefix = "recovery")
    public record Properties(String frontendBaseUrl, String from, Duration tokenTtl) {
        public Properties {
            if (frontendBaseUrl == null || frontendBaseUrl.isBlank() || from == null || from.isBlank()
                    || tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()
                    || tokenTtl.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("Configuração de recuperação inválida.");
            }
            validarUrl(frontendBaseUrl);
        }
    }
    static void validarUrl(String value) {
        try {
            URI uri=new URI(value);
            String scheme=uri.getScheme();
            if(uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null
                    || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535
                    || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback(uri.getHost())))
                    || !uri.normalize().equals(uri) || (uri.getRawPath()!=null && uri.getRawPath().contains("//"))) {
                throw new IllegalArgumentException();
            }
        } catch(URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("URL de recuperação inválida; use HTTPS ou HTTP em loopback local.");
        }
    }
    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
    @Bean InitializingBean validarConfiguracaoSmtp(Environment environment) {
        return () -> validarSmtp(environment);
    }
    static void validarSmtp(Environment env) {
        String host=env.getProperty("spring.mail.host","localhost");
        int port=env.getProperty("spring.mail.port",Integer.class,1025);
        boolean auth=env.getProperty("spring.mail.properties.mail.smtp.auth",Boolean.class,false);
        boolean tls=env.getProperty("spring.mail.properties.mail.smtp.starttls.enable",Boolean.class,false);
        // Host simples permite o nome de um serviço Docker somente no perfil local.
        boolean local=loopback(host) || (env.matchesProfiles("local") && host.matches("[A-Za-z0-9_-]+"));
        String username=env.getProperty("spring.mail.username","");
        String password=env.getProperty("spring.mail.password","");
        boolean required=env.getProperty("spring.mail.properties.mail.smtp.starttls.required",Boolean.class,tls);
        boolean identity=env.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity",Boolean.class,true);
        String trust=env.getProperty("spring.mail.properties.mail.smtp.ssl.trust","");
        boolean debug=env.getProperty("spring.mail.properties.mail.debug",Boolean.class,false);
        if(host.isBlank() || port<1 || port>65535 || (!tls && !local)
                || (auth && (!tls || username.isBlank() || password.isBlank()))
                || ("smtp.gmail.com".equalsIgnoreCase(host) && !auth)
                || (tls && (!required || !identity || !trust.isBlank())) || debug) {
            throw new IllegalArgumentException("Configuração SMTP inválida; confira host, porta, autenticação e TLS.");
        }
        for(String timeout:java.util.List.of("connectiontimeout","timeout","writetimeout")) {
            if(env.getProperty("spring.mail.properties.mail.smtp."+timeout,Integer.class,5000)<=0) {
                throw new IllegalArgumentException("Timeout SMTP deve ser positivo.");
            }
        }
    }
    @Bean SecureRandom secureRandom() { return new SecureRandom(); }
}
