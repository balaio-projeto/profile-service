package br.com.balaio.profile.account.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.balaio.profile.account.application.ContaAdministrativa;
import br.com.balaio.profile.account.application.port.out.ListarContasAdminPort;
import br.com.balaio.profile.account.domain.Papel;

@Repository
public class ContasAdminConsultaJdbcAdapter implements ListarContasAdminPort {
    private final JdbcTemplate jdbc;

    public ContasAdminConsultaJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long contar(Set<Papel> papeis, String padraoBusca) {
        var parametros = new ArrayList<Object>();
        Long total = jdbc.queryForObject("SELECT count(*) FROM contas c" + filtro(papeis, padraoBusca, parametros),
                Long.class, parametros.toArray());
        return total == null ? 0 : total;
    }

    @Override
    public List<ContaAdministrativa> listar(Set<Papel> papeis, String padraoBusca, int deslocamento, int limite) {
        var parametros = new ArrayList<Object>();
        String where = filtro(papeis, padraoBusca, parametros);
        parametros.add(limite);
        parametros.add(deslocamento);
        return jdbc.query("SELECT c.id, c.nome, c.email, c.papel, c.imagem FROM contas c" + where
                + " ORDER BY CASE WHEN c.papel = 'organizador' THEN 0 ELSE 1 END, lower(c.nome), c.id LIMIT ? OFFSET ?",
                ContasAdminConsultaJdbcAdapter::mapear, parametros.toArray());
    }

    private static String filtro(Set<Papel> papeis, String padraoBusca, List<Object> parametros) {
        var codigos = papeis.stream().map(Papel::codigo).sorted().toList();
        parametros.addAll(codigos);
        String sql = " WHERE c.papel IN (" + String.join(",", codigos.stream().map(codigo -> "?").toList()) + ")";
        if (padraoBusca != null) {
            sql += " AND (c.nome ILIKE ? ESCAPE '!' OR c.email ILIKE ? ESCAPE '!')";
            parametros.add(padraoBusca);
            parametros.add(padraoBusca);
        }
        return sql;
    }

    private static ContaAdministrativa mapear(ResultSet rs, int linha) throws SQLException {
        String imagem = rs.getString("imagem");
        return new ContaAdministrativa(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("email"),
                Papel.fromCodigo(rs.getString("papel")), imagem == null || imagem.isBlank() ? null : imagem);
    }
}
