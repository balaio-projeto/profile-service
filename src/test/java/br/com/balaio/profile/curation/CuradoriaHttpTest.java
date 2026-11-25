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
    static final List<String> metodosCatalogo=Collections.synchronizedList(new ArrayList<>());
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
                metodosCatalogo.add(exchange.getRequestMethod());
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
        metodosCatalogo.clear();
        dono=conta("artesao"); outro=conta("artesao"); comprador=conta("comprador"); admin=conta("admin"); admin2=conta("admin");
        perfil=UUID.randomUUID(); slug="artesao-"+dono;
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica,imagem) VALUES (?,?,?,?,?,?,?,?,?)",
                perfil,dono,slug,"Artista","Ateliê teste","História preservada","Pajeú","Xilogravura","/foto-preservada.webp");
    }
    @AfterEach void limpar() {
        for(UUID id:papeis.keySet()) jdbc.update("DELETE FROM solicitacoes_selo WHERE perfil_id IN (SELECT id FROM perfis_artesao WHERE conta_id=?)",id);
        for(UUID id:papeis.keySet()) jdbc.update("DELETE FROM contas WHERE id=?",id);
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
    /** TC12 (TS04): selo aprovado e ajuste com motivo não publicam, ocultam nem alteram peças no catálogo. */
    @Test void decisoesDeSeloNaoEscrevemNoCatalogoNemMudamPecas() throws Exception {
        String ajuste=solicitar(dono,"Pedido com peça publicada").get("id").asText();
        decidir(ajuste,"ajuste","Melhore as fotos",admin,200);
        String aprovado=solicitar(dono,"Fotos corrigidas").get("id").asText();
        decidir(aprovado,"aprovada","",admin,200);
        var fila=dados(enviar("GET","/admin/curadoria",null,admin,200));
        assertThat(fila.size()).isZero();
        assertThat(metodosCatalogo).isNotEmpty().allMatch("GET"::equals);
        assertThat(publicadas.get()).isEqualTo(1);
        assertThat(dados(enviar("GET","/artesao/selo",null,dono,200)).get("selo").asBoolean()).isTrue();
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
    @Test void filaSemParametrosContinuaComPendentesPaginadosEContaSoAPagina() throws Exception {
        var pendentes=new ArrayList<String>();
        for(int i=0;i<12;i++) pendentes.add(solicitacaoEm(artesao("Pessoa "+i,"Ateliê "+i),"pendente",Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i*60L),null));
        chamadas.set(0);
        var resposta=JSON.readTree(enviar("GET","/admin/curadoria",null,admin,200).body());
        assertThat(resposta.get("erro").isNull()).isTrue();
        assertThat(ids(resposta.get("dados"))).containsExactlyElementsOf(pendentes.subList(0,10));
        assertThat(paginacao(resposta)).containsExactly(1,10,12,2);
        assertThat(chamadas.get()).isEqualTo(10);
        var segunda=JSON.readTree(enviar("GET","/admin/curadoria?pagina=2",null,admin,200).body());
        assertThat(ids(segunda.get("dados"))).containsExactlyElementsOf(pendentes.subList(10,12));
        assertThat(paginacao(segunda)).containsExactly(2,10,12,2);
        assertThat(paginacao(JSON.readTree(enviar("GET","/admin/curadoria?pagina=99&tamanho=5",null,admin,200).body()))).containsExactly(3,5,12,3);
        assertThat(paginacao(JSON.readTree(enviar("GET","/admin/curadoria?tamanho=500",null,admin,200).body()))).containsExactly(1,50,12,1);
        int chamadasAntes=chamadas.get();
        var somenteTotal=JSON.readTree(enviar("GET","/admin/curadoria?situacao=pendente&tamanho=1&apenasTotal=true",null,admin,200).body());
        assertThat(somenteTotal.get("dados")).isEmpty();
        assertThat(paginacao(somenteTotal)).containsExactly(1,1,12,12);
        assertThat(chamadas.get()).isEqualTo(chamadasAntes);
    }
    @Test void filaFiltraPorSituacaoEOrdenaDecididasPelaDecisaoMaisRecente() throws Exception {
        UUID ana=artesao("Ana Rendeira","Casa da Renda"), bia=artesao("Bia Barro","Barro Vivo"), caio=artesao("Caio Couro","Couro Fino");
        String pendente=solicitacaoEm(ana,"pendente",Instant.parse("2026-03-01T00:00:00Z"),null);
        String ajusteAntigo=solicitacaoEm(bia,"ajuste",Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-05T00:00:00Z"));
        String ajusteRecente=solicitacaoEm(caio,"ajuste",Instant.parse("2026-01-02T00:00:00Z"),Instant.parse("2026-02-05T00:00:00Z"));
        String aprovada=solicitacaoEm(bia,"aprovada",Instant.parse("2026-02-01T00:00:00Z"),Instant.parse("2026-02-10T00:00:00Z"));
        chamadas.set(0);
        var ajustes=JSON.readTree(enviar("GET","/admin/curadoria?situacao=ajuste",null,admin,200).body());
        assertThat(ids(ajustes.get("dados"))).containsExactly(ajusteRecente,ajusteAntigo);
        assertThat(ajustes.get("dados").get(0).get("motivo").asText()).isEqualTo("Melhore as fotos");
        assertThat(ajustes.get("dados").get(0).get("pecasPublicadas").asInt()).isEqualTo(3);
        assertThat(ajustes.get("dados").get(0).get("decididoEm").asText()).isEqualTo("2026-02-05T00:00:00Z");
        assertThat(chamadas.get()).isZero();
        assertThat(ids(dados(enviar("GET","/admin/curadoria?situacao=aprovada",null,admin,200)))).containsExactly(aprovada);
        var todas=JSON.readTree(enviar("GET","/admin/curadoria?situacao=todas",null,admin,200).body());
        assertThat(ids(todas.get("dados"))).containsExactly(pendente,aprovada,ajusteRecente,ajusteAntigo);
        assertThat(paginacao(todas)).containsExactly(1,10,4,1);
        assertThat(ids(dados(enviar("GET","/admin/curadoria?situacao=pendente",null,admin,200)))).containsExactly(pendente);
        enviar("GET","/admin/curadoria?situacao=recusada",null,admin,400);
        enviar("GET","/admin/curadoria?pagina=abc",null,admin,400);
        enviar("GET","/admin/curadoria?situacao=todas",null,comprador,403);
        enviar("GET","/admin/curadoria?situacao=todas",null,null,401);
    }
    @Test void buscaPorAtelieOuArtesaoSemDiferenciarCaixaEComCuringasLiterais() throws Exception {
        String renda=solicitacaoEm(artesao("Ana Rendeira","Casa da Renda"),"pendente",Instant.parse("2026-01-01T00:00:00Z"),null);
        String barro=solicitacaoEm(artesao("Bia Barro","Barro_Vivo 100%"),"pendente",Instant.parse("2026-01-02T00:00:00Z"),null);
        String ajuste=solicitacaoEm(artesao("Ana Ceramista","Forno Aceso"),"ajuste",Instant.parse("2026-01-03T00:00:00Z"),Instant.parse("2026-01-04T00:00:00Z"));
        assertThat(ids(dados(enviar("GET","/admin/curadoria?busca=CASA%20DA",null,admin,200)))).containsExactly(renda);
        assertThat(ids(dados(enviar("GET","/admin/curadoria?busca=bia",null,admin,200)))).containsExactly(barro);
        assertThat(ids(dados(enviar("GET","/admin/curadoria?situacao=todas&busca=%20ana%20",null,admin,200)))).containsExactly(renda,ajuste);
        var percentual=JSON.readTree(enviar("GET","/admin/curadoria?busca=%25",null,admin,200).body());
        assertThat(ids(percentual.get("dados"))).containsExactly(barro);
        assertThat(paginacao(percentual)).containsExactly(1,10,1,1);
        assertThat(ids(dados(enviar("GET","/admin/curadoria?busca=o_V",null,admin,200)))).containsExactly(barro);
        var vazia=JSON.readTree(enviar("GET","/admin/curadoria?busca=inexistente",null,admin,200).body());
        assertThat(vazia.get("dados").size()).isZero();
        assertThat(paginacao(vazia)).containsExactly(1,10,0,1);
        enviar("GET","/admin/curadoria?busca="+"x".repeat(101),null,admin,400);
    }
    UUID artesao(String nome,String atelie) {
        UUID id=conta("artesao"), perfilId=UUID.randomUUID();
        jdbc.update("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,historia,territorio,tecnica,imagem) VALUES (?,?,?,?,?,?,?,?,?)",
                perfilId,id,"artesao-"+id,nome,atelie,"","Agreste","Cerâmica","/foto.webp");
        return perfilId;
    }
    String solicitacaoEm(UUID perfilId,String situacao,Instant solicitadoEm,Instant decididoEm) {
        UUID id=UUID.randomUUID();
        boolean decidida=!"pendente".equals(situacao);
        jdbc.update("INSERT INTO solicitacoes_selo(id,perfil_id,mensagem,situacao,solicitado_em,motivo,decidido_em,decidido_por,pecas_publicadas_decisao) VALUES (?,?,?,?,?,?,?,?,?)",
                id,perfilId,"Mensagem",situacao,java.sql.Timestamp.from(solicitadoEm),"ajuste".equals(situacao)?"Melhore as fotos":null,
                decidida?java.sql.Timestamp.from(decididoEm):null,decidida?admin:null,decidida?3:null);
        return id.toString();
    }
    static List<String> ids(JsonNode dados) {
        var ids=new ArrayList<String>();
        dados.forEach(item->ids.add(item.get("id").asText()));
        return ids;
    }
    static List<Integer> paginacao(JsonNode resposta) {
        var p=resposta.get("paginacao");
        return List.of(p.get("pagina").asInt(),p.get("tamanho").asInt(),p.get("total").asInt(),p.get("totalPaginas").asInt());
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
