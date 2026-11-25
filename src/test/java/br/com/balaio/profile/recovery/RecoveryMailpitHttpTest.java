package br.com.balaio.profile.recovery;

import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SMTP real em Mailpit descartável. JavaMailSender não é mockado e não há relay externo. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"logging.level.root=WARN","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@Import(PostgreSQLTestConfiguration.class)
class RecoveryMailpitHttpTest {
    static final String IMAGE="axllent/mailpit:v1.34.0";
    static final GenericContainer<?> MAIL=mailpit();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String BASE="https://balaio.example.test/market/";
    static final String ANTIGA="SenhaAnterior123!";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride=true) InicializarAdministradorUseCase bootstrap;
    UUID conta;
    String email;
    private static GenericContainer<?> mailpit() {
        var container=new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(1025,8025).waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));
        container.start(); return container;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host",MAIL::getHost);
        registry.add("spring.mail.port",()->MAIL.getMappedPort(1025));
        registry.add("spring.mail.username",()->"");
        registry.add("spring.mail.password",()->"");
        registry.add("spring.mail.properties.mail.smtp.auth",()->false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable",()->false);
        registry.add("spring.mail.properties.mail.smtp.starttls.required",()->false);
        registry.add("recovery.frontend-base-url",()->BASE);
        registry.add("recovery.from",()->"nao-responda@balaio.test");
    }
    @AfterAll static void parar(){MAIL.stop();}
    @BeforeEach void preparar() throws Exception {
        mail("DELETE","/api/v1/messages");
        email="smtp-"+UUID.randomUUID()+"@example.test";
        var cadastro=post("/auth/cadastro",Map.of("nome","Pessoa SMTP","email",email,"senha",ANTIGA,"perfil","comprador"));
        assertThat(cadastro.statusCode()).isEqualTo(201);
        conta=UUID.fromString(JSON.readTree(cadastro.body()).get("dados").get("id").asText());
    }
    @AfterEach void limpar() throws Exception {
        if(conta!=null) {
            jdbc.update("DELETE FROM password_recovery WHERE conta_id=?",conta);
            jdbc.update("DELETE FROM contas WHERE id=?",conta);
        }
        mail("DELETE","/api/v1/messages");
    }
    @Test void entregaSmtpContemLinkCorretoETokenPermiteUmaUnicaRedefinicao() throws Exception {
        var conhecido=post("/auth/recuperacao",Map.of("email",email));
        var ausente=post("/auth/recuperacao",Map.of("email","ausente-"+UUID.randomUUID()+"@example.test"));
        assertThat(conhecido.statusCode()).isEqualTo(201);
        assertThat(ausente.statusCode()).isEqualTo(201);
        assertThat(ausente.body()).isEqualTo(conhecido.body());
        var mensagem=receber();
        assertThat(mensagem.get("To").get(0).get("Address").asText().equals(email)).isTrue();
        assertThat(mensagem.get("From").get("Address").asText().equals("nao-responda@balaio.test")).isTrue();
        assertThat(mensagem.get("Subject").asText().equals("Redefina sua senha no Balaio")).isTrue();
        String token=token(mensagem);
        String persistido=jdbc.queryForObject("SELECT token_hash FROM password_recovery WHERE conta_id=?",String.class,conta);
        assertThat(persistido.equals(RecoveryService.hash(token))).isTrue();
        assertThat(conhecido.body().contains(token)).isFalse();
        String nova="NovaSenhaRecuperada456!";
        assertThat(reset(token,nova).statusCode()).isEqualTo(200);
        assertThat(login(ANTIGA).statusCode()).isEqualTo(401);
        assertThat(login(nova).statusCode()).isEqualTo(200);
        var repetida=reset(token,"OutraSenhaSegura789!");
        assertThat(repetida.statusCode()).isEqualTo(400);
        assertThat(repetida.body()).contains("TOKEN_INVALIDO");
        assertThat(mensagensPara(ASSUNTO_RECUPERACAO)).hasSize(1);
        var aviso=aguardar("Sua senha foi redefinida");
        assertThat(aviso.get("HTML").asText()).contains("Sua senha foi redefinida","balaio-logo.png");
        assertThat(aviso.get("Text").asText()).doesNotContain(token);
    }
    @Test void cadastroEnviaBoasVindasComLayoutDaMarca() throws Exception {
        var boasVindas=aguardar("Boas-vindas ao Balaio");
        assertThat(boasVindas.get("HTML").asText()).contains("Boas-vindas, Pessoa!","Conhecer a vitrine","ARTESANATO DE PERNAMBUCO");
        assertThat(boasVindas.get("Text").asText()).contains("Sua conta no Balaio está pronta.");
    }
    @Test void linkSmtpExpiradoEDesconhecidoNaoAlteramCredencial() throws Exception {
        post("/auth/recuperacao",Map.of("email",email));
        String token=token(receber());
        jdbc.update("UPDATE password_recovery SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE conta_id=?",conta);
        assertThat(reset(token,"OutraSenhaSegura789!").body()).contains("TOKEN_INVALIDO");
        assertThat(reset("token-desconhecido","OutraSenhaSegura789!").body()).contains("TOKEN_INVALIDO");
        assertThat(login(ANTIGA).statusCode()).isEqualTo(200);
    }
    @Test void pedidosRepetidosNaoDuplicamEntregaSmtp() throws Exception {
        var primeira=post("/auth/recuperacao",Map.of("email",email)); receber();
        assertThat(post("/auth/recuperacao",Map.of("email",email)).body()).isEqualTo(primeira.body());
        assertThat(mensagensPara(ASSUNTO_RECUPERACAO)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_recovery WHERE conta_id=?",Integer.class,conta)).isEqualTo(1);
    }
    @Test void acentosRespeitamBytesUtf8SemConsumirTokenEmSenhaInvalida() throws Exception {
        post("/auth/recuperacao",Map.of("email",email)); String token=token(receber());
        for(String senha:List.of("a".repeat(11),"é".repeat(37),"😀".repeat(11),"😀".repeat(19),"a".repeat(12)+"\n")) {
            var resposta=reset(token,senha);
            assertThat(resposta.statusCode()).isEqualTo(400);
            assertThat(resposta.body()).contains("REQUISICAO_INVALIDA");
        }
        String nova="é".repeat(36);
        assertThat(reset(token,nova).statusCode()).isEqualTo(200);
        assertThat(login(nova).statusCode()).isEqualTo(200);
        assertThat(login(ANTIGA).statusCode()).isEqualTo(401);
    }
    @Test void dezoitoEmojisSaoSetentaEDoisBytesEAceitosNoLogin() throws Exception {
        post("/auth/recuperacao",Map.of("email",email)); String token=token(receber());
        String nova="😀".repeat(18);
        assertThat(reset(token,nova).statusCode()).isEqualTo(200);
        assertThat(login(nova).statusCode()).isEqualTo(200);
    }
    @Test void dtoDeRedefinicaoNaoExibeTokenOuSenhaEmLogs() {
        assertThat(new RecoveryController.Reset("token-sintetico","senha-sintetica").toString()).isEqualTo("Reset[REDACTED]");
    }
    static final String ASSUNTO_RECUPERACAO="Redefina sua senha no Balaio";
    JsonNode receber() throws Exception {
        return aguardar(ASSUNTO_RECUPERACAO);
    }
    JsonNode aguardar(String assunto) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline) {
            var mensagens=mensagensPara(assunto);
            if(!mensagens.isEmpty()) return mail("GET","/api/v1/message/"+mensagens.get(0).get("ID").asText());
            Thread.sleep(100);
        }
        throw new AssertionError("E-mail não recebido pelo Mailpit de teste: "+assunto);
    }
    List<JsonNode> mensagensPara(String assunto) throws Exception {
        var mensagens=mail("GET","/api/v1/messages").get("messages");
        var encontradas=new java.util.ArrayList<JsonNode>();
        if(mensagens==null) return encontradas;
        for(JsonNode mensagem:mensagens) {
            boolean paraConta=false;
            for(JsonNode destino:mensagem.get("To")) paraConta|=email.equals(destino.get("Address").asText());
            if(paraConta && assunto.equals(mensagem.get("Subject").asText())) encontradas.add(mensagem);
        }
        return encontradas;
    }
    String token(JsonNode mensagem) {
        String text=mensagem.get("Text").asText();
        String link=Arrays.stream(text.split("\\s+")).filter(part->part.startsWith("https://")).findFirst().orElse("");
        URI uri=URI.create(link);
        assertThat((BASE.replaceAll("/+$","")+"/login/reset").equals(link.split("\\?",2)[0])).isTrue();
        assertThat(uri.getRawQuery()!=null && uri.getRawQuery().startsWith("token=")).isTrue();
        String token=uri.getRawQuery().substring("token=".length());
        assertThat(token.matches("[A-Za-z0-9_-]{43}")).isTrue();
        return token;
    }
    JsonNode mail(String method,String path) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://"+MAIL.getHost()+":"+MAIL.getMappedPort(8025)+path))
                .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build();
        var response=HTTP.send(req,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200,299);
        return method.equals("DELETE") || response.body().isBlank()?JSON.nullNode():JSON.readTree(response.body());
    }
    HttpResponse<String> login(String senha) throws Exception {return post("/auth/login",Map.of("email",email,"senha",senha));}
    HttpResponse<String> reset(String token,String senha) throws Exception {return post("/auth/redefinicao",Map.of("token",token,"novaSenha",senha));}
    HttpResponse<String> post(String path,Object body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return HTTP.send(req,HttpResponse.BodyHandlers.ofString());
    }
}
