package br.com.balaio.profile.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Notificacoes {
    public record Notificacao(UUID id, String tipo, String titulo, String mensagem, String link, boolean lida,
            OffsetDateTime criadaEm) { }

    public record NovaNotificacao(UUID conta, String tipo, String titulo, String mensagem, String link, String chave) { }

    static final int HORAS_APOS_LEITURA = 24;

    private final JdbcTemplate jdbc;

    public Notificacoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean registrar(NovaNotificacao nova) {
        return jdbc.update("INSERT INTO notificacoes(id, conta_id, tipo, titulo, mensagem, link, chave) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM contas WHERE id = ?) "
                        + "ON CONFLICT (chave) DO NOTHING",
                UUID.randomUUID(), nova.conta(), nova.tipo(), nova.titulo(), nova.mensagem(), nova.link(), nova.chave(),
                nova.conta()) > 0;
    }

    public boolean contaExiste(UUID conta) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM contas WHERE id = ?)",
                Boolean.class, conta));
    }

    public List<Notificacao> listar(UUID conta) {
        return jdbc.query("SELECT id, tipo, titulo, mensagem, link, lida_em IS NOT NULL AS lida, criada_em "
                        + "FROM notificacoes WHERE conta_id = ? "
                        + "AND (lida_em IS NULL OR lida_em > now() - make_interval(hours => ?)) "
                        + "ORDER BY criada_em DESC, id",
                (rs, linha) -> new Notificacao(rs.getObject("id", UUID.class), rs.getString("tipo"),
                        rs.getString("titulo"), rs.getString("mensagem"), rs.getString("link"), rs.getBoolean("lida"),
                        rs.getObject("criada_em", OffsetDateTime.class)),
                conta, HORAS_APOS_LEITURA);
    }

    public int removerLidasHaMaisDeUmDia() {
        return jdbc.update("DELETE FROM notificacoes WHERE lida_em < now() - make_interval(hours => ?)",
                HORAS_APOS_LEITURA);
    }

    public int naoLidas(UUID conta) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM notificacoes WHERE conta_id = ? AND lida_em IS NULL", Integer.class, conta);
        return total == null ? 0 : total;
    }

    public boolean marcarLida(UUID conta, UUID id) {
        return jdbc.update("UPDATE notificacoes SET lida_em = COALESCE(lida_em, now()) WHERE id = ? AND conta_id = ?",
                id, conta) > 0;
    }

    public int marcarTodasLidas(UUID conta) {
        return jdbc.update("UPDATE notificacoes SET lida_em = now() WHERE conta_id = ? AND lida_em IS NULL", conta);
    }
}
