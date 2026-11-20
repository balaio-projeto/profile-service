package br.com.balaio.profile.settings;

import br.com.balaio.profile.account.application.AcessoNegadoException;
import br.com.balaio.profile.account.application.IdentidadeAutenticada;
import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.artisan.application.PerfilArtesaoPort;
import br.com.balaio.profile.artisan.application.PerfilNaoEncontradoException;
import br.com.balaio.profile.artisan.domain.PerfilArtesao;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfiguracoesAtelieService {
    public record Configuracoes(String cepOrigem, int prazoPadraoDias, boolean aceitaEncomendas,
            boolean encomendasPausadas, String chavePix) {
        @Override public String toString() { return "Configuracoes[REDACTED]"; }
    }
    private final ContasPort contas;
    private final PerfilArtesaoPort perfis;
    private final JdbcTemplate jdbc;
    public ConfiguracoesAtelieService(ContasPort contas, PerfilArtesaoPort perfis, JdbcTemplate jdbc) {
        this.contas = contas; this.perfis = perfis; this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Configuracoes consultar(IdentidadeAutenticada identidade, String slug) {
        return carregar(autorizar(identidade, slug, false).id());
    }

    @Transactional
    public Configuracoes atualizar(IdentidadeAutenticada identidade, String slug, Map<String, Object> campos) {
        var perfil = autorizar(identidade, slug, true);
        EdicaoContaService.validarCampos(campos, Set.of("cepOrigem", "prazoPadraoDias", "aceitaEncomendas", "encomendasPausadas", "chavePix"));
        var atual = carregar(perfil.id());
        String cep = atual.cepOrigem();
        if (campos.containsKey("cepOrigem")) {
            cep = EdicaoContaService.texto(campos, "cepOrigem");
            if (!cep.matches("[0-9]{5}-?[0-9]{3}")) throw new CamposInvalidosException("cepOrigem", "Informe um CEP válido.");
            cep = cep.replace("-", ""); cep = cep.substring(0, 5) + "-" + cep.substring(5);
        }
        int prazo = atual.prazoPadraoDias();
        if (campos.containsKey("prazoPadraoDias")) {
            Object valor = campos.get("prazoPadraoDias");
            try {
                if (!(valor instanceof Number)) throw new ArithmeticException();
                prazo = new BigDecimal(valor.toString()).intValueExact();
                if (prazo < 1 || prazo > 120) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new CamposInvalidosException("prazoPadraoDias", "Informe dias inteiros entre 1 e 120.");
            }
        }
        boolean aceita = booleano(campos, "aceitaEncomendas", atual.aceitaEncomendas());
        boolean pausadas = booleano(campos, "encomendasPausadas", atual.encomendasPausadas());
        String pix = campos.containsKey("chavePix") ? EdicaoContaService.texto(campos, "chavePix") : atual.chavePix();
        jdbc.update("""
            INSERT INTO configuracoes_atelie (perfil_id, cep_origem, prazo_padrao_dias, aceita_encomendas, encomendas_pausadas, chave_pix)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (perfil_id) DO UPDATE SET cep_origem=EXCLUDED.cep_origem,
            prazo_padrao_dias=EXCLUDED.prazo_padrao_dias, aceita_encomendas=EXCLUDED.aceita_encomendas,
            encomendas_pausadas=EXCLUDED.encomendas_pausadas, chave_pix=EXCLUDED.chave_pix
            """, perfil.id(), cep, prazo, aceita, pausadas, pix);
        return new Configuracoes(cep, prazo, aceita, pausadas, pix);
    }

    private PerfilArtesao autorizar(IdentidadeAutenticada identidade, String slug, boolean escrita) {
        if (identidade == null) throw new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO);
        var conta = (escrita ? contas.bloquearPorId(identidade.contaId()) : contas.buscarPorId(identidade.contaId()))
                .orElseThrow(() -> new AcessoNegadoException(AcessoNegadoException.Motivo.NAO_AUTENTICADO));
        if (conta.papel() != Papel.ARTESAO) throw new AcessoNegadoException(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        var perfil = (escrita ? perfis.buscarPorSlugParaAtualizar(slug) : perfis.buscarPorSlug(slug))
                .orElseThrow(PerfilNaoEncontradoException::new);
        if (!perfil.contaId().equals(conta.id())) throw new AcessoNegadoException(AcessoNegadoException.Motivo.PRIVILEGIO_INSUFICIENTE);
        return perfil;
    }
    private Configuracoes carregar(UUID perfil) {
        return jdbc.query("SELECT * FROM configuracoes_atelie WHERE perfil_id=?", (r, n) -> new Configuracoes(
                r.getString("cep_origem"), r.getInt("prazo_padrao_dias"), r.getBoolean("aceita_encomendas"),
                r.getBoolean("encomendas_pausadas"), r.getString("chave_pix")), perfil).stream().findFirst()
                .orElse(new Configuracoes("", 1, false, false, ""));
    }
    private boolean booleano(Map<String, Object> campos, String campo, boolean anterior) {
        if (!campos.containsKey(campo)) return anterior;
        if (!(campos.get(campo) instanceof Boolean valor)) throw new CamposInvalidosException(campo, "Informe verdadeiro ou falso.");
        return valor;
    }
}
