package br.com.balaio.profile.account.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;
import br.com.balaio.profile.account.domain.SenhaProtegida;

@Repository
@Transactional(readOnly = true)
public class ContasPersistenceAdapter implements ContasPort {
    private final SpringDataContaRepository repository;
    private final JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager entityManager;

    public ContasPersistenceAdapter(SpringDataContaRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Conta salvar(Conta conta) {
        Objects.requireNonNull(conta, "Conta obrigatoria.");
        try {
            return repository.saveAndFlush(ContaJpaEntity.fromDomain(conta)).toDomain();
        } catch (DataIntegrityViolationException exception) {
            if (emailDuplicado(exception)) {
                throw new EmailJaCadastradoException();
            }
            throw exception;
        }
    }

    @Override
    public Optional<Conta> buscarPorId(UUID id) {
        return repository.findById(Objects.requireNonNull(id, "Identificador obrigatorio."))
                .map(ContaJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Conta> bloquearPorId(UUID id) {
        exigirTransacaoDeEscrita();
        Objects.requireNonNull(id, "Identificador obrigatório.");
        return jdbc.query("""
                SELECT id, nome, email, senha_hash, papel, troca_senha_obrigatoria
                FROM contas WHERE id = ? FOR UPDATE
                """, (linha, numero) -> new Conta(linha.getObject("id", UUID.class),
                        linha.getString("nome"), new Email(linha.getString("email")),
                        new SenhaProtegida(linha.getString("senha_hash")),
                        Papel.fromCodigo(linha.getString("papel")), linha.getBoolean("troca_senha_obrigatoria")), id)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean atualizarSenha(UUID id, SenhaProtegida hashEsperado, SenhaProtegida novoHash) {
        exigirTransacaoDeEscrita();
        Objects.requireNonNull(id, "Identificador obrigatório.");
        Objects.requireNonNull(hashEsperado, "Hash esperado obrigatório.");
        Objects.requireNonNull(novoHash, "Novo hash obrigatório.");
        entityManager.flush();
        int alterados = jdbc.update("""
                UPDATE contas SET senha_hash = ?, troca_senha_obrigatoria = FALSE
                WHERE id = ? AND senha_hash = ? AND senha_hash <> ?
                """, novoHash.valor(), id, hashEsperado.valor(), novoHash.valor());
        if (alterados == 1) {
            entityManager.clear();
        }
        return alterados == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean atualizarPapel(UUID id, Papel papelEsperado, Papel novoPapel) {
        exigirTransacaoDeEscrita();
        Objects.requireNonNull(id, "Identificador obrigatório.");
        Objects.requireNonNull(papelEsperado, "Papel esperado obrigatório.");
        Objects.requireNonNull(novoPapel, "Novo papel obrigatório.");
        entityManager.flush();
        int alterados = jdbc.update("""
                UPDATE contas SET papel = ?
                WHERE id = ? AND papel = ? AND papel <> ?
                """, novoPapel.codigo(), id, papelEsperado.codigo(), novoPapel.codigo());
        if (alterados == 1) {
            entityManager.clear();
        }
        return alterados == 1;
    }

    @Override
    public Optional<Conta> buscarPorEmail(Email email) {
        return repository.findByEmail(Objects.requireNonNull(email, "E-mail obrigatorio.").valor())
                .map(ContaJpaEntity::toDomain);
    }

    @Override
    public long contarPorPapel(Papel papel) {
        return repository.countByPapel(Objects.requireNonNull(papel, "Papel obrigatorio.").codigo());
    }

    private void exigirTransacaoDeEscrita() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Atualização de conta exige transação de escrita.");
        }
    }

    private boolean emailDuplicado(Throwable exception) {
        for (Throwable causa = exception; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConstraintViolationException violacao
                    && "uk_contas_email_normalizado".equals(violacao.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
