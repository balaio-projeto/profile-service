package br.com.balaio.profile.curation;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** O selo é derivado da aprovação persistida; não existe uma segunda flag para divergir. */
@Repository
public class SelosRepository {
    private final JdbcTemplate jdbc;
    public SelosRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean verificado(UUID perfilId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM solicitacoes_selo WHERE perfil_id=? AND situacao='aprovada')",
                Boolean.class, perfilId));
    }
    public Set<UUID> verificados() {
        return new HashSet<>(jdbc.query("SELECT perfil_id FROM solicitacoes_selo WHERE situacao='aprovada'",
                (rs, n) -> rs.getObject(1, UUID.class)));
    }
}
