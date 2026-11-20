# profile-service

Contas, perfis de compradores e artesãos, selo e curadoria. O serviço é independente, usa Java 25 e Spring Boot 4.1.1 e recebe requisições externas por meio do api-gateway.

## PI-209 — conta e configurações privadas

`PATCH /api/v1/usuario` e o alias `PATCH /api/v1/conta/{id}` editam somente
`nome` e `email` da própria conta autenticada. Campos omitidos são preservados;
campos desconhecidos, valores nulos e tipos incorretos são recusados. O nome
segue o limite existente de 240 caracteres; o e-mail é normalizado e permanece
único, inclusive sob concorrência. O alias não permite editar outro UUID.
Respostas usam o usuário público existente e `Cache-Control: no-store`.
E-mail duplicado retorna 409/EMAIL_EM_USO; entrada inválida retorna
400/REQUISICAO_INVALIDA com `erro.campos`; autenticação/autorização usam 401/403.

`POST /api/v1/auth/alterar-senha` continua sendo a única implementação da troca
de senha, com `senhaAtual` e `novaSenha`; o frontend passou a chamar esse contrato.

`GET/PATCH /api/v1/artesaos/{slug}/configuracoes` exigem Bearer JWT válido e
papel atual de artesão proprietário. Não há acesso público ou exceção de admin
para a chave Pix. Os campos são `cepOrigem` (8 dígitos, com hífen opcional,
normalizado para 00000-000), `prazoPadraoDias` (inteiro de 1 a 120),
`aceitaEncomendas` e `encomendasPausadas` (booleanos) e `chavePix` (texto não vazio,
sem caracteres de controle, com espaços externos removidos). Não há processamento
ou validação bancária da chave. PATCH preserva campos ausentes e serializa
atualizações concorrentes do mesmo ateliê. A V9 cria somente
`configuracoes_atelie`, sem modificar o perfil cultural ou as migrations antigas.

Um ateliê ainda não configurado retorna CEP/Pix vazios, prazo mínimo de 1 dia e
ambas as opções desmarcadas; GET não grava dados. Esses valores iniciais permitem
preencher o formulário, sem criar uma nova regra de encomendas. A chave só aparece
na resposta privada das configurações, nunca no perfil público ou no usuário.
Todas as respostas e erros desse recurso têm `Cache-Control: no-store` e erros
internos são sanitizados. Mutações diretas também verificam Origin.

O carregamento SSR de `/dashboard/settings` encaminha explicitamente o token
obtido por `exigirArtesao()` usando `comToken`, sem mudar o cookie HttpOnly ou
enviar o JWT como propriedade de componentes cliente. Via navegador, o proxy
existente converte o cookie em Bearer. As configurações são persistidas no
PostgreSQL do Profile, inclusive após reinício; GET não depende de memória local.

Testes adicionais: `PreferenciasHttpTest` (PostgreSQL descartável e HTTP real).

## Stack inicial

- Spring MVC, Spring Data JPA e PostgreSQL
- Flyway para migrações do banco próprio
- Bean Validation
- Spring Boot Actuator

## Execução

Porta HTTP padrão: 8082. Para executar pela IDE, defina `PROFILE_SERVICE_PORT` (opcional), `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`, além da configuração de autenticação descrita abaixo. O banco e suas credenciais são configurados no repositório `platform`.

No Windows: `.\mvnw.cmd spring-boot:run`. Testes: `.\mvnw.cmd test`.

O `Dockerfile` constrói uma imagem própria. Em `platform`, use `docker compose up --build -d profile-service` para iniciar este serviço e seu banco ou `docker compose --profile extended up --build -d` para iniciar todos. Health: `http://localhost:8082/actuator/health`.

As migrations V1, V2 e V3 criam contas, o registro singleton de controle administrativo e os tokens de recuperação. O Flyway aplica o schema e o Hibernate o valida. Cadastro, login/alias, logout e recuperação estão disponíveis sob `/api/v1/auth`; as demais rotas de conta mapeadas no gateway ainda dependem de implementação.

## Cadastro público — etapa 2

`POST /api/v1/auth/cadastro`, com `Content-Type: application/json`, recebe `nome`, `email`, `senha` e `perfil` (`comprador` ou `artesao`). O alias legado `papel` também é aceito. Se ambos forem enviados, devem ser iguais. Qualquer valor não público, conflito, tipo não textual, campo conhecido explicitamente nulo ou chave JSON duplicada resulta em 400; os valores de perfil são exatos, sem coerção ou normalização. Na ausência dos dois campos de perfil, o padrão de compatibilidade é `comprador`.

O servidor gera o UUID e normaliza nome/e-mail pelas regras existentes. A senha mantém a política de pelo menos 12 caracteres Unicode, UTF-8 válido, até 72 bytes e nenhum caractere de controle; não sofre trim. Tentativas de fornecer identificador, hash, privilégios ou indicador administrativo não alteram a conta criada. O papel também é validado pelo caso de uso, antes de acesso ao banco ou codificação.

Sucesso retorna **201** e o envelope abaixo, com dados normalizados:

```json
{
  "dados": {
    "id": "<uuid gerado pelo servidor>",
    "nome": "Pessoa Exemplo",
    "email": "pessoa@example.test",
    "papel": "comprador",
    "perfil": "comprador",
    "imagem": ""
  },
  "erro": null
}
```

`imagem` vazia permite a apresentação sem foto no frontend; não há criação de ateliê, slug, perfil ou foto nesta etapa. Campos de apresentação adicionais enviados pela UI, como território e técnica, não são persistidos pelo cadastro de contas. Esses dados pertencem à entrega de perfil do artesão (PI-171).

Erros preservam `{ "dados": null, "erro": { "codigo": "...", "mensagem": "..." } }`: **400 / REQUISICAO_INVALIDA** para entrada inválida, **409 / EMAIL_EM_USO** para duplicidade normalizada (inclusive concorrente) e **500 / CADASTRO_INDISPONIVEL** para falha interna sanitizada. Senhas, hashes e detalhes internos não são devolvidos.

O cadastro persiste somente hash bcrypt com salt; usa o índice único existente para impedir duas contas com o mesmo e-mail normalizado. Não foi necessária nova migration. A etapa 3 acrescenta o cookie JWT após a confirmação transacional da conta, preservando esse corpo de resposta. Se a assinatura falhar depois do commit, retorna 500 sem cookie; a conta permanece cadastrada e pode tentar login.

## Autenticação e sessão — etapa 3

- `POST /api/v1/auth/login` e alias temporário `/api/v1/auth/entrar`: recebem JSON com `email` e `senha`; retornam 200 com `{dados:usuarioPublico,erro:null}` e cookie `balaio_sessao`.
- `POST /api/v1/auth/cadastro`: retorna 201 e emite o mesmo cookie após persistir a conta.
- `POST /api/v1/auth/logout`: retorna 200 com `{dados:{encerrada:true},erro:null}` e expira o cookie, mesmo sem sessão ou com um token inválido/expirado.
- `GET /api/v1/artesao/me`: exige Bearer JWT válido de conta com papel atual `artesao`; retorna `{dados:{id,slug:"artesao-<UUID>"},erro:null}`. Comprador e administrador recebem 403. O catálogo usa essa identidade antes de publicar peças.

Login usa e-mail normalizado, senha literal e bcrypt existentes; não aplica retroativamente a política de cadastro. UUID e papel vêm exclusivamente da conta persistida, independentemente de campos extras enviados pelo cliente. Conta ausente e senha incorreta retornam o mesmo **401 / CREDENCIAIS_INVALIDAS**; uma comparação bcrypt fictícia, com hash gerado no startup, reduz a diferença de processamento entre esses caminhos. Dados inválidos retornam **400 / REQUISICAO_INVALIDA** e falhas internas de autenticação retornam **500 / AUTENTICACAO_INDISPONIVEL**, sem expor causas ou credenciais. Erros mantêm `{dados:null,erro:{codigo,mensagem}}` e não emitem sessão.

O DTO continua limitado a ID, nome, e-mail, papel, perfil e imagem. Para administrador, `perfil` é omitido, pois o contrato desse campo só admite comprador/artesão; `papel` permanece `admin`. Os sucessos de cadastro/login/logout incluem `Cache-Control: no-store`. JWT, hash e senha não aparecem no corpo de resposta.

JWT é assinado com **RS256** usando Spring Security JOSE gerido pelo BOM do projeto. Contém somente `sub` (UUID), `papel`, `iss`, `aud`, `iat`, `exp` e `jti`. O emissor usa UTC, identificador único por emissão e validade fixa de **3600 segundos**, sem refresh token. `organizador` permanece reservado para PI-210: a base atual persiste comprador/artesão/admin e o cadastro público não concede privilégios adicionais. [Referência técnica do Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Cookie: `balaio_sessao`, `HttpOnly`, `SameSite=Lax`, `Path=/api/v1`, sem `Domain`, `Max-Age=3600` e `Secure` por padrão. Logout usa o mesmo escopo e `Max-Age=0`. **Logout não revoga imediatamente o JWT**: uma cópia continua criptograficamente válida até `exp`, salvo políticas adicionais documentadas dos consumidores. Não há tabela de sessões, refresh ou lista de revogação.

### Configuração obrigatória por ambiente

O serviço exige a configuração no startup e falha com mensagem sanitizada se ela estiver ausente/inválida:

- `JWT_PRIVATE_KEY_LOCATION`: caminho absoluto ou URI `file:` de arquivo PEM PKCS#8 RSA CRT, no mínimo 3072 bits. Não aceita `classpath:`, URL remota, chave pública no lugar da privada, arquivo vazio/inválido ou chave fraca. No perfil `local`, o Compose usa `local-generated` para gerar a chave privada em volume próprio e publicar apenas a chave pública em outro volume. Fora do perfil `local`, esse valor é recusado. O conteúdo não deve ser colocado em uma variável de ambiente.
- `JWT_ISSUER`: emissor não vazio acordado com os consumidores.
- `JWT_AUDIENCE`: audiência não vazia acordada com os consumidores.
- `AUTH_ALLOWED_ORIGINS`: origens públicas exatas separadas por vírgula, com esquema/host/porta e sem caminho, query, fragmento, credenciais ou wildcard. Exemplo local: `http://localhost:4321`; `127.0.0.1` é uma origem diferente.
- `AUTH_COOKIE_SECURE`: padrão `true`. `false` só é aceito com **único perfil ativo `local`** e todas as origens permitidas usando HTTP. Mesmo nesse modo, uma requisição HTTPS direta emite `Secure`. `local` junto de outro perfil não habilita a exceção.

O profile deriva a pública correspondente e executa um self-check de assinatura/verificação antes de concluir a configuração. Não gera uma chave nova no startup de produção. Os testes geram material descartável em execução e não versionam chaves privadas.

Provisionar o material fora de todos os repositórios, com acesso restrito à identidade do processo e mount somente leitura. Em um terminal com OpenSSL, estes comandos exemplificam o provisionamento **manual**, usando caminhos externos escolhidos pelo operador:

```text
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out <arquivo-privado-externo.pem>
openssl pkey -in <arquivo-privado-externo.pem> -pubout -out <arquivo-publico-externo.pem>
```

Somente o profile recebe o arquivo privado. Gateway e serviços receberão apenas o arquivo **público** por configuração, usando os mesmos valores de issuer/audience na integração futura. Não distribuir a privada, imprimir seus componentes ou gerar arquivos dentro do Git. Os comandos são exemplos de procedimento, não foram executados para criar uma chave de ambiente nesta entrega. [OpenSSL genpkey](https://docs.openssl.org/3.5/man1/openssl-genpkey/), [OpenSSL pkey](https://docs.openssl.org/3.5/man1/openssl-pkey/).

O Compose local monta os volumes de chave automaticamente; fora do perfil `local`, forneça a chave privada e as variáveis de autenticação por ambiente. Uma substituição de chave deve ser coordenada com os consumidores: não há JWKS/rotação dinâmica nesta entrega e tokens emitidos com a chave anterior podem deixar de ser aceitos antes dos 60 minutos.

### Origin e limites da integração

Cadastro, login/alias e logout verificam `Origin` antes dos efeitos. Quando presente, ele deve corresponder exatamente à allowlist; `null`, múltiplos valores e origem externa retornam **403 / SEM_PERMISSAO**. Se houver cookie `balaio_sessao`, a ausência de Origin também é rejeitada. Requisições sem cookie e sem Origin são permitidas para clientes de API; adicionar Bearer não dispensa a regra quando há cookie. Para repetir logout com cookie no Bruno, enviar Origin permitido ou remover o cookie do cliente.

Não há confiança em `Host`, `Forwarded` ou `X-Forwarded-*` para desligar Secure; `server.forward-headers-strategy=none`. HTTPS público deve usar `AUTH_COOKIE_SECURE=true`, inclusive quando o TLS terminar no proxy. O CORS do gateway e a propagação de credenciais são trabalho da etapa 4; esta etapa não adiciona CORS permissivo.

O cookie restrito a `/api/v1` não chega ao SSR de `/account` ou `/dashboard`; esse desenho continua registrado no acompanhamento. Sessões opacas da Fake API não reconhecem JWT real. Cookies legados com `Path=/` precisam de limpeza no corte de integração; o logout atual limpa o cookie contratado com `Path=/api/v1`.

### Consulta do usuário autenticado

`GET /api/v1/usuario` recebe exatamente um `Authorization: Bearer <JWT>` e revalida a assinatura **RS256** com a chave pública local derivada da privada configurada. O gateway encaminha nesse header o token validado do cookie; uma chamada direta ao profile também precisa de Bearer. Cookies ou headers de identidade, UUID e papel enviados pelo cliente não autenticam essa rota.

Os sete claims são obrigatórios: `sub` UUID canônico, `papel` conhecido no domínio atual, `iss` e `aud` correspondentes à configuração, `iat` e `exp` NumericDate inteiros e `jti` textual não vazio. A validade deve ser positiva e de até 3600 segundos; `iat` admite até 30 segundos no futuro, enquanto `exp` é estrito, inclusive no instante exato de expiração. Se presente, `nbf` também é respeitado sem tolerância. Tipos inválidos, campos JSON duplicados, algoritmos alternativos e headers `jwk`, `jku`, `x5u` ou `x5c` são rejeitados; nenhuma chave remota é consultada.

Após a validação, o serviço consulta `ContasPort.buscarPorId` e retorna **200** com o mesmo `{dados:usuarioPublico,erro:null}` de login, usando nome, e-mail e papel **atuais do PostgreSQL**, mesmo se o papel do JWT tiver mudado desde a emissão. `perfil` continua omitido para administrador; senha, hash, JWT e indicador de troca não são expostos. Token ausente/inválido ou conta inexistente produzem o mesmo **401 / NAO_AUTENTICADO**, com envelope sanitizado. Sucesso e 401 incluem `Cache-Control: no-store` e não emitem cookie.

Essa validação fica restrita à consulta de usuário. Regras de autorização dos demais recursos e cadeias globais de segurança dos serviços permanecem nas etapas próprias.

## Contas e primeiro administrador

O domínio de `account` não depende de Spring ou JPA. Contas têm um único papel (`comprador`, `artesao` ou `admin`), e-mail normalizado/único, hash bcrypt e `trocaSenhaObrigatoria`. O cadastro público aceita exclusivamente `comprador` ou `artesao`; `admin` e `organizador` são rejeitados. A instrução da etapa 2 substitui a regra anterior que sempre cadastrava comprador.

O bootstrap usa `ADMIN_EMAIL` e `ADMIN_SENHA_INICIAL` enquanto o marcador estiver pendente. Cria o administrador com troca obrigatória e confirma o controle na mesma transação, sob bloqueio no PostgreSQL. Depois da conclusão, reinícios não redefinem senha ou papel. Credenciais não são gravadas no código nem registradas em mensagens.

No perfil `local`, quando ambas as variáveis estão ausentes, o bootstrap é ignorado para permitir o primeiro start sem `admin.env`. Se uma delas for informada, a validação existente continua exigindo o par válido.

`spring-security-crypto` fornece o `PasswordEncoder` bcrypt de custo 12. `spring-security-oauth2-jose` fornece assinatura RS256; não foi adicionada cadeia global de autenticação HTTP. `spring-boot-testcontainers` e o módulo PostgreSQL existem somente em escopo de teste.

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

A operação permite atribuir `comprador`, `artesao` ou `admin`, sempre consultando no banco o papel e a pendência de senha atuais do solicitante. Uma solicitação sem mudança de papel também exige autorização. O cadastro público permanece restrito a comprador/artesão e não utiliza esta entrada administrativa.

O caso de uso abre uma transação independente `REQUIRES_NEW`, com isolamento `READ_COMMITTED`. A ordem é: bloquear o singleton `controle_administracao`; autorizar e bloquear o solicitante; bloquear a conta alvo; contar administradores; aplicar a regra de domínio; atualizar somente o papel. A comparação com o papel anterior também protege a escrita contra estado incompatível, sem sobrescrever nome, e-mail, hash ou indicador. O cache JPA é sincronizado após a atualização.

`Conta.alterarPapel` lança `UltimoAdministradorException` se um administrador perder esse papel quando só existe um. A mesma regra se aplica à própria conta. A exceção é de negócio, sem dependência HTTP, e poderá ser traduzida para 409. Com dois administradores, uma despromoção pode confirmar e o restante passa a ficar protegido.

A trava do singleton permanece até commit ou rollback. Assim, a segunda operação concorrente conta os administradores depois da conclusão da primeira. `REQUIRES_NEW` evita herdar um snapshot antigo de uma transação externa `REPEATABLE_READ`; a contagem precisa refletir o estado confirmado após a espera. Esta é uma fronteira transacional independente: não a invoque de uma transação externa que já detenha o singleton ou as mesmas contas, para evitar bloqueio contra a própria transação suspensa.

O modelo atual não possui indicador de ativação nem operações de remoção/desativação. Portanto, contas existentes com papel `admin` compõem a contagem de administradores ativos nesta etapa. Pendência de troca restringe ações administrativas, mas não equivale a desativação. Não foram criadas operações de ciclo de vida; quando existirem, deverão reutilizar a trava e a verificação do último administrador antes de remover sua condição ativa. Curadoria de selo pertence à PI-177 e não integra esta entrega.

## Testes e escopo

Execute `.\mvnw.cmd -B -ntp clean verify` com JDK 25 e Docker ativo. A compilação limpa também remove classes e migrations antigas de `target/` depois de desfazer alterações. Os testes usam PostgreSQL 18 descartável via Testcontainers, sem usar o banco do projeto nem ler credenciais locais. Confira `java -version` e `JAVA_HOME` no terminal usado pelo Maven; a versão declarada no POM não seleciona automaticamente um JDK instalado.

Os testes de cadastro incluem comprador/artesão, ambos os aliases de perfil, payloads manipulados, dados inválidos, JSON bruto duplicado, ausência de credenciais/cookie nas respostas, duplicidade e corrida simultânea de e-mail normalizado. A corrida força os dois SELECTs a concluírem antes dos inserts, exercitando a restrição única real do PostgreSQL.

Os testes de `GET /api/v1/usuario` fazem chamadas HTTP reais com PostgreSQL descartável, verificam os dados e papéis atualizados após a emissão do token, ausência de dados sensíveis, conta removida, headers forjados e cookies sem Bearer. A validação criptográfica exercita assinatura incorreta, algoritmos alternativos, claims ausentes ou com tipos inválidos, JSON duplicado, chaves remotas, issuer/audience, validade máxima, tolerância de `iat` e expiração estrita com relógio controlado.

Os testes cobrem bootstrap com indicador pendente, senha atual correta/incorreta, política e não reutilização, hash novo e senha anterior inválida, remoção da obrigação somente no sucesso, rollback após escrita real, isolamento da própria conta, conflitos entre duas transações concorrentes e guard administrativo antes/depois da troca. Também verificam releitura na mesma transação e ausência de segredos nas representações e erros.

Para papéis, os testes verificam autorização pelo estado atual do banco, bloqueio de solicitantes comuns/pendentes, promoção válida, preservação dos demais dados, impossibilidade de autodespromover o último administrador, proteção do remanescente, rollback e duas despromoções concorrentes sob bloqueio PostgreSQL real. Também exercitam a entrada a partir de snapshots externos `REPEATABLE_READ` e tentativas de usar o identificador do alvo como autorização.

O cadastro da etapa 2 inclui adaptador HTTP, aplicação e persistência de contas. A etapa 3 acrescenta login, JWT, cookie e logout, restritos ao profile. Gateway e demais microsserviços não foram alterados. Bootstrap, troca de senha e administração continuam internos, sem novos endpoints administrativos.

## Recuperação de senha — US34

`POST /api/v1/auth/recuperacao` recebe `{ "email": "pessoa@example.test" }`. Para qualquer e-mail válido, cadastrado ou não, retorna **201** com `{ "dados": { "recebida": true }, "erro": null }`. Erros de formato retornam `400 REQUISICAO_INVALIDA`. Para uma conta existente, o serviço gera segredo aleatório de 256 bits, grava somente seu SHA-256 em `password_recovery` e solicita envio de link por e-mail após o commit. Um novo pedido é aceito após um minuto e invalida os links anteriores. A validade padrão é de uma hora.

O link aponta para `${PROFILE_FRONTEND_BASE_URL}/login/reset?token=...`. `POST /api/v1/auth/redefinicao` recebe `{ "token": "...", "novaSenha": "..." }`. Token ausente, vencido, inválido ou já consumido retorna `400 TOKEN_INVALIDO`; nova senha fora da política existente retorna `400 REQUISICAO_INVALIDA`. No sucesso, retorna **200** com `{ "dados": { "redefinida": true }, "erro": null }`. A atualização usa `ContasPort`, a mesma política e o mesmo codificador bcrypt do login PI-168. A troca e o consumo único ocorrem na mesma transação, sob bloqueio da conta, e limpam `troca_senha_obrigatoria` como a alteração de senha existente.

Configure externamente `PROFILE_FRONTEND_BASE_URL`, `PROFILE_RECOVERY_TOKEN_TTL` (ISO-8601, padrão `PT1H`), `PROFILE_MAIL_FROM`, `PROFILE_SMTP_HOST`, `PROFILE_SMTP_PORT`, `PROFILE_SMTP_USERNAME`, `PROFILE_SMTP_PASSWORD`, `PROFILE_SMTP_AUTH` e `PROFILE_SMTP_STARTTLS`. Use URL pública do frontend e credenciais de SMTP em secret store ou `.env` local ignorado pelo Git. Não versione segredos. Falhas de envio são registradas sem endereço, token, link ou senha, e não alteram a resposta pública.

**JWT emitidos antes da redefinição permanecem válidos até expirar.** PI-168 usa JWT RS256 sem lista de revogação nem tabela de sessões; esta integração não cria mecanismo paralelo. O login com a senha antiga falha, e o login com a nova funciona. A revogação imediata de JWT requer decisão de segurança e alteração coordenada dos validadores/consumidores. O logout continua apenas limpando o cookie no navegador, conforme o contrato existente.

Os testes US34 reutilizam o PostgreSQL 18 descartável de `PostgreSQLTestConfiguration`. Execute `.\mvnw.cmd -B -ntp clean verify` com JDK 25 e Docker ativo. O envio externo deve ser validado com uma caixa SMTP controlada e a URL pública do ambiente; os testes automatizados não precisam de credenciais reais.
## Midias do perfil — PI-214, Etapa 3

Branch baseada em `origin/PI-171` no commit `1e5beb3`.
O modulo `artisan.media` prepara operacoes de aplicacao para enviar, substituir,
recuperar e remover imagens. Os controllers atuais continuam usando os contratos
do PI-171. A Etapa 4 acrescenta os endpoints HTTP descritos abaixo.

O adapter usa AWS SDK Java v2 2.42.22, cliente sincrono e path-style. Recebe
`MEDIA_S3_ENDPOINT`, `MEDIA_S3_REGION`, `MEDIA_S3_BUCKET`,
`MEDIA_S3_ACCESS_KEY_ID` e `MEDIA_S3_SECRET_ACCESS_KEY`. As credenciais devem ser
da identidade consumidora provisionada pelo platform. Sem endpoint configurado,
os recursos existentes iniciam normalmente e operacoes S3 falham com erro de
indisponibilidade. Endpoint configurado exige credenciais explicitas.

V7 acrescenta `midias_perfil`, com UUID, perfil proprietario, chave, MIME, tamanho,
SHA-256, estado e data. Bytes permanecem somente no storage. O bucket e uma
configuracao da instalacao; sua alteracao requer migrar os objetos existentes.
O campo `imagem` guarda a referencia `/api/v1/arquivos/perfis/{midiaId}`, independente
do slug. A Etapa 4 disponibiliza este caminho. DTOs HTTP continuam
devolvendo `imagem`, sem serializar as chaves internas de `MidiaPerfil`.

JPEG/PNG/WebP sao decodificados, com limite de 2 MiB e 20 milhoes de pixels antes
da alocacao do raster. TwelveMonkeys ImageIO 3.12.0 acrescenta leitura WebP ao
ImageIO do Java. O arquivo e mantido no formato original.

O upload reserva uma linha PENDING e envia ao S3 fora de transacao de banco.
A ativacao bloqueia a linha do perfil, troca `imagem` e marca a anterior DELETE
na mesma transacao. Exclusao logica restaura o placeholder antes da exclusao
fisica. O worker consulta a fila a cada 60 segundos. A V8 registra no PostgreSQL
`proxima_tentativa`, reservando cada exclusao por cinco minutos antes da chamada
S3. Falhas e interrupcoes preservam esse prazo. Outros registros elegiveis podem
avancar enquanto a tentativa anterior aguarda. DELETEs repetidos sao idempotentes.
Linhas PENDING ou referencias abandonadas pelo PATCH aguardam 24 horas para
reconciliacao, inclusive apos crash ou resultado incerto de commit. A limpeza
confere novamente o vinculo sob bloqueio e nunca exclui uma imagem referenciada.
O intervalo pode ser configurado por `balaio.media.cleanup-delay`.

O PATCH preserva referencias legadas e aceita a referencia gerenciada atual.
Uma referencia gerenciada diferente deve ser vinculada pela operacao de midia.
O mesmo bloqueio e refresh coordenam PATCH e upload concorrentes. A V8 troca a
foreign key da midia para `ON DELETE RESTRICT`, bloqueando exclusao do perfil e
o cascade da conta enquanto existirem metadados de midia, inclusive pendentes.
O futuro fluxo de exclusao de contas devera solicitar limpeza de todas as midias
do perfil, aguardar confirmacao fisica e somente entao excluir a conta. Essa
protecao impede perder chaves S3 e ainda exige o futuro fluxo de orquestracao.

Erros de limpeza registram somente UUID da midia, etapa e categoria controlada.
Quando a selecao da fila falha, o identificador aparece como indisponivel.
Mensagens de excecao, chaves S3, credenciais e conteudo nao entram nesses logs.

Testes requerem Java 25 e Docker para PostgreSQL descartavel. Execute
`./mvnw test`. `S3MidiaIntegrationTest` e habilitado quando `MEDIA_S3_ENDPOINT`
esta definido e usa somente o par consumidor, criando e removendo um objeto
diagnostico unico em `profile/diagnostics/`. Nunca passar segredos literais na
linha de comando ou publicar a configuracao resolvida do Compose.

Validacao em 12/11/2026 com Java 25 em container e fontes copiados para tmpfs
Linux para evitar latencia do bind Windows. `mvn -o -B -ntp test` concluiu com
558 testes, zero falhas/erros e um teste S3 condicional ignorado. Esse teste foi
executado separadamente contra SeaweedFS 4.53 com o par consumidor e passou.
Os testes de banco usam PostgreSQL descartavel e verificam V5, associacao,
rollback, placeholder, slug alterado, concorrencia com PATCH e upload sem
transacao aberta. A V5 ainda sera aplicada ao ambiente principal ao publicar
a imagem do servico na etapa de integracao.

Revisao 3.1 em 13/11/2026 — o banco persistente local consultado estava na V4.
A V5 foi preservada e as correcoes usam uma V6 aditiva, compativel com instalacoes
que ja tenham aplicado V5. As provas de retomada recriam o worker usando a fila
persistida no PostgreSQL descartavel, simulando interrupcao antes do DELETE e
apos o DELETE antes da confirmacao. Os testes tambem cobrem 20 falhas persistentes
com progresso do lote seguinte, vinculo reativado e bloqueio do cascade.

Validacao 3.1 — a suite completa concluiu com 569 testes, zero falhas/erros e
um teste S3 condicional ignorado. O teste S3 foi executado separadamente e passou
contra o SeaweedFS local com a credencial consumidora. Foram acrescentados seis
testes de persistencia/retomada e cinco testes de logs. A V6 foi aplicada somente
nos PostgreSQL descartaveis da suite; o banco persistente local permanece na V4.

### Endpoints de imagens da Etapa 4

Todos os caminhos usam `/api/v1`. PUT e DELETE em `/artesao/me/imagem` exigem
JWT e conta artesao verificada no PostgreSQL. O proprietario vem da sessao.
PUT recebe `multipart/form-data`, campo `arquivo`, e substitui a imagem atual.
DELETE restaura `/fotos/ImagemBase.webp`, mantendo a limpeza fisica posterior.
Ambos retornam HTTP 200 com o perfil atualizado no envelope `{dados, erro}`,
usando o mesmo `PerfilArtesaoResponse` do PI-171.

GET e HEAD em `/arquivos/perfis/{midiaId}` sao publicos porque os perfis do
contrato atual sao publicos. Exigem midia ACTIVE e vinculo atual com `imagem`.
Imagens pendentes, substituidas ou removidas retornam 404. GET devolve bytes com
MIME validado e SHA-256 conferido. HEAD consulta somente metadados vinculados,
sem download S3. Ambos incluem Content-Length, Content-Type,
`X-Content-Type-Options=nosniff` e `Cache-Control=no-store` para conferir o vinculo
em cada leitura. O UUID permanece estavel se o slug mudar.

O Profile aplica 2 MiB por arquivo, 3 MiB por requisicao multipart e gravacao
temporaria em disco a partir de zero bytes. A resolucao lazy permite tratar
erros no envelope HTTP. Os limites continuam validos com transferencia chunked.
A aplicacao carrega o arquivo limitado em memoria apos autorizar o artesao.
O gateway encaminha o corpo em streaming e recusa antecipadamente um tamanho
declarado acima de 3 MiB nessa rota. A verificacao efetiva de cada arquivo e de
requisicoes sem Content-Length acontece no Profile. Formatos e limite de
20 milhoes de pixels usam `ValidarImagem`.

Erros usam o envelope existente. Os codigos HTTP sao 400 para arquivo vazio,
ausente ou requisicao invalida, 401 para sessao ausente/invalida, 403 para papel
ou origem recusados, 404 para midia indisponivel, 413 para tamanho excedido,
415 para formato nao suportado, 409 para conflito e 503 para indisponibilidade.
Excecoes S3 e SQL nao sao serializadas. Cookies nas mutacoes exigem Origin
permitido, inclusive no acesso direto ao Profile.

No platform, atualize somente os dois servicos com
`docker compose up --build -d --no-deps profile-service api-gateway`.
Em bancos compativeis com a develop ate V5, Flyway aplica V7/V8. Veja a nota de compatibilidade abaixo antes de atualizar bancos antigos.
O frontend devera enviar FormData e resolver o caminho de `imagem` pelo gateway
na Etapa 5. Os endpoints JSON e o campo `imagem` mantem os contratos do PI-171.

As propriedades multipart seguem a documentacao oficial do Spring Boot.
[Referencia de propriedades](https://docs.spring.io/spring-boot/appendix/application-properties/)

Validacao da Etapa 4 em 13/11/2026 — Java 25 em container, PostgreSQL descartavel
e SeaweedFS 4.53 local. A suite Profile concluiu com 587 testes, zero falhas/erros
e um teste S3 condicional ignorado. Gateway concluiu 231 testes, zero falhas/erros.
Foram acrescentados 18 casos HTTP no Profile e oito no gateway. A regressao inclui
as provas anteriores de concorrencia com PATCH, reserva persistida de limpeza e
protecao ON DELETE RESTRICT.

Integracao real em http://localhost:8080, com conta artesao descartavel criada e
autenticada via HTTP. Upload WebP com Bearer, substituicao PNG com cookie/Origin,
GET anonimo e HEAD passaram. SHA-256 original e recuperado identicos para ambas
as imagens. URLs substituidas/removidas retornaram 404 em GET e HEAD. DELETE
retornou o perfil com placeholder. Content-Type incorreto retornou 415 no envelope.
Envios explicitamente chunked acima de 2 MiB e 3 MiB retornaram 413 pelo gateway.

Hashes dos arquivos diagnosticos publicos usados no teste, em hexadecimal
`c90cff659645a312a28804965f3dbc34061338f7234ff5d6ddb2c57e9eadec15` para WebP e
`fa137cfeddf9be254e60055e23c43d5b8663f5292b3cf1453cd52cd2d7d9fcd7` para PNG.
V5 e V6 foram aplicadas automaticamente ao banco local na publicacao desta etapa.
As evidencias de V4 nas secoes anteriores descrevem o estado anterior.

A integracao com FormData, proxy e exibicao no navegador pertence a Etapa 5.
O teste HTTP desta etapa valida a cadeia gateway -> Profile -> S3.

A limpeza fisica foi confirmada pelo worker antes da exclusao de cada conta
exclusiva de diagnostico. Conferencia final encontrou zero contas/metadados dessas
fixtures, 13 containers saudaveis, os 11 containers fora da atualizacao com os
mesmos IDs, seis volumes originais e nove arquivos locais protegidos com hashes
identicos. A busca em memoria pelas quatro credenciais S3 reais encontrou zero
ocorrencias nos arquivos entregaveis e nos logs dos tres servicos conferidos.

## Integracao PI-214 sobre develop — 19/11/2026

A base desta entrega e `1adaab1`. O PI-171 atual e a refatoracao de recuperacao
foram preservados: DTO com id da conta, slug `artesao-<UUID da conta>`, criacao
do perfil no primeiro acesso de artesao antigo e um unico GET `/artesao/me`.
As adicoes de midia nao substituem esses contratos.

As evidencias V5/V6 nas secoes historicas acima descrevem a branch original
`feat/pi-214-entrega-profile-service`, que continua preservada. Na integracao
atual, V1 a V5 da develop permanecem byte a byte iguais. Midia entra em duas
novas migrations V7/V8; a numeracao V6 fica reservada a entrega legada.

Banco que ja aplicou a antiga `V5__create_midias_perfil.sql` e
`V6__protect_media_cleanup.sql` NAO e compativel automaticamente com esta
linhagem. Nao executar repair, alterar checksums ou apagar tabelas/volumes.
A transicao desse banco exige planejamento separado, copia protegida e prova
de restauracao. O ambiente novo baseado na develop usa V5 de slugs + V7/V8.
Nenhum banco persistente foi migrado durante a resolucao dos conflitos.

Sem MEDIA_S3_ENDPOINT, o servico inicia e as operacoes que necessitam do S3
retornam indisponibilidade; nunca ha fallback anonimo. Executar o preparador
explicito do Platform habilita o endpoint local e preserva credenciais existentes.
O perfil `storage` deve ser iniciado junto ao servico quando midia for habilitada.

Testes novos cobrem a atualizacao de um banco V5 da develop sem perda de perfil
ou alteracao dos checksums anteriores, e upload de um artesao sem perfil,
preservando o identificador e slug definidos pelo PI-171.

Foi reproduzida uma interrupcao de conexao em uploads rejeitados acima de 3 MiB.
O limite de descarte do Tomcat foi ajustado de forma finita para 4 MiB, permitindo
receber o envelope HTTP 413. Isso nao aumenta o limite aceito de arquivo (2 MiB)
nem de requisicao multipart (3 MiB).
Referencia: [HTTP Connector / maxSwallowSize](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).

### Validacao desta integracao

Em 19/11/2026: build limpo e suite completa com 611 casos, zero falhas/erros
e um teste S3 condicional ignorado. Os 19 casos HTTP passaram, incluindo o
HTTP 413 acima dos limites. Upgrade V5 da develop -> V7/V8 foi testado em
PostgreSQL descartavel, preservando perfil, imagem legada e checksums V1-V5.
A politica S3 real foi validada separadamente no Platform com quatro
credenciais aleatorias, dados em tmpfs e nenhum volume da aplicacao.
Nenhum banco ou container da aplicacao foi atualizado nesta resolucao.
