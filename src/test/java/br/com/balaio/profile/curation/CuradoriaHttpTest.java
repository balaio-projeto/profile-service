package br.com.balaio.profile.curation;

import br.com.balaio.profile.AutenticacaoTestFixtures;
import br.com.balaio.profile.PostgreSQLTestConfiguration;
import br.com.balaio.profile.SessaoJwtTestFixtures;
import br.com.balaio.profile.account.application.port.in.InicializarAdministradorUseCase;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** HTTP real e Postgres Testcontainers. O servidor de catálogo exercita o contrato HTTP de leitura. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class CuradoriaHttpTest {
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String HASH="{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("SenhaDeTeste123!");
    static final AtomicInteger publicadas=new AtomicInteger(1), chamadas=new AtomicInteger();
    static volatile int statusCatalogo=200;
    static volatile String corpoCatalogo;
    static volatile String bearerCatalogo;
    static volatile long atrasoCorpo;
    static final HttpServer CATALOGO=iniciarCatalogo();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(enforceOverride=true) InicializarAdministradorUseCase bootstrap;
    UUID dono,outro,comprador,admin,admin2,perfil;
    String slug;
    final Map<UUID,String> papeis=new HashMap<>();

    static HttpServer iniciarCatalogo() {
        try {
            var servidor=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            servidor.createContext("/api/v1/artesaos/",exchange->{
                chamadas.incrementAndGet();
                bearerCatalogo=exchange.getRequestHeaders().getFirst("Authorization");
                String artesao=exchange.getRequestURI().getPath().split("/")[4];
                var pecas=new ArrayList<Map<String,Object>>();
                for(int i=0;i<publicadas.get();i++) pecas.add(Map.of("slug","peca-"+i,"artesao",artesao,"situacao","publicada"));
                // Defesa adicional: rascunhos e peças de outra loja não contam.
                pecas.add(Map.of("slug","rascunho","artesao",artesao,"situacao","rascunho"));
                pecas.add(Map.of("slug","outra-loja","artesao","outro","situacao","publicada"));
                pecas.add(Map.of("slug","inativa","artesao",artesao,"situacao","publicada","inativadoEm",Instant.now().toString()));
                String body=corpoCatalogo!=null?corpoCatalogo:JSON.writeValueAsString(new Envelope(pecas,null));
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(statusCatalogo,bytes.length);
                if(atrasoCorpo>0) {
                    try { Thread.sleep(atrasoCorpo); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });
            servidor.start(); return servidor;
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    record Envelope(Object dados,Object erro) { }
    @DynamicPropertySource static void catalogo(DynamicPropertyRegistry registry) {
        registry.add("balaio.catalog.url",()->"http://127.0.0.1:"+CATALOGO.getAddress().getPort());
    }
    @AfterAll static void pararCatalogo(){CATALOGO.stop(0);}
    @BeforeEach void preparar() {
        publicadas.set(1); chamadas.set(0); statusCatalogo=200; corpoCatalogo=null; bearerCatalogo=null; atrasoCorpo=0;
        dono=conta("artesao"); outro=conta("artesao"); comprador=conta("comprador"); admin=conta("admin"); admin2=conta("admin");
        perfil=UUID.randomUUID(); slug="artesao-"+dono;
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica,imagem) VALUES (?,?,?,?,?,?,?,?,?)",
                perfil,dono,slug,"Artista","Ateliê teste","História preservada","Pajeú","Xilogravura","/foto-preservada.webp");
    }
    @AfterEach void limpar() {
        jdbc.update("DELETE FROM solicitacoes_selo WHERE perfil_id IN (SELECT id FROM perfis_artesao WHERE conta_id IN (?,?))",dono,outro);
        jdbc.update("DELETE FROM contas WHERE id IN (?,?,?,?,?)",dono,outro,comprador,admin,admin2);
    }
    @Test void cicloCompletoPersisteHistoricoSeloEPerfilSemAlterarFoto() throws Exception {
        var antes=dados(enviar("GET","/artesaos/"+slug,null,null,200));
        assertThat(dados(enviar("GET","/artesao/selo",null,dono,200)).get("solicitacao").isNull()).isTrue();
        var solicitacao=solicitar(dono,"Mensagem original");
        String id=solicitacao.get("id").asText();
        var fila=dados(enviar("GET","/admin/curadoria",null,admin,200));
        assertThat(fila.size()).isEqualTo(1);
        assertThat(fila.get(0).get("pecasPublicadas").asInt()).isEqualTo(1);
        assertThat(bearerCatalogo).isNull();
        decidir(id,"ajuste"," Melhore as fotos ",admin,200);
        var ajuste=dados(enviar("GET","/artesao/selo",null,dono,200)).get("solicitacao");
        assertThat(ajuste.get("motivo").asText()).isEqualTo("Melhore as fotos");
        assertThat(ajuste.get("situacao").asText()).isEqualTo("ajuste");
        assertThat(dados(enviar("GET","/admin/curadoria",null,admin,200)).size()).isZero();
        String novoId=solicitar(dono,"Fotos corrigidas").get("id").asText();
        assertThat(novoId).isNotEqualTo(id);
        decidir(id,"aprovada","",admin,409);
        decidir(novoId,"aprovada","",admin,200);
        var estado=dados(enviar("GET","/artesao/selo",null,dono,200));
        assertThat(estado.get("selo").asBoolean()).isTrue();
        assertThat(estado.get("solicitacao").get("situacao").asText()).isEqualTo("aprovada");
        assertThat(dados(enviar("GET","/admin/curadoria",null,admin,200)).size()).isZero();
        var depois=dados(enviar("GET","/artesaos/"+slug,null,null,200));
        for(String campo:List.of("nome","atelie","historia","territorio","tecnica","imagem")) assertThat(depois.get(campo)).isEqualTo(antes.get(campo));
        assertThat(depois.get("selo").asBoolean()).isTrue();
        assertThat(dados(enviar("GET","/artesao/me",null,dono,200)).get("selo").asBoolean()).isTrue();
        assertThat(dados(enviar("GET","/artesaos",null,null,200)).toString()).contains("\"selo\":true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM solicitacoes_selo WHERE perfil_id=?",Integer.class,perfil)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT motivo FROM solicitacoes_selo WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("Melhore as fotos");
        enviar("POST","/artesao/selo",Map.of("mensagem","Duplicada após selo"),dono,409);
    }
    @Test void repeticaoPreservaDataAutorEContagemMesmoSeCatalogoCair() throws Exception {
        String id=solicitar(dono,"").get("id").asText();
        var primeira=dados(decidir(id,"aprovada","",admin,200));
        int consultas=chamadas.get(); statusCatalogo=503;
        var repetida=dados(decidir(id,"aprovada","",admin2,200));
        assertThat(repetida).isEqualTo(primeira);
        assertThat(chamadas.get()).isEqualTo(consultas);
        assertThat(jdbc.queryForObject("SELECT decidido_por FROM solicitacoes_selo WHERE id=?",UUID.class,UUID.fromString(id))).isEqualTo(admin);
        decidir(id,"ajuste","Outra decisão",admin2,409);
    }
    @Test void ajusteRepetidoMesmoMotivoEIdNaoDuplicamEfeito() throws Exception {
        String id=solicitar(dono,"Teste").get("id").asText();
        var primeira=dados(decidir(id,"ajuste","Ajustar foto",admin,200));
        assertThat(dados(decidir(id,"ajuste"," Ajustar foto ",admin2,200))).isEqualTo(primeira);
        decidir(id,"ajuste","Motivo diferente",admin2,409);
    }
    @Test void decisoesConcorrentesDeAdminsDiferentesTemUmVencedor() throws Exception {
        String id=solicitar(dono,"Concorrência").get("id").asText();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->decidir(id,"aprovada","",admin,-1));
            var b=executor.submit(()->decidir(id,"ajuste","Conferir origem",admin2,-1));
            assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        String situacao=jdbc.queryForObject("SELECT situacao FROM solicitacoes_selo WHERE id=?",String.class,UUID.fromString(id));
        assertThat(dados(enviar("GET","/artesaos/"+slug,null,null,200)).get("selo").asBoolean()).isEqualTo("aprovada".equals(situacao));
    }
    @Test void aprovacoesConcorrentesIdenticasRetornamMesmoResultado() throws Exception {
        String id=solicitar(dono,"Concorrência").get("id").asText();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->decidir(id,"aprovada","",admin,200));
            var b=executor.submit(()->decidir(id,"aprovada","",admin2,200));
            assertThat(dados(a.get())).isEqualTo(dados(b.get()));
        }
        assertThat(chamadas.get()).isEqualTo(1);
    }
    @Test void solicitacoesConcorrentesNaoDuplicam() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->enviar("POST","/artesao/selo",Map.of("mensagem","A"),dono,-1));
            var b=executor.submit(()->enviar("POST","/artesao/selo",Map.of("mensagem","B"),dono,-1));
            assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM solicitacoes_selo WHERE perfil_id=?",Integer.class,perfil)).isEqualTo(1);
    }
    @Test void posseEPapeisSaoValidadosLocalmente() throws Exception {
        String id=solicitar(dono,"Mensagem privada").get("id").asText();
        var estadoOutro=dados(enviar("GET","/artesao/selo",null,outro,200));
        assertThat(estadoOutro.get("solicitacao").isNull()).isTrue();
        assertThat(estadoOutro.toString()).doesNotContain("Mensagem privada",id);
        for(UUID ator:List.of(outro,comprador)) {
            enviar("GET","/admin/curadoria",null,ator,403);
            decidir(id,"aprovada","",ator,403);
        }
        enviar("GET","/artesao/selo",null,comprador,403);
        enviar("POST","/artesao/selo",Map.of("mensagem","Forjada"),comprador,403);
        enviar("POST","/artesao/selo",Map.of("mensagem","Forjada","artesaoSlug",slug),outro,400);
        for(String papel:List.of("admin","artesao")) {
            var req=HttpRequest.newBuilder(uri("/admin/curadoria")).header("Authorization","Bearer "+token(outro,papel));
            assertThat(HTTP.send(req.GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        jdbc.update("UPDATE contas SET troca_senha_obrigatoria=true WHERE id=?",admin);
        enviar("GET","/admin/curadoria",null,admin,403);
        var pendente=HttpRequest.newBuilder(uri("/admin/curadoria")).header("Authorization","Bearer "+token(admin,"admin")).GET().build();
        assertThat(HTTP.send(pendente,HttpResponse.BodyHandlers.ofString()).body()).contains("TROCA_SENHA_OBRIGATORIA");
        var outroAdmin=HttpRequest.newBuilder(uri("/admin/curadoria")).header("Authorization","Bearer "+token(outro,"admin")).GET().build();
        assertThat(HTTP.send(outroAdmin,HttpResponse.BodyHandlers.ofString()).body()).doesNotContain("TROCA_SENHA_OBRIGATORIA");
        decidir(id,"aprovada","",admin,403);
    }
    @Test void semSessaoOuJwtInvalidoRetorna401() throws Exception {
        for(String path:List.of("/artesao/selo","/admin/curadoria")) {
            enviar("GET",path,null,null,401);
            var req=HttpRequest.newBuilder(uri(path)).header("Authorization","Bearer invalido").GET().build();
            assertThat(HTTP.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        enviar("POST","/artesao/selo",Map.of("mensagem",""),null,401);
        decidir(UUID.randomUUID().toString(),"aprovada","",null,401);
    }
    @ParameterizedTest @ValueSource(strings={"{\"mensagem\":null}","{\"mensagem\":123}","{\"mensagem\":true}","{\"id\":\"forjado\"}","{\"papel\":\"admin\"}","[]","{bad"})
    void mensagemInvalidaNaoPersiste(String body) throws Exception {
        enviarRaw("POST","/artesao/selo",body,dono,400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM solicitacoes_selo WHERE perfil_id=?",Integer.class,perfil)).isZero();
    }
    @Test void limitesMensagemEMotivoSaoAplicadosNoServidor() throws Exception {
        enviar("POST","/artesao/selo",Map.of("mensagem","x".repeat(1001)),dono,400);
        enviar("POST","/artesao/selo",Map.of("mensagem","a\u0000b"),dono,400);
        String id=solicitar(dono,"x".repeat(1000)).get("id").asText();
        decidir(id,"ajuste","x".repeat(1001),admin,400);
        decidir(id,"ajuste","x".repeat(1000),admin,200);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"decisao\":null}","{\"decisao\":\"recusada\"}","{\"decisao\":\"ajuste\"}","{\"decisao\":\"ajuste\",\"motivo\":\" \"}","{\"decisao\":\"ajuste\",\"motivo\":7}","{\"decisao\":\"aprovada\",\"motivo\":\"muda\"}","{\"decisao\":\"aprovada\",\"papel\":\"admin\"}"})
    void decisaoInvalidaMantemPendente(String body) throws Exception {
        String id=solicitar(dono,"").get("id").asText();
        enviarRaw("POST","/admin/curadoria/"+id+"/decisao",body,admin,400);
        assertThat(jdbc.queryForObject("SELECT situacao FROM solicitacoes_selo WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("pendente");
    }
    @Test void semPecasOuFalhaCatalogoNaoConcedemSeloNemSimulamContagem() throws Exception {
        String id=solicitar(dono,"").get("id").asText();
        publicadas.set(0);
        assertThat(dados(enviar("GET","/admin/curadoria",null,admin,200)).get(0).get("pecasPublicadas").asInt()).isZero();
        decidir(id,"aprovada","",admin,409);
        statusCatalogo=503;
        enviar("GET","/admin/curadoria",null,admin,503);
        decidir(id,"aprovada","",admin,503);
        statusCatalogo=200;
        for(String body:List.of("{}","{\"dados\":[],\"erro\":{\"codigo\":\"FALHA\"}}","{\"dados\":[{}],\"erro\":null}","nao-json")) {
            corpoCatalogo=body;
            decidir(id,"aprovada","",admin,503);
        }
        assertThat(dados(enviar("GET","/artesao/selo",null,dono,200)).get("selo").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT situacao FROM solicitacoes_selo WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("pendente");
    }
    @Test void origemMaliciosaEIdsInvalidosSaoRecusados() throws Exception {
        var request=HttpRequest.newBuilder(uri("/artesao/selo")).header("Content-Type","application/json")
                .header("Authorization","Bearer "+token(dono,"artesao")).header("Origin","https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        assertThat(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        decidir("invalido","aprovada","",admin,400);
        decidir(UUID.randomUUID().toString(),"aprovada","",admin,404);
    }
    @Test void catalogoComCorpoTravadoTemPrazoLimitadoENaoAprova() throws Exception {
        String id=solicitar(dono,"").get("id").asText();
        atrasoCorpo=6500;
        long inicio=System.nanoTime();
        decidir(id,"aprovada","",admin,503);
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-inicio).toSeconds()).isLessThan(8);
        assertThat(dados(enviar("GET","/artesao/selo",null,dono,200)).get("selo").asBoolean()).isFalse();
    }
    UUID conta(String papel) {
        UUID id=UUID.randomUUID(); papeis.put(id,papel);
        jdbc.update("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)",id,"Conta curadoria",id+"@example.test",HASH,papel);
        return id;
    }
    JsonNode solicitar(UUID ator,String mensagem) throws Exception { return dados(enviar("POST","/artesao/selo",Map.of("mensagem",mensagem),ator,200)).get("solicitacao"); }
    HttpResponse<String> decidir(String id,String decisao,String motivo,UUID ator,int expected) throws Exception {
        return enviar("POST","/admin/curadoria/"+id+"/decisao",Map.of("decisao",decisao,"motivo",motivo),ator,expected);
    }
    URI uri(String path){return URI.create("http://localhost:"+port+"/api/v1"+path);}
    String token(UUID id,String papel) { var claims=SessaoJwtTestFixtures.claims(id,Instant.now()); claims.put("papel",papel); return SessaoJwtTestFixtures.token(claims); }
    HttpResponse<String> enviar(String metodo,String path,Object body,UUID ator,int expected) throws Exception {
        return enviarRaw(metodo,path,body==null?null:JSON.writeValueAsString(body),ator,expected);
    }
    HttpResponse<String> enviarRaw(String metodo,String path,String body,UUID ator,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
        if(ator!=null) builder.header("Authorization","Bearer "+token(ator,papeis.get(ator)));
        var response=HTTP.send(builder.method(metodo,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if(expected>=0) assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        assertThat(response.body()).doesNotContain("senha_hash","stackTrace","org.postgresql","decidido_por","perfil_id");
        if(path.startsWith("/artesao/selo") || path.startsWith("/admin/curadoria")) assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return response;
    }
    JsonNode dados(HttpResponse<String> response){return JSON.readTree(response.body()).get("dados");}
}
