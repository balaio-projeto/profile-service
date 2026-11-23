package br.com.balaio.profile.curation;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.in.AutorizarOperacaoAdministrativaUseCase;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.artisan.application.PerfilArtesaoService;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    @Transactional
    public List<Solicitacao> fila(IdentidadeAutenticada identidade) {
        admins.exigirPermissao(identidade);
        return jdbc.query(CONSULTA + " WHERE s.situacao='pendente' ORDER BY s.solicitado_em,s.id", this::mapear)
                .stream().map(this::comContagem).toList();
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
