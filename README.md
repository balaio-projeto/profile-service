# profile-service

Contas, perfis de compradores e artesãos, selo e curadoria. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8082. Para executar pela IDE, defina `PROFILE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d profile-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8082/actuator/health`.

As migrations V1 e V2 já criam contas e o registro singleton de controle administrativo. O Flyway aplica o schema e o Hibernate o valida. As rotas públicas planejadas estão mapeadas no gateway; os endpoints de conta ainda não foram implementados neste serviço.

## Contas e primeiro administrador

O domínio de `account` não depende de Spring ou JPA. Contas têm um único papel (`comprador`, `artesao` ou `admin`), e-mail normalizado/único, hash bcrypt e `trocaSenhaObrigatoria`. O cadastro público interno fixa `comprador`, ignorando o papel solicitado.

O bootstrap usa `ADMIN_EMAIL` e `ADMIN_SENHA_INICIAL` enquanto o marcador estiver pendente. Cria o administrador com troca obrigatória e confirma o controle na mesma transação, sob bloqueio no PostgreSQL. Depois da conclusão, reinícios não redefinem senha ou papel. Credenciais não são gravadas no código nem registradas em mensagens.

`spring-security-crypto` fornece o `PasswordEncoder` bcrypt de custo 12. Não há configuração de segurança HTTP. `spring-boot-testcontainers` e o módulo PostgreSQL existem somente em escopo de teste.

## Alteração da própria senha

`AlterarPropriaSenhaUseCase.alterar` recebe `IdentidadeAutenticada` separada do comando. O comando contém somente `senhaAtual` e `novaSenha`; não aceita UUID de alvo, papel ou indicador. A identidade é um contrato interno para a futura fronteira de autenticação validada. Não existe mecanismo provisório que confie em um UUID informado pelo cliente.

A aplicação confere a senha atual usando `PasswordEncoder.matches`, valida a nova com `PoliticaSenha` e rejeita reutilização. A política exige pelo menos 12 caracteres Unicode, UTF-8 válido, até 72 bytes e nenhum caractere de controle. As senhas não sofrem trim ou interpolação. O caso de uso não retorna senha, hash ou dados da conta; o comando oculta suas credenciais em `toString` e erros inesperados são sanitizados sem causa sensível.

`Conta.alterarSenha` exige um hash novo e produz uma conta com a obrigação desativada, preservando os demais dados. `ContasPort.atualizarSenha` faz uma atualização condicional: grava somente hash e `troca_senha_obrigatoria = false` se o hash anterior ainda corresponder ao lido. A transação confirma ambos ou nenhum. Uma segunda troca concorrente usando o mesmo estado recebe conflito; não há repetição automática com senha antiga. O cache JPA é sincronizado para permitir releitura correta na mesma transação.

Não há operação pública para desativar somente o indicador. O valor continua persistido na coluna já existente de V1; esta etapa não exige nova migration.

## Autorização administrativa interna

Os casos de uso administrativos devem abrir uma transação de escrita e chamar `AutorizarOperacaoAdministrativaUseCase.exigirPermissao` antes de qualquer efeito, dentro dessa mesma transação. O guard exige transação existente (`MANDATORY`), consulta o estado atual da conta com `SELECT ... FOR UPDATE` e exige papel `admin` e `trocaSenhaObrigatoria = false`. A trava é mantida até o fim da ação protegida.

Uma identidade ausente, conta indisponível, papel inadequado ou troca pendente bloqueia a operação. Claims futuros de papel/indicador não substituirão a consulta ao estado persistido. A troca da própria senha não depende desse guard, portanto permanece permitida enquanto a obrigação estiver pendente. A alteração administrativa de papéis descrita abaixo já utiliza essa autorização; nenhum endpoint é exposto nesta etapa.

## Alteração administrativa de papéis e último administrador

`AlterarPapelAdministrativoUseCase.alterar` recebe a identidade confiável do solicitante separada de `AlterarPapelComando`, que descreve apenas a conta alvo e seu novo papel. O UUID do alvo e o papel solicitado não representam autorização. A futura autenticação JWT deverá produzir a identidade a partir do token validado, sem copiá-la do corpo ou de um identificador livre informado pelo cliente.

A operação permite atribuir `comprador`, `artesao` ou `admin`, sempre consultando no banco o papel e a pendência de senha atuais do solicitante. Uma solicitação sem mudança de papel também exige autorização. O cadastro público continua fixando `comprador` e não utiliza esta entrada administrativa.

O caso de uso abre uma transação independente `REQUIRES_NEW`, com isolamento `READ_COMMITTED`. A ordem é: bloquear o singleton `controle_administracao`; autorizar e bloquear o solicitante; bloquear a conta alvo; contar administradores; aplicar a regra de domínio; atualizar somente o papel. A comparação com o papel anterior também protege a escrita contra estado incompatível, sem sobrescrever nome, e-mail, hash ou indicador. O cache JPA é sincronizado após a atualização.

`Conta.alterarPapel` lança `UltimoAdministradorException` se um administrador perder esse papel quando só existe um. A mesma regra se aplica à própria conta. A exceção é de negócio, sem dependência HTTP, e poderá ser traduzida para 409. Com dois administradores, uma despromoção pode confirmar e o restante passa a ficar protegido.

A trava do singleton permanece até commit ou rollback. Assim, a segunda operação concorrente conta os administradores depois da conclusão da primeira. `REQUIRES_NEW` evita herdar um snapshot antigo de uma transação externa `REPEATABLE_READ`; a contagem precisa refletir o estado confirmado após a espera. Esta é uma fronteira transacional independente: não a invoque de uma transação externa que já detenha o singleton ou as mesmas contas, para evitar bloqueio contra a própria transação suspensa.

O modelo atual não possui indicador de ativação nem operações de remoção/desativação. Portanto, contas existentes com papel `admin` compõem a contagem de administradores ativos nesta etapa. Pendência de troca restringe ações administrativas, mas não equivale a desativação. Não foram criadas operações de ciclo de vida; quando existirem, deverão reutilizar a trava e a verificação do último administrador antes de remover sua condição ativa. Curadoria de selo pertence à PI-177 e não integra esta entrega.

## Testes e escopo

Execute `.\mvnw.cmd clean test` com JDK 25 e Docker ativo. A compilação limpa também remove classes e migrations antigas de `target/` depois de desfazer alterações. Os testes usam PostgreSQL 18 descartável via Testcontainers, sem usar o banco do projeto nem ler credenciais locais.

Os testes cobrem bootstrap com indicador pendente, senha atual correta/incorreta, política e não reutilização, hash novo e senha anterior inválida, remoção da obrigação somente no sucesso, rollback após escrita real, isolamento da própria conta, conflitos entre duas transações concorrentes e guard administrativo antes/depois da troca. Também verificam releitura na mesma transação e ausência de segredos nas representações e erros.

Para papéis, os testes verificam autorização pelo estado atual do banco, bloqueio de solicitantes comuns/pendentes, promoção válida, preservação dos demais dados, impossibilidade de autodespromover o último administrador, proteção do remanescente, rollback e duas despromoções concorrentes sob bloqueio PostgreSQL real. Também exercitam a entrada a partir de snapshots externos `REPEATABLE_READ` e tentativas de usar o identificador do alvo como autorização.

Esta entrega permanece interna a domínio, aplicação e persistência. Não inclui controllers, endpoints, login, JWT, alterações no gateway ou em outros microsserviços.
