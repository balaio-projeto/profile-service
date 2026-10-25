package br.com.balaio.profile.account.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import br.com.balaio.profile.account.application.port.out.ContasPort;
import br.com.balaio.profile.account.domain.Conta;
import br.com.balaio.profile.account.domain.Email;
import br.com.balaio.profile.account.domain.EmailJaCadastradoException;
import br.com.balaio.profile.account.domain.Papel;

@Repository
@Transactional(readOnly = true)
public class ContasPersistenceAdapter implements ContasPort {
    private final SpringDataContaRepository repository;

    public ContasPersistenceAdapter(SpringDataContaRepository repository) {
        this.repository = repository;
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
    public Optional<Conta> buscarPorEmail(Email email) {
        return repository.findByEmail(Objects.requireNonNull(email, "E-mail obrigatorio.").valor())
                .map(ContaJpaEntity::toDomain);
    }

    @Override
    public long contarPorPapel(Papel papel) {
        return repository.countByPapel(Objects.requireNonNull(papel, "Papel obrigatorio.").codigo());
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
