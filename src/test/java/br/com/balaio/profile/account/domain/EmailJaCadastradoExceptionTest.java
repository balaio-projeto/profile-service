package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailJaCadastradoExceptionTest {
    @Test
    void comunicaConflitoComMensagemGenerica() {
        EmailJaCadastradoException exception = new EmailJaCadastradoException();

        assertThat(exception).isInstanceOf(RuntimeException.class).hasMessage("E-mail já cadastrado.");
    }
}
