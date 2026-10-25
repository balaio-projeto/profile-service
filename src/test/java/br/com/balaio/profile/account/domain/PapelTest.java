package br.com.balaio.profile.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PapelTest {
    @Test
    void converteSomenteCodigosPublicosExatos() {
        assertThat(Papel.fromCodigo("comprador")).isEqualTo(Papel.COMPRADOR);
        assertThat(Papel.fromCodigo("artesao")).isEqualTo(Papel.ARTESAO);
        assertThat(Papel.fromCodigo("admin")).isEqualTo(Papel.ADMIN);
        assertThat(Papel.COMPRADOR.codigo()).isEqualTo("comprador");
        assertThat(Papel.ARTESAO.codigo()).isEqualTo("artesao");
        assertThat(Papel.ADMIN.codigo()).isEqualTo("admin");
    }

    @Test
    void rejeitaCodigosAusentesDesconhecidosOuNormalizadosImplicitamente() {
        for (String codigo : new String[] {null, "", "ADMIN", "Admin", " admin", "admin ", "administrador"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> Papel.fromCodigo(codigo));
        }
    }
}
