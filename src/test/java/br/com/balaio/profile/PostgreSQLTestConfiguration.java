package br.com.balaio.profile;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSQLTestConfiguration {

	@Bean
	DynamicPropertyRegistrar autenticacaoDeTeste() {
		return registro -> {
			registro.add("balaio.auth.jwt.private-key-location", AutenticacaoTestFixtures::privateKeyLocation);
			registro.add("balaio.auth.jwt.issuer", () -> AutenticacaoTestFixtures.ISSUER);
			registro.add("balaio.auth.jwt.audience", () -> AutenticacaoTestFixtures.AUDIENCE);
			registro.add("balaio.auth.allowed-origins", () -> AutenticacaoTestFixtures.ORIGIN);
		};
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:18-alpine");
	}
}
