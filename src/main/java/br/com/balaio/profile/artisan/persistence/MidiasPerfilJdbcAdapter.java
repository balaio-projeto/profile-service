package br.com.balaio.profile.artisan.persistence;

import br.com.balaio.profile.artisan.media.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MidiasPerfilJdbcAdapter implements MidiasPerfilPort {
    private final JdbcTemplate jdbc;
    private static final RowMapper<MidiaPerfil> MAP = (r, n) -> new MidiaPerfil(
            r.getObject("id", UUID.class), r.getObject("perfil_id", UUID.class), r.getString("chave"),
            r.getString("tipo"), r.getInt("tamanho"), r.getString("sha256"));

    public MidiasPerfilJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void reservar(MidiaPerfil m) {
        jdbc.update("INSERT INTO midias_perfil(id,perfil_id,chave,tipo,tamanho,sha256,estado) VALUES (?,?,?,?,?,?,'PENDING')",
                m.id(), m.perfilId(), m.chave(), m.tipo(), m.tamanho(), m.sha256());
    }
    private String bloquear(UUID perfil) {
        return jdbc.queryForObject("SELECT imagem FROM perfis_artesao WHERE id=? FOR UPDATE", String.class, perfil);
    }
    @Transactional
    public void ativar(MidiaPerfil m) {
        String anterior = bloquear(m.perfilId());
        if (jdbc.update("UPDATE midias_perfil SET estado='ACTIVE' WHERE id=? AND perfil_id=? AND estado='PENDING'",
                m.id(), m.perfilId()) != 1) throw new MidiaException(MidiaException.Motivo.CONFLITO);
        jdbc.update("UPDATE perfis_artesao SET imagem=? WHERE id=?", m.url(), m.perfilId());
        marcarAnterior(m.perfilId(), anterior);
    }
    private void marcarAnterior(UUID perfil, String url) {
        jdbc.update("UPDATE midias_perfil SET estado='DELETE' WHERE perfil_id=? AND ? = ? || CAST(id AS text)",
                perfil, url, MidiaPerfil.PREFIXO_PUBLICO);
    }
    @Transactional
    public void remover(UUID perfil) {
        String anterior = bloquear(perfil);
        jdbc.update("UPDATE perfis_artesao SET imagem=? WHERE id=?", MidiaPerfil.PLACEHOLDER, perfil);
        marcarAnterior(perfil, anterior);
    }
    public Optional<MidiaPerfil> vinculada(UUID id) {
        return jdbc.query("SELECT m.* FROM midias_perfil m JOIN perfis_artesao p ON p.id=m.perfil_id "
                + "WHERE m.id=? AND m.estado='ACTIVE' AND p.imagem=? || CAST(m.id AS text)", MAP,
                id, MidiaPerfil.PREFIXO_PUBLICO).stream().findFirst();
    }
    public List<MidiaPerfil> candidatasLimpeza() {
        return jdbc.query("SELECT m.* FROM midias_perfil m WHERE "
                + "m.proxima_tentativa <= CURRENT_TIMESTAMP "
                + "AND (m.estado='DELETE' OR m.criada_em < CURRENT_TIMESTAMP - INTERVAL '24 hours') "
                + "AND NOT EXISTS (SELECT 1 FROM perfis_artesao p WHERE p.imagem=? || CAST(m.id AS text)) "
                + "ORDER BY m.proxima_tentativa, m.criada_em, m.id LIMIT 20", MAP, MidiaPerfil.PREFIXO_PUBLICO);
    }
    @Transactional
    public boolean prepararExclusao(MidiaPerfil m) {
        String atual = bloquear(m.perfilId());
        if (m.url().equals(atual)) return false;
        // Claim before contacting S3. Another worker/restarted process waits for
        // the persisted deadline. A timed-out or repeated DELETE is idempotent.
        return jdbc.update("UPDATE midias_perfil SET estado='DELETE', "
                + "proxima_tentativa=CURRENT_TIMESTAMP + INTERVAL '5 minutes' "
                + "WHERE id=? AND proxima_tentativa <= CURRENT_TIMESTAMP AND "
                + "(estado='DELETE' OR criada_em < CURRENT_TIMESTAMP - INTERVAL '24 hours')", m.id()) == 1;
    }
    @Transactional
    public void concluirExclusao(UUID id) {
        jdbc.update("DELETE FROM midias_perfil WHERE id=? AND estado='DELETE'", id);
    }
}
