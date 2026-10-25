package br.com.balaio.profile.account.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.balaio.profile.account.application.ConfiguracaoAdministradorInicial;

class ConfiguracaoAdministradorInicialEnvironmentAdapterTest {
    @Test
    void preservaValoresLiteraisSemResolverPlaceholdersOuRemoverEspacos() {
        String email = " ADMIN+${ALIAS}@EXEMPLO.COM ";
        String senha = "  senha ${OUTRO_SEGREDO}:# com espacos  ";
        Map<String, String> ambiente = Map.of(
                "ADMIN_EMAIL", email,
                "ADMIN_SENHA_INICIAL", senha,
                "OUTRO_SEGREDO", "valor-que-nao-deve-ser-interpolado");
        var adapter = new ConfiguracaoAdministradorInicialEnvironmentAdapter(ambiente::get);

        ConfiguracaoAdministradorInicial configuracao = adapter.obter();

        assertThat(configuracao.email()).isEqualTo(email);
        assertThat(configuracao.senhaInicial()).isEqualTo(senha);
    }

    @Test
    void leSomenteVariaveisContratadasQuandoSolicitadoSemCacheOuValidacaoAntecipada() {
        Map<String, String> ambiente = new HashMap<>();
        List<String> consultadas = new ArrayList<>();
        var adapter = new ConfiguracaoAdministradorInicialEnvironmentAdapter(chave -> {
            consultadas.add(chave);
            return ambiente.get(chave);
        });

        assertThat(consultadas).isEmpty();
        ConfiguracaoAdministradorInicial ausente = adapter.obter();
        assertThat(ausente.email()).isNull();
        assertThat(ausente.senhaInicial()).isNull();
        assertThat(consultadas).containsExactly("ADMIN_EMAIL", "ADMIN_SENHA_INICIAL");

        ambiente.put("ADMIN_EMAIL", "admin@exemplo.com");
        ambiente.put("ADMIN_SENHA_INICIAL", "${VALOR_LITERAL}");
        ConfiguracaoAdministradorInicial preenchida = adapter.obter();

        assertThat(preenchida.email()).isEqualTo("admin@exemplo.com");
        assertThat(preenchida.senhaInicial()).isEqualTo("${VALOR_LITERAL}");
        assertThat(consultadas).containsExactly(
                "ADMIN_EMAIL", "ADMIN_SENHA_INICIAL", "ADMIN_EMAIL", "ADMIN_SENHA_INICIAL");
    }
}
