package br.com.balaio.profile.settings;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class PreferenciasHttpTest {
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String SENHA="SenhaDeTeste123!";
    static final String HASH="{bcrypt}"+new BCryptPasswordEncoder(12).encode(SENHA);
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride=true) InicializarAdministradorUseCase admin;
    UUID dono, outro, comprador, perfil;
    String slug;

    @BeforeEach void preparar() {
        dono=criar("artesao"); outro=criar("artesao"); comprador=criar("comprador");
        perfil=UUID.randomUUID(); slug="artesao-"+dono;
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica) VALUES (?,?,?,?,?,?,?,?)",
                perfil,dono,slug,"Nome artístico","Ateliê cultural","História preservada","Pajeú","Xilogravura");
    }
    @AfterEach void limpar() {
        jdbc.update("DELETE FROM contas WHERE id IN (?,?,?)",dono,outro,comprador);
    }
    @Test void contaParcialPersisteSemAlterarPerfilCredenciaisOuPapel() throws Exception {
        assertStatus(enviar("PATCH","/conta/"+dono,"{\"nome\":\" Novo Nome \"}",dono),200);
        assertStatus(enviar("PATCH","/usuario","{\"email\":\"NOVO@EXAMPLE.TEST\"}",dono),200);
        var atual=enviar("GET","/usuario",null,dono);
        assertThat(atual.body()).contains("Novo Nome","novo@example.test").doesNotContain("senha_hash",HASH);
        assertThat(jdbc.queryForObject("SELECT nome FROM perfis_artesao WHERE id=?",String.class,perfil)).isEqualTo("Nome artístico");
        assertThat(jdbc.queryForObject("SELECT senha_hash FROM contas WHERE id=?",String.class,dono)).isEqualTo(HASH);
        assertThat(jdbc.queryForObject("SELECT papel FROM contas WHERE id=?",String.class,dono)).isEqualTo("artesao");
        assertStatus(login("novo@example.test",SENHA),200);
        assertStatus(login(dono+"@example.test",SENHA),401);
        assertThat(jdbc.queryForObject("SELECT nome FROM contas WHERE id=?",String.class,dono)).isEqualTo("Novo Nome");
    }
    @Test void compradorTambemEditaPropriaConta() throws Exception {
        assertStatus(enviar("PATCH","/usuario","{\"nome\":\"Comprador atualizado\"}",comprador),200);
    }
    @ParameterizedTest
    @ValueSource(strings={"{}","{\"nome\":\"\"}","{\"nome\":null}","{\"nome\":42}",
        "{\"email\":\"invalido\"}","{\"email\":null}","{\"papel\":\"admin\"}",
        "{\"id\":\"forjado\"}","{\"senha\":\"forjada\"}","{\"nome\":\"ok\",\"senha_hash\":\"x\"}"})
    void contaRejeitaCamposInvalidosOuPrivilegios(String body) throws Exception {
        assertStatus(enviar("PATCH","/usuario",body,dono),400);
        assertThat(jdbc.queryForObject("SELECT nome FROM contas WHERE id=?",String.class,dono)).isEqualTo("Conta teste");
    }
    @Test void emailDuplicadoENomeLongoRecusados() throws Exception {
        var duplicado=enviar("PATCH","/usuario","{\"email\":\""+outro+"@EXAMPLE.TEST\"}",dono);
        assertStatus(duplicado,409); assertThat(duplicado.body()).contains("EMAIL_EM_USO");
        assertStatus(enviar("PATCH","/usuario",JSON.writeValueAsString(Map.of("nome","x".repeat(241))),dono),400);
    }
    @Test void contaAlheiaOuSemSessaoRecusada() throws Exception {
        assertStatus(enviar("PATCH","/conta/"+outro,"{\"nome\":\"Invasor\"}",dono),403);
        assertStatus(enviar("PATCH","/usuario","{\"nome\":\"Invasor\"}",null),401);
    }
    @Test void emailsConcorrentesContinuamUnicos() throws Exception {
        String email="concorrente-"+UUID.randomUUID()+"@example.test";
        String body=JSON.writeValueAsString(Map.of("email",email));
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->enviar("PATCH","/usuario",body,dono));
            var b=executor.submit(()->enviar("PATCH","/usuario",body,outro));
            assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contas WHERE email=?",Integer.class,email)).isEqualTo(1);
    }
    @Test void trocaSenhaReutilizaCasoExistenteELoginMuda() throws Exception {
        assertStatus(enviar("POST","/auth/alterar-senha",JSON.writeValueAsString(Map.of("senhaAtual","incorreta","novaSenha","NovaSenhaDeTeste456!")),dono),400);
        assertStatus(enviar("POST","/auth/alterar-senha",JSON.writeValueAsString(Map.of("senhaAtual",SENHA,"novaSenha","NovaSenhaDeTeste456!")),dono),200);
        assertStatus(login(dono+"@example.test",SENHA),401);
        assertStatus(login(dono+"@example.test","NovaSenhaDeTeste456!"),200);
    }
    @Test void configuracoesPersistemPatchParcialEPixPermanecePrivado() throws Exception {
        var inicial=enviar("GET",config(),null,dono);
        assertStatus(inicial,200); assertThat(inicial.headers().firstValue("Cache-Control")).hasValue("no-store");
        String body="{\"cepOrigem\":\"55880000\",\"prazoPadraoDias\":15,\"aceitaEncomendas\":true,\"encomendasPausadas\":false,\"chavePix\":\" privada@example.test \"}";
        assertStatus(enviar("PATCH",config(),body,dono),200);
        assertStatus(enviar("PATCH",config(),"{\"encomendasPausadas\":true}",dono),200);
        var atual=JSON.readTree(enviar("GET",config(),null,dono).body()).get("dados");
        assertThat(atual.get("cepOrigem").asText()).isEqualTo("55880-000");
        assertThat(atual.get("prazoPadraoDias").asInt()).isEqualTo(15);
        assertThat(atual.get("aceitaEncomendas").asBoolean()).isTrue();
        assertThat(atual.get("encomendasPausadas").asBoolean()).isTrue();
        assertThat(atual.get("chavePix").asText()).isEqualTo("privada@example.test");
        assertThat(jdbc.queryForObject("SELECT chave_pix FROM configuracoes_atelie WHERE perfil_id=?",String.class,perfil)).isEqualTo("privada@example.test");
        assertThat(enviar("GET","/artesaos/"+slug,null,null).body()).doesNotContain("chavePix","privada@example.test","cepOrigem");
        assertThat(enviar("GET","/artesaos",null,null).body()).doesNotContain("chavePix","privada@example.test");
        assertThat(enviar("GET","/usuario",null,dono).body()).doesNotContain("chavePix","privada@example.test");
        assertThat(new ConfiguracoesAtelieService.Configuracoes("",1,false,false,"privada@example.test").toString()).doesNotContain("privada@example.test");
    }
    @ParameterizedTest
    @ValueSource(strings={"{}","{\"cepOrigem\":\"123\"}","{\"cepOrigem\":null}",
        "{\"prazoPadraoDias\":0}","{\"prazoPadraoDias\":121}","{\"prazoPadraoDias\":1.5}","{\"prazoPadraoDias\":\"10\"}",
        "{\"aceitaEncomendas\":\"true\"}","{\"encomendasPausadas\":null}","{\"chavePix\":\" \"}","{\"chavePix\":5}",
        "{\"nome\":\"Perfil cultural\"}"})
    void configuracoesValidamTodosOsCamposSemGravar(String body) throws Exception {
        assertStatus(enviar("PATCH",config(),body,dono),400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM configuracoes_atelie WHERE perfil_id=?",Integer.class,perfil)).isZero();
    }
    @Test void configuracoesExigemArtesaoDonoParaLerEEscrever() throws Exception {
        for (String method:List.of("GET","PATCH")) {
            String body=method.equals("PATCH")?"{\"prazoPadraoDias\":10}":null;
            assertStatus(enviar(method,config(),body,null),401);
            assertStatus(enviar(method,config(),body,outro),403);
            assertStatus(enviar(method,config(),body,comprador),403);
        }
    }
    @Test void mutacoesDiretasRejeitamOriginExterno() throws Exception {
        var req=HttpRequest.newBuilder(uri(config())).header("Authorization","Bearer "+token(dono))
                .header("Origin","https://evil.example").header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"prazoPadraoDias\":10}")).build();
        assertStatus(HTTP.send(req,HttpResponse.BodyHandlers.ofString()),403);
    }
    @Test void doisPatchesConcorrentesPreservamCamposDistintos() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->enviar("PATCH",config(),"{\"prazoPadraoDias\":25}",dono));
            var b=executor.submit(()->enviar("PATCH",config(),"{\"chavePix\":\"concorrente@example.test\"}",dono));
            assertStatus(a.get(),200); assertStatus(b.get(),200);
        }
        assertThat(enviar("GET",config(),null,dono).body()).contains("25","concorrente@example.test");
    }
    private UUID criar(String papel) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",id,"Conta teste",id+"@example.test",HASH,papel);
        return id;
    }
    private String config() { return "/artesaos/"+slug+"/configuracoes"; }
    private URI uri(String path) { return URI.create("http://localhost:"+port+"/api/v1"+path); }
    private String token(UUID id) {
        var claims=SessaoJwtTestFixtures.claims(id,Instant.now());
        claims.put("papel",id.equals(comprador)?"comprador":"artesao");
        return SessaoJwtTestFixtures.token(claims);
    }
    private HttpResponse<String> enviar(String method,String path,String body,UUID id) throws Exception {
        var req=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
        if(id!=null) req.header("Authorization","Bearer "+token(id));
        return HTTP.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> login(String email,String senha) throws Exception {
        return enviar("POST","/auth/login",JSON.writeValueAsString(Map.of("email",email,"senha",senha)),null);
    }
    private void assertStatus(HttpResponse<String> resposta,int status) {
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(status);
        assertThat(resposta.body()).doesNotContain(HASH,"stackTrace","org.postgresql");
    }
}
