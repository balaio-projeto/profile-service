package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ControleAdministracaoTest {
    @Test
    void representaEstadoPersistidoDoBootstrapSemExecutarTransicoes() {
        assertThat(new ControleAdministracao(false).bootstrapConcluido()).isFalse();
        assertThat(new ControleAdministracao(true).bootstrapConcluido()).isTrue();
    }
}
