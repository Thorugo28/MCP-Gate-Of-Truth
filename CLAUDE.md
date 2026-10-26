# MCP Gate of Truth

Gateway MCP agregador em Java/Spring, inspirado no Bifrost (Maxim AI).
- Para fora: servidor MCP em `/mcp` (Streamable HTTP). Clientes como o Claude Code se conectam numa URL única.
- Para dentro: cliente MCP que conecta em N servidores upstream, agrega as tools e roteia as chamadas.
- O gateway não chama LLM. Só repassa mensagens MCP.

## Stack e decisões fixas (não mudar sem conversar)
- Spring Boot 4.1.x + Spring AI 2.0 (MCP Java SDK 2.0, spec MCP 2025-11-25). Maven, versões pelo BOM do Spring AI.
- Transporte: WebMVC + Streamable HTTP (`spring-ai-starter-mcp-server-webmvc`, não o starter STDIO).
- Cliente: `spring-ai-starter-mcp-client`, modo SYNC (`McpSyncClient` / `McpSyncServer`). Desde a fase 3, passo 4 (2026-10-26),
  o autoconfig do cliente fica desligado (`spring.ai.mcp.client.enabled: false`) e o gateway cria os clientes na
  `upstream/UpstreamClientFactory`, lendo as mesmas propriedades do yml; o starter fica pelas classes (SDK e propriedades).
- Tools dos upstreams registradas programaticamente em tempo de execução, nunca via `@Tool`.
- `spring.ai.mcp.client.toolcallback.enabled: false`: o atalho automático do Spring AI fica desligado de propósito.
- Sem starter de modelo (OpenAI/Anthropic).
- Spring Security só na etapa de controle de acesso (spring-ai-community/mcp-security).

## Ambiente
- Linux Mint, JDK/Maven via SDKMAN, Node v24.21.0 via nvm. Sem Docker.
- Subir: `export NOTION_TOKEN=ntn_...` e `./mvnw spring-boot:run`.
- O upstream do Notion é `@notionhq/notion-mcp-server` via STDIO, iniciado pelo gateway com o caminho absoluto do `npx`
  e `PATH` no `env` da conexão (o `npx` precisa achar o `node`).
- Os avisos "Cannot resolve property 'notion'" do IntelliJ no application.yml são falso positivo (chave de mapa).

## Estrutura de pacotes (decidido em 2026-10-23)
Pacotes por responsabilidade, não as camadas controller/service/repository (o `/mcp` é atendido pelo Spring AI e não há banco).
```
io.github.thorugo28.mcpgateoftruth   (confira o nome exato do último segmento)
├── upstream/   conexões com os servidores MCP de fora (ex.: UpstreamRegistry guarda os McpSyncClient e lista tools)
├── proxy/      o que o gateway expõe no /mcp (ex.: ToolMirror registra as tools no McpSyncServer e repassa o callTool)
├── routing/    ToolRouter: tabela nome exposto → (cliente, nome original) e o call
├── admin/      API REST /admin para o painel: controllers finos e DTOs (records)
├── security/   fase 4: chaves de acesso, filtro de autenticação e regras de quem vê qual tool
└── config/     @ConfigurationProperties e beans
```
- Fases futuras viram pacotes novos: `audit/` (logs e métricas).
- `security` não depende de `admin` nem de `proxy`; `routing` e `proxy` perguntam a `security` se a chave pode usar uma tool.
- `proxy` depende de `upstream`, nunca o contrário.
- `admin` só lê de `upstream` e `routing` (e chama tools pelo `ToolRouter`); nada depende de `admin`.
- Sem interfaces antes de existir uma segunda implementação; com um upstream só, classes concretas.

## Concluído: MVP (2026-10-23)
Claude Code → gateway → servidor do Notion criou uma página ponta a ponta.
- `upstream/UpstreamToolsLogger` loga as 24 tools do upstream "Notion API" na subida.
- `proxy/ToolMirror` registra cada tool do upstream no `McpSyncServer` (mesmo nome, descrição e inputSchema) e repassa o `callTool`.
- Argumentos que funcionaram em `API-post-page`: `parent: {"page_id": "..."}`, `properties: {"title": [{"text": {"content": "..."}}]}`.
- WARNs de BeanPostProcessorChecker e "No tool/sampling/elicitation methods found" são esperados.

## Concluído: fase 2, vários upstreams com prefixo e roteamento (2026-10-24)
Objetivo: dois upstreams no mesmo `/mcp`, cada tool com o prefixo do seu upstream, e cada chamada indo para o cliente certo.
Pronto quando: o Claude Code, num único pedido, cria uma página no Notion e um arquivo na pasta sandbox, os dois passando pelo gateway.

1. ✅ Segundo upstream: servidor de filesystem (`@modelcontextprotocol/server-filesystem`), sem conta nem token.
   Criar uma pasta só para isso (ex.: `~/mcp-sandbox`), porque ele só acessa as pastas passadas como argumento.
   Nova conexão STDIO `filesystem` no application.yml, com o mesmo `command` absoluto do `npx` e o mesmo `PATH` no `env`:
   `args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/thorugo/mcp-sandbox"]`.
   Pronto quando: o `UpstreamToolsLogger` loga as tools dos dois upstreams.
   Feito: loga "Notion API" (24) e "secure-filesystem-server" (14); o `ToolMirror` espelha as 38, ainda sem prefixo.
2. ✅ Saber de qual chave do yml veio cada `McpSyncClient`. O prefixo é a chave (`notion`, `filesystem`), nunca o nome que o servidor anuncia.
   A confirmar: o Spring AI parece montar o `clientInfo` do cliente como `<client.name> - <chave>`. Logar `getClientInfo()` de cada cliente
   para conferir antes de depender disso. Se não der para extrair a chave de forma confiável, criar os clientes na mão a partir de uma
   configuração própria é o plano B (conversar antes).
   Pronto quando: existe um `upstream/UpstreamRegistry` com `Map<String, McpSyncClient>` indexado pela chave.
   Feito: confirmado `clientInfo` = `gate-of-truth-client - notion` (template `"<name> - <chave>"` no `McpClientAutoConfiguration`).
   `UpstreamRegistry` tira o prefixo `<McpClientCommonProperties.getName()> - ` e falha na subida se o nome não bater.
3. ✅ Prefixo nos nomes: tool exposta = `<chave>__<nome original>` (ex.: `notion__API-post-page`, `filesystem__read_file`).
   Separador `__` porque nomes de tool aceitam só letras, números, `_` e `-` em vários clientes. Nomes curtos: o Claude Code
   ainda acrescenta `mcp__gate-of-truth__` na frente, e há limite de tamanho.
   Pronto quando: `tools/list` no `/mcp` mostra todas as tools com prefixo, e nenhuma sem.
   Feito: `ToolMirror` usa o `UpstreamRegistry`, copia o `Tool` inteiro trocando só o nome; 38 tools, todas com prefixo.
   Maior nome: `notion__API-list-data-source-templates` (38; 58 com `mcp__gate-of-truth__`).
4. ✅ Roteamento (pacote novo `routing/`): tabela `nome exposto → (cliente, nome original)`, preenchida quando o `ToolMirror` registra as tools.
   O handler consulta a tabela, chama o cliente certo com o nome **original** (sem prefixo) e devolve o resultado sem alterar.
   Nome desconhecido devolve erro de tool, não exceção.
   Pronto quando: `tools/call` em `notion__API-get-self` e em `filesystem__list_allowed_directories` respondem, cada um pelo seu upstream.
   Feito: `routing/ToolRouter` guarda a tabela (`register` falha se o nome exposto repetir) e faz o `call`; o `ToolMirror` registra
   cada tool nele e todo handler chama `router.call(request)`. Nome fora da tabela devolve `CallToolResult` com `isError: true`
   (coberto por `ToolRouterTest`). Pelo `/mcp`, um nome não registrado nem chega no router: o SDK responde `-32602 Unknown tool`.
5. ✅ Ponta a ponta com o Claude Code (remover e adicionar de novo o `gate-of-truth` se as tools antigas ficarem em cache).
   Feito (2026-10-24): o arquivo `~/mcp-sandbox/ola.txt` foi criado via `filesystem__write_file`; depois, só com tools do gateway,
   o Claude Code leu o arquivo (`filesystem__read_text_file`), achou "Teste MCP" (`notion__API-post-search`) e criou a página filha
   "Fase 2" com o conteúdo do arquivo (`notion__API-post-page`).

Fica fora da fase 2: upstream fora do ar (hoje, se um `npx` falhar, o gateway inteiro não sobe; isso é fase 3), filtro de tools e autenticação.

## Concluído: API /admin para o painel (etapa A, só leitura + playground) (2026-10-25)
Objetivo: uma API REST, separada do `/mcp`, que o front em Angular (projeto separado) consome direto, sem dados mockados.
O `/mcp` não serve para o front: é JSON-RPC com sessão e SSE, feito para cliente MCP.
Pronto quando: o Angular lista os upstreams, mostra as tools de cada um e chama uma tool pelo playground, tudo com dados reais.

Contrato (JSON; DTOs como `record` em `admin/`):
- `GET /admin/upstreams` → `[{ "key", "serverName", "serverVersion", "status", "toolCount", "attempts", "nextRetryAt" }]`
  (fase 3: `status` vem do health check; `attempts` e `nextRetryAt` ISO-8601 UTC só em DOWN, com `serverName`/`serverVersion` `null`)
  - `key`: chave do yml (do `UpstreamRegistry`). `serverName`/`serverVersion`: `client.getServerInfo()`.
  - `status`: `"UP"` ou `"DOWN"`, lido do `UpstreamRegistry` (atualizado pelo health check da fase 3); não pinga na requisição.
- `GET /admin/upstreams/{key}/tools` → `[{ "name", "originalName", "description", "inputSchema" }]`
  - `name` é o nome exposto (com prefixo), `originalName` o do upstream. `inputSchema` vai como veio (JSON Schema).
  - `key` desconhecida → 404.
- `POST /admin/tools/{name}/call`, corpo = objeto JSON com os arguments (ex.: `{}` ou `{"path": "..."}`) → o `CallToolResult`
  (`content`, `isError`, …) no mesmo formato que o `/mcp` devolve.
  - Usa o `ToolRouter.call(...)` que já existe, para o playground seguir exatamente o caminho do `/mcp`.
  - Nome fora do router → 404. Erro da tool (`isError: true`) → 200 com o resultado, porque é resposta válida da tool.
  - Conferir a serialização: se o Jackson do Spring não gerar o JSON igual ao do SDK, mapear para um DTO em vez de brigar com ele.

Passos:
1. ✅ `GET /admin/upstreams`. Pronto quando: o curl mostra `notion` e `filesystem` com `UP` e 24/14 tools.
   Feito: `admin/UpstreamController`; `toolCount` vem do `ToolRouter.routesOf(key)` (o que está exposto), não de um `listTools` novo.
2. ✅ `GET /admin/upstreams/{key}/tools`. Pronto quando: `/admin/upstreams/filesystem/tools` lista 14 tools com prefixo e schema; `/admin/upstreams/xpto/tools` dá 404.
   Feito: o `ToolRouter.Route` agora guarda a chave do upstream e o `Tool` original; `register(exposedName, key, client, tool)`.
   No SDK 2.0 o `inputSchema` já é `Map<String, Object>`, e vai assim no `ToolDto`. Lista ordenada pelo nome exposto.
3. ✅ `POST /admin/tools/{name}/call`. Pronto quando: `filesystem__list_allowed_directories` com `{}` devolve a sandbox e um nome inexistente dá 404.
   Feito: `admin/ToolCallController` devolve o `CallToolResult` direto; o Jackson do Spring gera o mesmo JSON do SDK
   (`content[].type`, `isError`, `structuredContent`), sem DTO. `isError` some do JSON quando o upstream não manda.
4. ✅ CORS para `/admin/**`, com origens vindas de propriedade (ex.: `gate.admin.cors-origins`, padrão `http://localhost:4200`),
   via `WebMvcConfigurer` em `config/`. Não aplicar CORS ao `/mcp`.
   Pronto quando: o Angular em `localhost:4200` chama a API sem erro de CORS no console.
   Feito no back (`config/AdminProperties` + `config/AdminCorsConfig`, GET/POST); preflight testado por curl e no teste,
   `/mcp` sem header de CORS. Conferido pelo Angular em 2026-10-25.
5. ✅ Testes com `@WebMvcTest` nos controllers (registry e router mockados): status 200/404 e formato do JSON.
   Feito: `UpstreamControllerTest` e `ToolCallControllerTest` (inclui CORS liberado para 4200 e 403 para outra origem).

Cuidados:
- `/admin` fica aberto até a fase 4 e o playground executa tools de verdade (inclusive `write_file` e `API-post-page`).
  Enquanto isso, subir só local: `server.address: 127.0.0.1` no application.yml.
- Controllers sem lógica própria: só consultam `UpstreamRegistry` e `ToolRouter`. Se faltar algo (ex.: o router expor
  a lista de rotas ou o `Tool` original), acrescentar um método lá, não duplicar estado em `admin/`.
- Nada de ligar/desligar tools, cadastrar upstreams ou API keys aqui: isso é etapa B, depois da fase 4.

## Concluído: fase 3, resiliência (2026-10-26)
Objetivo: um upstream com problema não derruba nem trava o gateway; quando ele volta, as tools voltam sozinhas.
Pronto quando: com o gateway no ar, `pkill -f notion-mcp-server` → o filesystem continua respondendo pelo `/mcp`, o painel mostra
`notion` como `DOWN` e o `tools/list` cai para 14; depois, sem reiniciar o gateway, o Notion volta e as 24 tools reaparecem.

Passos:
0. ✅ Investigar antes de codar (só leitura do código do Spring AI 2.0 e do MCP SDK 2.0; anotar aqui o que achar):
   a) Existe `spring.ai.mcp.client.initialized: false`? Com ele, os clientes são criados sem subir o processo e sem o handshake,
      e o gateway chama `initialize()` de cada um? Se sim, a subida tolerante (passo 1) não precisa criar clientes na mão.
   b) Um `McpSyncClient` cujo processo STDIO morreu pode ser inicializado de novo, ou o transporte fecha de vez?
      Se fecha de vez, a reconexão (passo 4) exige criar um cliente novo a partir das propriedades da conexão do yml
      (`McpStdioClientProperties`): é o "plano B" da fase 2. Conversar antes de seguir por ele.
   c) Como avisar os clientes do gateway: confirmar que `McpSyncServer.removeTool`/`addTool` disparam `notifications/tools/list_changed`.
   d) Como receber o `list_changed` de um upstream no Spring AI (provavelmente um `McpSyncClientCustomizer` com `toolsChangeConsumer`).
   Feito (2026-10-25), lido nas fontes do Spring AI 2.0.1 e do MCP SDK 2.0.0:
   a) Sim. `spring.ai.mcp.client.initialized` (padrão `true`) só controla o `client.initialize()` no fim do
      `McpClientAutoConfiguration.mcpSyncClients`; com `false` o bean é criado sem subir o processo (o `StdioClientTransport`
      só faz `ProcessBuilder.start()` no `connect`, chamado no initialize). Não precisa criar cliente na mão no passo 1.
      Pegadinhas: (1) toda operação do cliente (`listTools`, `callTool`, `ping`) faz initialize implícito se ele não estiver
      inicializado (`LifecycleInitializer.withInitialization`), e depois de uma falha o estado volta a "não inicializado";
      então **nunca chamar nada num cliente DOWN**, senão ele tenta de novo no mesmo transporte (ver b) e pode subir outro `npx`.
      (2) O handshake tem timeout próprio, `initializationTimeout` (20s padrão do `McpClient`, o Spring AI não mexe), separado
      do `request-timeout`. (3) `getServerInfo()` devolve `null` se o cliente não inicializou.
      Bônus: o Spring AI também põe a chave do yml em `clientInfo.title()`.
   b) Fecha de vez. No `StdioClientTransport` os sinks são `final` e unicast (aceitam um assinante só), `isClosing` nunca volta
      a `false` e os schedulers são descartados no close. Um transporte não reconecta, então **reconexão (passo 4) = cliente
      novo**, montado a partir de `McpStdioClientProperties.getConnections().get(chave).toServerParameters()` e
      `McpClient.sync(new StdioClientTransport(...))`. É o plano B: conversar antes do passo 4.
   c) Confirmado. `McpAsyncServer.addTool`/`removeTool` mandam `notifications/tools/list_changed` quando
      `capabilities.tools.listChanged` é true, e o Spring AI liga isso por padrão (`spring.ai.mcp.server.tool-change-notification`).
      Detalhe: é **uma notificação por tool** (tirar o Notion manda 24). `addTool` com nome repetido substitui a tool com um WARN;
      `removeTool` de nome inexistente só loga WARN.
   d) Mais fácil do que o previsto: o Spring AI já registra o `McpSyncToolsChangeEventEmmiter` (um `McpClientCustomizer`), que
      publica `org.springframework.ai.mcp.McpToolsChangedEvent(connectionName, tools)` no Spring. Basta um `@EventListener`;
      `connectionName` é a chave do yml. Atenção: só vale para clientes montados pelo Spring AI. O cliente novo do passo 4
      precisa receber o `toolsChangeConsumer` na mão.
1. ✅ Subida tolerante. Cada upstream é inicializado separadamente; se falhar, fica `DOWN`, sem tools, e o gateway sobe com os outros.
   Pronto quando: com a conexão `notion` apontando para um pacote inexistente, o gateway sobe, o filesystem funciona pelo `/mcp`
   e `/admin/upstreams` mostra `notion` `DOWN` com 0 tools.
   Feito: `initialized: false` no yml; o `UpstreamRegistry` chama `initialize()` de cada cliente no construtor e, se falhar,
   faz `closeGracefully()` (mata o processo) e deixa o upstream fora de `connected()`. `clients()` tem todos, `connected()` só os UP
   (é o que `ToolMirror` e `UpstreamToolsLogger` usam), `status(key)` dá DOWN sem tocar no cliente ou o resultado do ping.
   No `/admin/upstreams`, upstream DOWN vem com `serverName`/`serverVersion` `null` e `toolCount` 0; `/admin/upstreams/notion/tools` dá `[]`.
   Testado com `--spring.ai.mcp.client.stdio.connections.notion.args=-y,@notionhq/pacote-que-nao-existe-xyz` (trocar só
   `args[1]` pela linha de comando quebra o bind): sobe com 14 tools no `/mcp`, filesystem responde, nenhum processo sobra.
   O upstream quebrado atrasa a subida em ~20s (`initializationTimeout`), mesmo com o `npx` morrendo logo; inicializar em
   paralelo ou baixar esse timeout fica para o passo 2.
2. ✅ Timeouts curtos, por propriedade (ex.: `gate.upstreams.ping-timeout: 3s`, `gate.upstreams.call-timeout: 30s`).
   Se o SDK não aceitar timeout por chamada, rodar a chamada num executor e usar `Future.get(timeout)`.
   Pronto quando: com o processo do Notion congelado (`pkill -STOP -f notion-mcp-server`), `/admin/upstreams` responde em ~3s
   e uma chamada `notion__...` devolve erro de tool em vez de travar. (`pkill -CONT -f notion-mcp-server` para descongelar.)
   Feito: `config/UpstreamProperties` (`gate.upstreams.init-timeout`/`ping-timeout`/`call-timeout`, padrões 20s/60s/60s =
   comportamento de antes). O SDK não tem timeout por chamada, então `upstream/UpstreamExecutor` roda a chamada numa virtual
   thread e espera com `Future.get(timeout)` (estourou: `cancel(true)`). O `ToolRouter` usa o call-timeout e devolve
   `isError: true` com "Upstream 'notion' não respondeu em 5s" (ou "Falha ao chamar o upstream ...: <msg>" se o cliente lançar).
   O `UpstreamRegistry.status` usa o ping-timeout. O init-timeout entra num `McpClientCustomizer` em `config/UpstreamClientConfig`.
   Testado com 5s nos três (bloco marcado como TESTE no application.yml): Notion congelado → `/admin/upstreams` em 5,0s com
   `notion` DOWN, `notion__API-get-self` erro de tool em 5,0s pelo `/mcp` e pelo playground, filesystem normal; descongelado,
   volta a UP. Pacote inexistente atrasa a subida 5s em vez de 20s.
   **Cuidado no teste:** `pkill -f notion-mcp-server` congela o Notion de todo gateway rodando na máquina. Pegar só os filhos do
   java da porta testada (`ss -ltnp | grep :8081` → pid → `pgrep -P` recursivo).
   Ok dado em 2026-10-25; bloco de TESTE removido, valem os padrões (20s/60s/60s; o ping foi para 3s no passo 3). Para testar de novo, passar
   `--gate.upstreams.call-timeout=5s` (etc.) na linha de comando.
   Obs.: o `/admin/upstreams` pinga os upstreams um depois do outro, então o pior caso é ping-timeout × upstreams travados
   (o passo 3 resolve, lendo status guardado).
3. ✅ Health check. Um `@Scheduled` (ex.: `gate.upstreams.health-interval: 15s`) pinga cada upstream e guarda o status num lugar só
   em `upstream/`. O `/admin` passa a ler esse status guardado, sem pingar na hora da requisição.
   Na transição UP → DOWN: tirar as tools daquele upstream do `McpSyncServer` e do `ToolRouter` (o `ToolRouter` vai precisar de um
   `unregister` por chave) e fechar o cliente, para não sobrar processo zumbi.
   Pronto quando: `pkill -f notion-mcp-server` → em até 15s o painel mostra `DOWN` e o `tools/list` do `/mcp` tem 14 tools.
   Feito: o `UpstreamRegistry` é o único lugar do status (UP = estar em `connected()`, agora mutável). O
   `upstream/UpstreamHealthCheck` (`SchedulingConfigurer`, fixed delay de `gate.upstreams.health-interval`, padrão 10s; `@EnableScheduling`
   em `config/UpstreamClientConfig`) pinga os conectados em paralelo com o ping-timeout; falhou → `registry.markDown(key)`, que tira
   de `connected()`, publica `upstream/UpstreamDownEvent` e fecha o cliente (nessa ordem; só a 1ª chamada por chave faz algo).
   O `proxy/ToolMirror` escuta o evento: `ToolRouter.unregister(key)` devolve os nomes e cada um sai do `McpSyncServer`.
   `upstream` não conhece `proxy`. O `/admin` lê `registry.status(key)` sem pingar (responde em ms). O `ToolRouter` checa
   `registry.isConnected` antes de chamar: "Upstream 'notion' indisponível" na janela entre cair e a rota sair.
   Upstream que caiu fica com `serverName`/`serverVersion` `null` (o close zera o resultado do initialize).
   Testado na 8081 (matando só os filhos do java da porta): 24 `list_changed` no stream SSE, `tools/list` com 14, filesystem
   normal, chamada `notion__...` dá 404 no playground, nenhum processo sobra (`npx` sai com 143 = SIGTERM).
   **Achado:** processo morto não faz o ping falhar na hora; o SDK não percebe o transporte fechado e o ping espera o
   ping-timeout inteiro. Detecção = até health-interval + ping-timeout: com o padrão de 60s deu 61s; com
   `--gate.upstreams.ping-timeout=3s`, 17,8s. Decidido em 2026-10-26: padrões `ping-timeout: 3s` e `health-interval: 10s`
   (pior caso ~13s); `init-timeout` 20s e `call-timeout` 60s continuam. Com os padrões novos: DOWN em 12,1s, 14 tools.
4. ✅ Reconexão. Upstream `DOWN` é tentado de novo com backoff (ex.: 15s, 30s, 60s, teto de 5 min). Ao conectar, as tools são
   registradas de novo (no `McpSyncServer` e no `ToolRouter`) e o status volta a `UP`. Depende do que o passo 0b mostrar.
   Pronto quando: depois do `pkill`, o gateway sobe o `npx` de novo sozinho e as 24 tools do Notion reaparecem sem reiniciar.
   Decidido (2026-10-26): plano B completo, sem status `RECONNECTING`, tentativas infinitas com teto.
   Feito: `spring.ai.mcp.client.enabled: false`; `config/UpstreamClientConfig` liga `McpStdioClientProperties` e
   `McpClientCommonProperties` do Spring AI e um `Clock`. `upstream/UpstreamClientFactory` cria cliente novo por chave com a
   receita do Spring AI (clientInfo `"<name> - <chave>"`, title, request-timeout) + init-timeout; chaves em ordem alfabética.
   O `UpstreamRegistry` não lê mais o clientInfo: tem `keys()`, `connected()` (UP) e um `Retry(attempts, nextAt)` por DOWN.
   `reconnect(key)` cria cliente novo; deu certo → UP + `UpstreamUpEvent` (o `ToolMirror` lista e registra as tools; se falhar,
   `markDown` de novo); falhou → fecha o cliente e dobra a espera. Espera: `gate.upstreams.reconnect-initial-delay` (15s)
   × 2^tentativas, teto `reconnect-max-delay` (5m). Zera ao reconectar. Upstream DOWN desde a subida entra no mesmo ciclo.
   O health check faz pings e reconexões na mesma rodada, uma tarefa por upstream; como a rodada espera todas e o atraso é
   fixo, as transições de um upstream nunca correm juntas.
   `/admin/upstreams` ganhou `attempts` e `nextRetryAt` (null em UP), lidos de `registry.states()`.
   Testado na 8081 (matando só os filhos do java da porta): DOWN em 9s, de volta UP 22s depois (15s arredondado para a rodada
   + handshake), 24 tools, `tools/list` 38, 48 `list_changed` (24 saindo, 24 voltando), chamada ao Notion chega à API.
   Regressão do passo 1 com pacote inexistente (`--gate.upstreams.init-timeout=3s`): sobe, filesystem ok, tentativas a cada
   30s e 60s, `attempts` subindo no painel, nenhum processo sobra.
   Atenção para o passo 5: com o autoconfig desligado, o `McpToolsChangedEvent` do Spring AI (item 0d) não existe mais;
   o `toolsChangeConsumer` tem que ser ligado na `UpstreamClientFactory`.
5. ✅ Repasse do `list_changed` dos upstreams. Quando um upstream avisar que a lista mudou, sincronizar só as tools dele:
   remover as que sumiram, adicionar as novas, manter as iguais. O filesystem anuncia `listChanged=true`.
   Pronto quando: teste unitário da sincronização (lista antiga × lista nova) passando; teste manual se der para provocar o aviso.
   Feito: a `UpstreamClientFactory` liga `toolsChangeConsumer` → `upstream/UpstreamToolsChangedEvent(key, tools)` (o SDK já
   rebusca a lista inteira e chama o consumer numa thread do `boundedElastic`). `proxy/ToolDiff` (função pura) separa removidas,
   novas e alteradas (mesmo nome, `Tool` diferente) pelos nomes originais. O `ToolMirror` aplica só a diferença num método
   `apply` usado pela subida, pela reconexão e pelo aviso, o que torna os três idempotentes (um aviso no meio de uma
   reconexão não registra nada duas vezes); alterada = `router.remove` + `register` e `addTool`, que substitui no servidor
   (WARN "Replace existing Tool" esperado). Aviso de upstream DOWN é ignorado. Os métodos do `ToolMirror` que mexem nas tools
   são `synchronized`, porque os eventos chegam do health check e das threads dos clientes. `ToolRouter.remove(nome)` novo.
   Testes: `ToolDiffTest` (o unitário pedido) e `ToolMirrorTest` com `ToolRouter` de verdade.
   Teste manual: servidor MCP mínimo em Node (sem dependências) como upstream extra `dyn` pela linha de comando
   (`--spring.ai.mcp.client.stdio.connections.dyn.command=<node>` e `.args=<script>`), que 15s depois do handshake troca
   alpha+beta por beta(v2)+gamma e manda `list_changed`. Resultado: `-1 +1 ~1`, `dyn__alpha` some (404), `dyn__beta` com a
   descrição nova, `dyn__gamma` responde, 3 `list_changed` para o cliente do gateway.

Cuidados:
- O estado de cada upstream (cliente, status, tentativas) muda numa thread de agendamento enquanto chamadas chegam em outras:
  usar estruturas thread-safe e não registrar a mesma tool duas vezes (o `register` do `ToolRouter` falha com nome repetido).
- Chamada para upstream `DOWN` que ainda chegue ao router: `CallToolResult` com `isError: true` e uma mensagem clara
  ("upstream notion indisponível"), nunca exceção.
- Status ficou só `UP`/`DOWN` (decidido em 2026-10-26); a reconexão aparece em `attempts` e `nextRetryAt`.
- Fora da fase 3: autenticação, filtro de tools e métricas.

## Estado atual: fase 4, controle de acesso (API key)
Objetivo: ninguém chama o gateway sem chave, e cada chave só vê e só chama as tools liberadas para ela.
Hoje qualquer processo na máquina chama qualquer tool (inclusive `write_file` e `API-post-page`), pelo `/mcp` e pelo `/admin`.
Pronto quando: sem chave, `/mcp` e `/admin` dão 401; duas chaves diferentes veem listas de tools diferentes no `tools/list`
e uma não consegue chamar a tool que não vê; o Claude Code e o painel funcionam com as suas chaves.

Decidido (2026-10-26): chaves no `application.yml`, valor sempre de variável de ambiente (igual ao `NOTION_TOKEN`); sem banco.
Cadastro de chaves pelo painel é etapa B. OAuth fica para depois desta fase (próxima seção do roadmap).

Configuração proposta (`config/SecurityProperties`, prefixo `gate.security`):
```yaml
gate:
  security:
    keys:
      cli:                          # nome da chave: aparece em log (e na auditoria da fase 5), nunca o valor
        value: ${GATE_KEY_CLI}
        upstreams: ["*"]            # "*" = todos; ou lista de chaves de upstream, ex.: ["filesystem"]
        admin: false                # pode usar o /admin?
      painel:
        value: ${GATE_KEY_PANEL}
        upstreams: ["*"]
        admin: true
```
Header: `Authorization: Bearer <valor>` nos dois caminhos (casa com o OAuth depois). Gerar valores com `openssl rand -hex 32`.

Passos:
0. ✅ Investigar antes de codar (ler código e docs; anotar aqui o que achar):
   a) O spring-ai-community/mcp-security tem suporte a API key? Qual header e formato de chave, como as chaves são buscadas
      (interface de repositório que dá para implementar lendo o yml?), e se funciona com Spring Boot 4.1 / Spring AI 2.0.
      Se não servir para API key, usar Spring Security puro (um filtro em `security/`) e deixar o mcp-security para o OAuth.
   b) Como a identidade da chave chega ao handler da tool no `/mcp` (`McpTransportContext`, `SecurityContext`, outro?).
      Cuidado: o `UpstreamExecutor` roda a chamada numa virtual thread; o `SecurityContext` não vai junto sozinho.
   c) **A decisão grande:** dá para filtrar o `tools/list` por chave no SDK 2.0? O `McpSyncServer` tem uma lista de tools só,
      igual para todas as sessões. Procurar um gancho por sessão/requisição. Se não houver, listar as opções
      (ex.: filtrar só no `tools/call` e aceitar a lista cheia; um servidor por chave; interceptar a resposta) e conversar antes do passo 4.
   Feito (2026-10-26), lido nas fontes do MCP SDK 2.0.0, do Spring AI 2.0.1 e do mcp-security (main, 0.1.14 no Central):
   a) Tem API key, só para WebMVC. Versão 0.1.x = Spring AI 2.0 (o pom da main usa Boot 4.1.0 e Spring AI 2.0.0); 0.0.6 era a do 1.1.
      Como funciona (`McpApiKeyConfigurer` dentro do `HttpSecurity`): header `X-API-Key` (dá para trocar o nome, ou passar um
      `AuthenticationConverter` próprio para ler `Authorization: Bearer`), valor no formato `<id>.<segredo>`. As chaves vêm de um
      `ApiKeyEntityRepository.findByKeyId(id)` (dá para implementar lendo o yml; tem um `InMemoryApiKeyEntityRepository`).
      O segredo é conferido por um `DelegatingPasswordEncoder` fixo: o valor guardado tem que ser `{bcrypt}...` (caro, roda a cada
      POST do `/mcp`, a própria doc avisa) ou `{noop}...` (comparação com `equals`, não é tempo constante). O configurer não deixa
      trocar o encoder. O 401 sai sem `WWW-Authenticate` (`HttpStatusEntryPoint`). Bônus: `sessionBinding()` amarra o
      `Mcp-Session-Id` à chave que abriu a sessão (boa prática da spec), e ele ignora CSRF quando há chave.
      Conclusão: serve, mas obriga o formato `<id>.<segredo>` e bcrypt/noop. Alternativa: Spring Security puro com um filtro
      nosso em `security/` (Bearer, `MessageDigest.isEqual`, formato do yml acima), deixando o mcp-security para o OAuth (4b).
   b) Dois caminhos até o handler da tool:
      - `SecurityContextHolder`: o Spring AI liga `immediateExecution(true)` no servidor SYNC em servlet, e o transporte WebMVC
        roda o pedido com `.block()` na thread da requisição; o handler não pula para o `boundedElastic`, então o contexto do
        filtro está lá (é o que faz o `@PreAuthorize` do mcp-security funcionar). Só é preciso ler **antes** do `UpstreamExecutor`,
        que troca de thread.
      - `McpTransportContext`: `exchange.transportContext()` no handler, preenchido por um `McpTransportContextExtractor<ServerRequest>`
        no builder do `WebMvcStreamableServerTransportProvider`. O Spring AI monta o provider sem extractor (contexto vazio);
        para usar, é preciso declarar o bean do provider (`@ConditionalOnMissingBean`). Mais explícito, não depende de thread.
   c) **Não dá no servidor único.** O handler do `tools/list` é fixo dentro do `McpAsyncServer`
      (`this.tools.stream().map(...::tool)`, sem filtro, sem olhar a sessão nem o contexto), o mapa de handlers é montado no
      construtor e o builder não tem opção de filtro. Também o `list_changed` vai para todas as sessões (`notifyClients`).
      Opções:
      1) Lista cheia, filtro só no `tools/call`. Pouco código, mas não cumpre o "Pronto quando" (as duas chaves veem a mesma lista)
         e o cliente descobre as tools proibidas.
      2) Um `McpSyncServer` por chave. Cada chave tem o seu servidor e o seu `WebMvcStreamableServerTransportProvider` (builder
         público), e um `RouterFunction` nosso em `/mcp` repassa o pedido para o provider da chave autenticada. O `ToolMirror`
         põe cada tool só nos servidores das chaves que liberam aquele upstream. Ganha de graça: `tools/list` filtrado pelo
         próprio SDK, `tools/call` de tool fora da chave dá o mesmo `-32602 Unknown tool` de tool inexistente, `list_changed`
         só para as sessões que veem a tool, e sessão de uma chave usada com outra dá 404 (o provider não conhece o id).
         Custo: desligar o servidor do Spring AI (`spring.ai.mcp.server.enabled: false`) e montar os servidores na mão, igual
         ao que a fase 3 fez com os clientes; o `ToolMirror` passa a mexer em N servidores. Chaves vêm do yml, então N é fixo.
      3) Filtro de servlet reescrevendo a resposta SSE do `tools/list`. Frágil (ler o corpo antes, bufferizar SSE, parsear JSON-RPC).
      Reflexão no mapa de handlers do SDK: descartado.
   **Decidido (2026-10-26):**
   - Filtro: opção 2, **um `McpSyncServer` por chave** (nunca por "perfil": juntar chaves com os mesmos upstreams perde o
     isolamento de sessão). Cada servidor guarda as próprias sessões, então sessão aberta com uma chave e usada com outra dá 404
     sem amarrar o `Mcp-Session-Id` na mão (o que o mcp-security tinha de melhor vem de graça).
     Os servidores são montados na mão e anunciam **só a capacidade de tools** (fecha a nota antiga de "Notas para depois").
     Cada servidor guarda o nome da sua chave: na auditoria (fase 5), "quem chamou" é o servidor por onde a chamada passou.
   - Autenticação: **Spring Security puro** com um filtro nosso em `security/` (`Authorization: Bearer`, `MessageDigest.isEqual`,
     formato do yml acima). O mcp-security fica para o OAuth (4b).
   - O 0b deixa de ser necessário (o handler não precisa saber quem chamou), mas vale para a fase 5: o `SecurityContext` está na
     thread da requisição e tem que ser lido antes do `UpstreamExecutor`.
   - O `/admin` não muda: não passa pelos servidores MCP, chama o `ToolRouter` direto, e a chave `admin: true` vê tudo.
1. ✅ Protótipo do roteador (a parte mais arriscada; antes de mexer no `ToolMirror`). Dois `McpSyncServer`, cada um com o seu
   `WebMvcStreamableServerTransportProvider`, atrás de um `RouterFunction` nosso que escolhe o servidor pela chave do header.
   Os transportes do SDK registram a rota num caminho fixo; N servidores no mesmo `/mcp` não podem registrar a rota cada um.
   Provar com 2 chaves: `initialize`, `tools/list` diferente por chave, stream SSE do GET aberto, `list_changed` chegando só no
   cliente da chave certa, e sessão de uma chave usada com outra dando 404.
   Pronto quando: os itens acima passam por curl; se o encaminhamento brigar com o SSE, é aqui que se descobre (e conversar).
   Feito (2026-10-26): `proxy/RouterPrototype` (descartável, liga com `--gate.prototype.router.enabled=true` e
   `--gate.prototype.router.keys.<nome>=<valor>`), em `/mcp-proto` ao lado do `/mcp` do Spring AI.
   Não foi preciso caminho interno: cada `WebMvcStreamableServerTransportProvider` é montado com o mesmo `mcpEndpoint`, mas a
   rota dele **nunca vira bean**; só o nosso `RouterFunction` existe, escolhe o transporte pela chave e chama
   `transport.getRouterFunction().route(request)` e `handle(request)`. O `ServerResponse.sse` volta pelo nosso handler e o
   Spring escreve normalmente (SSE do POST e stream do GET funcionam).
   Testado na 8081 com chaves `a` e `b` (uma tool `echo_<chave>` em cada; `POST /mcp-proto/_toggle/<chave>` liga/desliga uma extra):
   `tools/list` diferente por chave; `echo_a` chamada com `b` → `-32602 Unknown tool`; `list_changed` chegou só no stream GET da
   chave que mudou (e nada no da outra); sessão de `a` com chave `b` → 404 no POST e no GET; sem chave ou chave errada → 401;
   DELETE da sessão → 200; o `/mcp` do Spring AI continuou respondendo ao lado.
   Achado: o `McpAsyncServer` força `logging` nas capacidades (`.mutate().logging()` no construtor). Anunciamos `tools` e
   `logging`; resources, prompts e completions saem.
2. ⬜ Chaves na configuração. `gate.security.keys` lido na subida; falha a subida se um `value` estiver vazio ou repetido.
   O valor nunca aparece em log, em `toString()` nem em resposta do `/admin`. Comparação em tempo constante (`MessageDigest.isEqual`).
   Pronto quando: subir sem `GATE_KEY_CLI` definida falha com mensagem clara dizendo qual chave está vazia.
3. ⬜ Um servidor por chave no `/mcp`, no lugar do servidor do Spring AI (`spring.ai.mcp.server.enabled: false`), a partir do protótipo.
   O `ToolMirror` põe cada tool só nos servidores das chaves que liberam aquele upstream (`upstreams` da configuração; a regra
   "chave X vê upstream Y" fica num lugar só em `security/`). Tool fora da chave: `-32602 Unknown tool`, igual a inexistente.
   Subida, reconexão e `list_changed` dos upstreams continuam pelo mesmo `apply`, agora para cada servidor.
   Pronto quando: com uma chave `fs` (`upstreams: ["filesystem"]`), o `tools/list` tem só as 14 `filesystem__`, e chamar
   `notion__API-get-self` com ela falha; com a `cli` (`"*"`), as 38 aparecem e a chamada funciona; a fase 3 continua valendo
   (Notion cai e volta só nos servidores que o veem).
4. ⬜ Autenticação em `/mcp` e `/admin/**` (Spring Security, filtro em `security/`). Sem header ou com valor errado → 401
   (com `WWW-Authenticate: Bearer`), sem dizer qual parte falhou. O roteador do passo 3 lê a chave autenticada.
   O preflight de CORS (`OPTIONS`) passa sem chave, e o CORS do `/admin` precisa liberar o header `Authorization`.
   `/actuator/health` continua aberto. Remover o `server.address: 127.0.0.1` só depois deste passo, e só se quiser.
   `/admin` só para chave com `admin: true`: chave válida sem admin → 403.
   Pronto quando: os curls de "Testes rápidos" dão 401 sem chave e 200 com `-H "Authorization: Bearer $GATE_KEY_CLI"`; a chave `cli`
   dá 403 no `/admin/upstreams` e a `painel` dá 200; o Claude Code conecta com
   `claude mcp add --transport http gate-of-truth http://localhost:8080/mcp --header "Authorization: Bearer $GATE_KEY_CLI"`.
5. ⬜ Testes: autenticação (401/403/200, preflight sem chave), propriedades (chave vazia ou repetida falha) e filtro por chave.

Cuidados:
- Chave nunca no código, no yml commitado nem em log. `.env` ou variável no IntelliJ; no Windows, variável de ambiente do usuário.
- A resiliência da fase 3 não muda: health check e reconexão não passam por autenticação (são internos).
- Quando um upstream reconecta e as tools voltam, o filtro por chave continua valendo (ele olha a chave do upstream, não a lista fixa).
- Fora da fase 4: OAuth, cadastro de chaves pelo painel (etapa B), auditoria de quem chamou o quê (fase 5).

## Notas para depois
- O servidor anuncia capacidades de resources, prompts e completions sem ter nenhum; resolvido na fase 4 (servidores montados na mão: só tools e logging, que o SDK força).

## Próximas fases
4b) OAuth 2.0 no lugar (ou ao lado) das API keys, via spring-ai-community/mcp-security; 5) auditoria e métricas (Micrometer).

## Testes rápidos
Handshake e listagem de tools no gateway:
```bash
curl -i -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
# copie o Mcp-Session-Id dos headers
# a partir da fase 4, todo curl leva -H "Authorization: Bearer $GATE_KEY_CLI" (ou $GATE_KEY_PANEL no /admin)
curl -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <id>" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# chamar uma tool (troque o nome; na fase 2, com prefixo)
curl -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <id>" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"notion__API-get-self","arguments":{}}}'
```

API do painel:
```bash
curl -s http://localhost:8080/admin/upstreams | jq
curl -s http://localhost:8080/admin/upstreams/filesystem/tools | jq '.[].name'
curl -s -X POST http://localhost:8080/admin/tools/filesystem__list_allowed_directories/call \
  -H "Content-Type: application/json" -d '{}' | jq
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/admin/upstreams/xpto/tools   # 404
```


