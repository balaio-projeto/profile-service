package br.com.balaio.profile.account.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.balaio.profile.account.application.port.out.ControleAdministracaoPort;
import br.com.balaio.profile.account.domain.ControleAdministracao;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ControleAdministracaoPersistenceAdapter implements ControleAdministracaoPort {
    private final JdbcTemplate jdbc;

    public ControleAdministracaoPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ControleAdministracao bloquear() {
        exigirTransacaoDeEscrita();
        try {
            Boolean concluido = jdbc.queryForObject(
                    "SELECT bootstrap_concluido FROM controle_administracao WHERE id = 1 FOR UPDATE",
                    Boolean.class);
            return new ControleAdministracao(Boolean.TRUE.equals(concluido));
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Registro de controle administrativo ausente.", exception);
        }
    }

    @Override
    public void concluirBootstrap() {
        exigirTransacaoDeEscrita();
        int alterados = jdbc.update(
                "UPDATE controle_administracao SET bootstrap_concluido = TRUE WHERE id = 1");
        if (alterados != 1) {
            throw new IllegalStateException("Registro de controle administrativo ausente.");
        }
    }

    private void exigirTransacaoDeEscrita() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Controle administrativo exige transacao de escrita.");
        }
    }
}
