# Profile Service

Serviço dono das contas e dos perfis de compradores, artesãos e coletivos do Balaio. Fornece os identificadores de perfil usados pelos outros serviços.

## Estado atual

Há somente o esqueleto Spring Boot e dependências iniciais de persistência, validação e monitoramento. Não existem controllers, entidades, autenticação ou migrações de negócio. As rotas abaixo são planejadas a partir dos fluxos da [Fake API do frontend](https://github.com/lucaschavessf2/Balaio/blob/main/Frontend/docs/api.md), não implementadas.

## Responsabilidades e limites

- Manter contas, dados cadastrais e perfis públicos de compradores e artesãos.
- Manter dados de artesãos, ateliês e coletivos.
- Manter endereços e alterações de conta.
- Apoiar cadastro, login, logout e recuperação de conta; o provedor/modelo de segurança real ainda precisa ser definido.
- Gerenciar selo de artesão verificado e sua curadoria, caso essa divisão seja confirmada na implementação.
- Não armazenar peças, pedidos ou conversas: esses dados pertencem, respectivamente, ao catálogo, comércio e comunidade.

## API planejada

Prefixo `/api/v1`. Nenhuma rota está implementada; validar a atribuição final junto aos contratos e histórias antes de criar controllers.

| Método e rota | Uso |
| --- | --- |
| `POST /auth/cadastro`, `/auth/login`, `/auth/logout`, `/auth/recuperacao` | Cadastro, sessão local e pedido de recuperação |
| `GET/PATCH /usuario` | Consultar/atualizar a conta autenticada |
| `GET/POST/PATCH/DELETE /usuario/enderecos` | Gerenciar endereços do titular |
| `POST /conta/{id}/senha` | Alterar senha do titular |
| `GET /artesaos`, `GET /artesaos/{slug}` | Listar e consultar perfis |
| `GET /coletivos`, `GET /coletivos/{slug}` | Listar e consultar coletivos |
| `GET/PATCH /artesao/me` | Consultar/editar perfil do ateliê autenticado |
| `GET/POST /artesao/selo` | Consultar/solicitar selo |
| `GET /admin/curadoria`, `POST /admin/curadoria/{id}/decisao` | Analisar solicitação de selo |

A Fake API não envia e-mail real de recuperação. A autenticação entre gateway e serviços, autorização por papel e política de sessão devem ser definidas no backend real.

## Stack e desenvolvimento

- Java 25, Maven Wrapper, Spring Boot 4.1.1.
- Spring MVC, Spring Data JPA, Bean Validation, PostgreSQL, Flyway e Actuator.
- Requer JDK 25 e PostgreSQL. Use o repositório `platform` para infraestrutura local quando disponível e configure `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` com os valores próprios do serviço. Não compartilhe banco nem versione credenciais.
- Migrações em `src/main/resources/db/migration`, por exemplo `V1__create_profiles.sql`.
- O `application.yaml` atual define somente o nome; datasource e migrações ainda não foram criados.

Windows: ` .\mvnw.cmd spring-boot:run`, ` .\mvnw.cmd test`, ` .\mvnw.cmd package`. Em Linux/macOS, use `./mvnw`.

Com Actuator, consulte `/actuator/health`. O teste atual é apenas de contexto; adicione testes de autenticação, autorização e titularidade ao implementar os fluxos.

## OpenAPI / Swagger UI

Ainda não está no `pom.xml`. Recomendação: adicionar OpenAPI e Swagger UI ao criar os primeiros controllers, com `org.springdoc:springdoc-openapi-starter-webmvc-ui` 3.x para Spring Boot 4 (versão estável consultada: 3.1.1). URLs esperadas após configurar: `/swagger-ui/index.html` e `/v3/api-docs`.

## Referências

- [Contrato da API do frontend](https://github.com/lucaschavessf2/Balaio/blob/main/Frontend/docs/api.md)
- [springdoc-openapi](https://springdoc.org/)
