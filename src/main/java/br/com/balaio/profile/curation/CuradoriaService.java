package br.com.balaio.profile.curation;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.JanelaPagina;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CuradoriaService {
    public record Solicitacao(UUID id, String artesaoSlug, String artesao, String atelie,
            String mensagem, String situacao, Instant solicitadoEm, String motivo, Instant decididoEm,
            String territorio, String tecnica, String imagem, Integer pecasPublicadas) { }
    public record Estado(boolean selo, Solicitacao solicitacao) { }
    public record PaginaFila(List<Solicitacao> itens, JanelaPagina janela) { }
    private static final Set<String> SITUACOES_FILA = Set.of("pendente", "ajuste", "aprovada", "todas");
    private static final String CONSULTA = """
            SELECT s.*, p.slug, p.nome, p.atelie, p.territorio, p.tecnica, p.imagem
            FROM solicitacoes_selo s JOIN perfis_artesao p ON p.id=s.perfil_id
            """;
    private final JdbcTemplate jdbc;
    private final ContasPort contas;
    private final PerfilArtesaoService perfis;
    private final AutorizarOperacaoAdministrativaUseCase admins;
    private final PecasPublicadasClient catalogo;

    public CuradoriaService(JdbcTemplate jdbc, ContasPort contas, PerfilArtesaoService perfis,
            AutorizarOperacaoAdministrativaUseCase admins, PecasPublicadasClient catalogo) {
        this.jdbc=jdbc; this.contas=contas; this.perfis=perfis; this.admins=admins; this.catalogo=catalogo;
    }

    @Transactional
    public Estado consultar(IdentidadeAutenticada identidade) {
        return estado(artesao(identidade).id());
    }

    @Transactional
    public Estado solicitar(IdentidadeAutenticada identidade, Map<String,Object> body) {
        var perfil = artesao(identidade);
        campos(body, Set.of("mensagem"));
        String mensagem = texto(body, "mensagem", false);
        var atual = estado(perfil.id());
        if (atual.selo()) throw CuradoriaException.conflito("O ateliê já possui o selo.");
        if (atual.solicitacao()!=null && "pendente".equals(atual.solicitacao().situacao())) {
            throw CuradoriaException.conflito("Já existe uma solicitação pendente.");
        }
        // Cada reenvio tem novo ID; uma decisão atrasada nunca decide a nova tentativa.
        jdbc.update("INSERT INTO solicitacoes_selo(id,perfil_id,mensagem,situacao) VALUES (?,?,?,'pendente')",
                UUID.randomUUID(), perfil.id(), mensagem);
        return estado(perfil.id());
    }

    public PaginaFila fila(IdentidadeAutenticada identidade, String situacao, String busca, Integer pagina, Integer tamanho) {
        return fila(identidade, situacao, busca, pagina, tamanho, false);
    }

    @Transactional
    public PaginaFila fila(IdentidadeAutenticada identidade, String situacao, String busca, Integer pagina, Integer tamanho,
            boolean apenasTotal) {
        admins.exigirPermissao(identidade);
        String filtro = situacao == null || situacao.isBlank() ? "pendente" : situacao.strip();
        if (!SITUACOES_FILA.contains(filtro)) throw CuradoriaException.invalido("situacao", "Informe pendente, ajuste, aprovada ou todas.");
        if (!JanelaPagina.buscaValida(busca)) throw CuradoriaException.invalido("busca", "Informe uma busca de até 100 caracteres.");
        var condicoes = new ArrayList<String>();
        var parametros = new ArrayList<Object>();
        if (!"todas".equals(filtro)) {
            condicoes.add("s.situacao=?");
            parametros.add(filtro);
        }
        String padrao = JanelaPagina.padraoBusca(busca);
        if (padrao != null) {
            condicoes.add("(p.atelie ILIKE ? ESCAPE '!' OR p.nome ILIKE ? ESCAPE '!')");
            parametros.add(padrao);
            parametros.add(padrao);
        }
        String where = condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes);
        Long total = jdbc.queryForObject("SELECT count(*) FROM solicitacoes_selo s JOIN perfis_artesao p ON p.id=s.perfil_id" + where,
                Long.class, parametros.toArray());
        var janela = JanelaPagina.de(pagina, tamanho, total == null ? 0 : total);
        if (apenasTotal) return new PaginaFila(List.of(), janela);
        var argumentos = new ArrayList<Object>(parametros);
        argumentos.add(janela.tamanho());
        argumentos.add(janela.deslocamento());
        var itens = jdbc.query(CONSULTA + where + " ORDER BY s.decidido_em DESC NULLS FIRST,s.solicitado_em,s.id LIMIT ? OFFSET ?",
                this::mapear, argumentos.toArray()).stream()
                .map(item -> "pendente".equals(item.situacao()) ? comContagem(item) : item).toList();
        return new PaginaFila(itens, janela);
    }

    @Transactional
    public Solicitacao decidir(IdentidadeAutenticada identidade, UUID id, Map<String,Object> body) {
        admins.exigirPermissao(identidade);
        campos(body, Set.of("decisao", "motivo"));
        String decisao = texto(body, "decisao", true);
        if (!Set.of("aprovada", "ajuste").contains(decisao)) throw CuradoriaException.invalido("decisao", "Informe aprovada ou ajuste.");
        String motivo = texto(body, "motivo", "ajuste".equals(decisao));
        if ("aprovada".equals(decisao) && !motivo.isEmpty()) throw CuradoriaException.invalido("motivo", "Motivo é usado somente no pedido de ajuste.");
        var rows = jdbc.query(CONSULTA + " WHERE s.id=? FOR UPDATE OF s", this::mapear, id);
        if (rows.isEmpty()) throw new CuradoriaException(404,"RECURSO_NAO_ENCONTRADO","Solicitação não encontrada.",null);
        var atual = rows.getFirst();
        if (!"pendente".equals(atual.situacao())) {
            if (decisao.equals(atual.situacao()) && motivo.equals(atual.motivo()==null?"":atual.motivo())) return atual;
            throw CuradoriaException.conflito("A solicitação já recebeu outra decisão.");
        }
        int publicadas = catalogo.contar(atual.artesaoSlug());
        if ("aprovada".equals(decisao) && publicadas==0) {
            throw CuradoriaException.conflito("O selo exige ao menos uma peça publicada.");
        }
        jdbc.update("UPDATE solicitacoes_selo SET situacao=?, motivo=?, decidido_em=CURRENT_TIMESTAMP, decidido_por=?, pecas_publicadas_decisao=? WHERE id=?",
                decisao, "ajuste".equals(decisao)?motivo:null, identidade.contaId(), publicadas, id);
        return jdbc.query(CONSULTA + " WHERE s.id=?", this::mapear, id).getFirst();
    }

    private PerfilArtesao artesao(IdentidadeAutenticada identidade) {
        if (identidade==null) throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        var conta=contas.bloquearPorId(identidade.contaId())
                .orElseThrow(()->new AcessoNegadoException(AcessoNegadoException.Motivo.CONTA_INDISPONIVEL));
        if (conta.papel()!=Papel.ARTESAO) throw new AcessoNegadoException(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        return perfis.consultarMeu(identidade);
    }
    private Estado estado(UUID perfil) {
        var rows=jdbc.query(CONSULTA+" WHERE s.perfil_id=? ORDER BY s.solicitado_em DESC,s.id DESC LIMIT 1", this::mapear, perfil);
        var atual=rows.isEmpty()?null:rows.getFirst();
        // Uma única fotografia do banco: aprovação concorrente não mistura flag nova com estado antigo.
        return new Estado(atual!=null && "aprovada".equals(atual.situacao()), atual);
    }
    private Solicitacao mapear(ResultSet rs,int n) throws SQLException {
        var decidido=rs.getTimestamp("decidido_em");
        return new Solicitacao(rs.getObject("id",UUID.class),rs.getString("slug"),rs.getString("nome"),rs.getString("atelie"),
                rs.getString("mensagem"),rs.getString("situacao"),rs.getTimestamp("solicitado_em").toInstant(),
                rs.getString("motivo"),decidido==null?null:decidido.toInstant(),rs.getString("territorio"),
                rs.getString("tecnica"),rs.getString("imagem"),rs.getObject("pecas_publicadas_decisao",Integer.class));
    }
    private Solicitacao comContagem(Solicitacao s) {
        return new Solicitacao(s.id(),s.artesaoSlug(),s.artesao(),s.atelie(),s.mensagem(),s.situacao(),s.solicitadoEm(),
                s.motivo(),s.decididoEm(),s.territorio(),s.tecnica(),s.imagem(),catalogo.contar(s.artesaoSlug()));
    }
    private static void campos(Map<String,Object> body,Set<String> permitidos) {
        if(body==null) throw CuradoriaException.invalido("corpo","Informe um objeto JSON.");
        for(String key:body.keySet()) if(!permitidos.contains(key)) throw CuradoriaException.invalido(key,"Campo não permitido.");
    }
    private static String texto(Map<String,Object> body,String campo,boolean obrigatorio) {
        if(!body.containsKey(campo)) {
            if(obrigatorio) throw CuradoriaException.invalido(campo,"Campo obrigatório.");
            return "";
        }
        if(!(body.get(campo) instanceof String valor)) throw CuradoriaException.invalido(campo,"Informe um texto.");
        if(valor.codePointCount(0,valor.length())>1000 || valor.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')) {
            throw CuradoriaException.invalido(campo,"Informe um texto de até 1.000 caracteres.");
        }
        valor=valor.strip();
        if(obrigatorio && valor.isBlank()) throw CuradoriaException.invalido(campo,"Campo obrigatório.");
        return valor;
    }
}
