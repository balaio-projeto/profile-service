package br.com.balaio.profile.artisan.media;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

class MediaMigrationUpgradeTest {
    @Test void atualizaBancoDaDevelopSemAlterarPerfilNemHistoricoAprovado() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            var anterior = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("5").load();
            anterior.migrate();
            var historico = anterior.info().applied();
            UUID conta = UUID.randomUUID(), perfil = UUID.randomUUID();
            try (var conexao = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var sql = conexao.prepareStatement("INSERT INTO contas(id,nome,email,senha_hash,papel) VALUES (?,?,?,?,?)")) {
                    sql.setObject(1, conta); sql.setString(2, "Artesao existente");
                    sql.setString(3, conta + "@test.invalid");
                    sql.setString(4, "{bcrypt}$2b$12$" + ".".repeat(53)); sql.setString(5, "artesao");
                    sql.executeUpdate();
                }
                try (var sql = conexao.prepareStatement("INSERT INTO perfis_artesao(id,conta_id,slug,nome,atelie,imagem) VALUES (?,?,?,?,?,?)")) {
                    sql.setObject(1, perfil); sql.setObject(2, conta); sql.setString(3, "artesao-" + conta);
                    sql.setString(4, "Nome preservado"); sql.setString(5, "Atelie preservado");
                    sql.setString(6, "/fotos/legado.webp"); sql.executeUpdate();
                }
                var atual = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration").load();
                atual.migrate();
                assertThat(atual.info().applied()).hasSize(atual.info().all().length);
                for (int i = 0; i < historico.length; i++) {
                    assertThat(atual.info().applied()[i].getChecksum()).isEqualTo(historico[i].getChecksum());
                }
                assertThat(atual.info().pending()).isEmpty();
                try (var sql = conexao.prepareStatement("SELECT conta_id,slug,nome,atelie,imagem FROM perfis_artesao WHERE id=?")) {
                    sql.setObject(1, perfil);
                    try (var resultado = sql.executeQuery()) {
                        assertThat(resultado.next()).isTrue();
                        assertThat(resultado.getObject("conta_id", UUID.class)).isEqualTo(conta);
                        assertThat(resultado.getString("slug")).isEqualTo("artesao-" + conta);
                        assertThat(resultado.getString("nome")).isEqualTo("Nome preservado");
                        assertThat(resultado.getString("atelie")).isEqualTo("Atelie preservado");
                        assertThat(resultado.getString("imagem")).isEqualTo("/fotos/legado.webp");
                    }
                }
                // Reiniciar a migracao deve apenas validar o mesmo historico.
                assertThat(atual.migrate().migrationsExecuted).isZero();
            }
        }
    }
}
