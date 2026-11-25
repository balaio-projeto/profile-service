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
    @Test void enderecosSaoPrivadosEPrincipalSegueExclusao() throws Exception {
        String corpo="{\"apelido\":\"Casa\",\"rua\":\"Rua A, 1\",\"bairro\":\"Centro, Recife, PE\",\"cep\":\"50000-000\"}";
        var criado=enviar("POST","/usuario/enderecos",corpo,comprador);
        assertStatus(criado,200);
        assertStatus(enviar("POST","/usuario/enderecos","{\"apelido\":\"Sem cidade\",\"rua\":\"Rua B, 2\",\"bairro\":\"Centro\",\"cep\":\"50000-000\"}",comprador),400);
        String id=JSON.readTree(criado.body()).get("dados").get("id").asText();
        assertThat(criado.body()).contains("\"principal\":true");
        assertThat(enviar("GET","/usuario/enderecos",null,comprador).body()).contains(id);
        assertThat(enviar("GET","/usuario",null,comprador).body()).doesNotContain("enderecos");
        assertStatus(enviar("GET","/usuario/enderecos/"+id,null,outro),404);
        assertStatus(enviar("GET","/usuario/enderecos/"+id,null,null),401);
        assertStatus(enviar("DELETE","/usuario/enderecos/"+id,null,comprador),200);
        assertThat(enviar("GET","/usuario/enderecos",null,comprador).body()).contains("\"dados\":[]");
    }
    @Test void artesaoCadastraEnderecoDeEntregaEOrganizadorEAdminNao() throws Exception {
        var criado=enviar("POST","/usuario/enderecos","{\"apelido\":\"Ateliê\",\"rua\":\"Rua C, 3\",\"bairro\":\"Centro, Olinda, PE\",\"cep\":\"53000-000\"}",dono);
        assertStatus(criado,200);
        String id=JSON.readTree(criado.body()).get("dados").get("id").asText();
        assertThat(enviar("GET","/usuario/enderecos",null,dono).body()).contains(id,"Rua C, 3");
        assertStatus(enviar("GET","/usuario/enderecos/"+id,null,dono),200);
        assertStatus(enviar("GET","/usuario/enderecos/"+id,null,comprador),404);
        assertThat(enviar("GET","/usuario/enderecos",null,outro).body()).doesNotContain(id);
        for(String papel:List.of("organizador","admin")) {
            UUID conta=criar(papel);
            try {
                var recusa=enviar("GET","/usuario/enderecos",null,conta);
                assertStatus(recusa,403);
                assertStatus(enviar("POST","/usuario/enderecos","{\"apelido\":\"Casa\",\"rua\":\"Rua D, 4\",\"bairro\":\"Centro, Recife, PE\",\"cep\":\"50000-000\"}",conta),403);
            } finally {
                jdbc.update("DELETE FROM contas WHERE id=?",conta);
            }
        }
    }
    @Test void enderecoGuardaNumeroEComplementoSeparadosDaRua() throws Exception {
        var criado=enviar("POST","/usuario/enderecos","{\"apelido\":\"Casa\",\"rua\":\"Rua da Aurora\",\"numero\":\"240\",\"complemento\":\"Apto 302\",\"bairro\":\"Boa Vista, Recife, PE\",\"cep\":\"50050-000\"}",comprador);
        assertStatus(criado,200);
        var dados=JSON.readTree(criado.body()).get("dados");
        assertThat(dados.get("rua").asText()).isEqualTo("Rua da Aurora");
        assertThat(dados.get("numero").asText()).isEqualTo("240");
        assertThat(dados.get("complemento").asText()).isEqualTo("Apto 302");
        String id=dados.get("id").asText();
        var semComplemento=enviar("PATCH","/usuario/enderecos/"+id,"{\"complemento\":\"\"}",comprador);
        assertStatus(semComplemento,200);
        var atualizado=JSON.readTree(semComplemento.body()).get("dados");
        assertThat(atualizado.get("numero").asText()).isEqualTo("240");
        assertThat(atualizado.get("complemento").isNull()).isTrue();
        assertStatus(enviar("PATCH","/usuario/enderecos/"+id,"{\"numero\":\""+"9".repeat(21)+"\"}",comprador),400);
        assertStatus(enviar("PATCH","/usuario/enderecos/"+id,"{\"numero\":240}",comprador),400);
        var semNumero=enviar("POST","/usuario/enderecos","{\"apelido\":\"Antigo\",\"rua\":\"Rua B, 2\",\"bairro\":\"Centro, Recife, PE\",\"cep\":\"50000-000\"}",comprador);
        assertStatus(semNumero,200);
        assertThat(JSON.readTree(semNumero.body()).get("dados").get("numero").isNull()).isTrue();
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride=true) InicializarAdministradorUseCase admin;
    UUID dono, outro, comprador, comprador2, perfil;
    String slug;

    @BeforeEach void preparar() {
        dono=criar("artesao"); outro=criar("artesao"); comprador=criar("comprador"); comprador2=criar("comprador");
        perfil=UUID.randomUUID(); slug="artesao-"+dono;
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica) VALUES (?,?,?,?,?,?,?,?)",
                perfil,dono,slug,"Nome artístico","Ateliê cultural","História preservada","Pajeú","Xilogravura");
    }
    @Test void coletivoSemMembrosNemPecasRetornaContratoPublico() throws Exception {
        assertThat(enviar("GET","/coletivos",null,null).body()).contains("\"dados\":[]");
        UUID id=UUID.randomUUID();
        String identificador="coletivo-"+id.toString().substring(0,8);
        jdbc.update("INSERT INTO coletivos(id,slug,nome,territorio,fundado,historia) VALUES (?,?,?,?,?,?)",
                id,identificador,"Coletivo de teste","Pajeú","2024","História");
        try {
            var resposta=enviar("GET","/coletivos/"+identificador,null,null);
            assertStatus(resposta,200);
            assertThat(resposta.body()).contains("\"membros\":[]","\"pecas\":[]","Coletivo de teste");
        } finally {
            jdbc.update("DELETE FROM coletivos WHERE id=?",id);
        }
    }
    @AfterEach void limpar() {
        jdbc.update("DELETE FROM contas WHERE id IN (?,?,?,?)",dono,outro,comprador,comprador2);
    }
    @Test void compradorNaoLeAlteraNemRemoveEnderecoDeOutroComprador() throws Exception {
        var criado=enviar("POST","/usuario/enderecos","{\"apelido\":\"Casa\",\"rua\":\"Rua A, 1\",\"bairro\":\"Centro, Recife, PE\",\"cep\":\"50000-000\"}",comprador);
        assertStatus(criado,200);
        String id=JSON.readTree(criado.body()).get("dados").get("id").asText();
        assertStatus(enviar("GET","/usuario/enderecos/"+id,null,comprador2),404);
        assertStatus(enviar("PATCH","/usuario/enderecos/"+id,"{\"rua\":\"Invadida, 9\"}",comprador2),404);
        assertStatus(enviar("DELETE","/usuario/enderecos/"+id,null,comprador2),404);
        assertThat(enviar("GET","/usuario/enderecos",null,comprador2).body()).doesNotContain(id,"Rua A");
        var atual=enviar("GET","/usuario/enderecos/"+id,null,comprador);
        assertStatus(atual,200);
        assertThat(atual.body()).contains("Rua A, 1").doesNotContain("Invadida");
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
        claims.put("papel",id.equals(comprador)||id.equals(comprador2)?"comprador":"artesao");
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
