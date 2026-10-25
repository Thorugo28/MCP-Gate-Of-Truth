# MCP Gate of Truth

Gateway MCP agregador em Java/Spring, inspirado no Bifrost (Maxim AI).
- Para fora: servidor MCP em `/mcp` (Streamable HTTP). Clientes como o Claude Code se conectam numa URL única.
- Para dentro: cliente MCP que conecta em N servidores upstream, agrega as tools e roteia as chamadas.
- O gateway não chama LLM. Só repassa mensagens MCP.

## Stack e decisões fixas (não mudar sem conversar)
- Spring Boot 4.1.x + Spring AI 2.0 (MCP Java SDK 2.0, spec MCP 2025-11-25). Maven, versões pelo BOM do Spring AI.
- Transporte: WebMVC + Streamable HTTP (`spring-ai-starter-mcp-server-webmvc`, não o starter STDIO).
- Cliente: `spring-ai-starter-mcp-client`, modo SYNC (`McpSyncClient` / `McpSyncServer`).
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
└── config/     @ConfigurationProperties e beans
```
- Fases futuras viram pacotes novos: `security/` (API key/OAuth), `audit/` (logs e métricas).
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
- `GET /admin/upstreams` → `[{ "key", "serverName", "serverVersion", "status", "toolCount" }]`
  - `key`: chave do yml (do `UpstreamRegistry`). `serverName`/`serverVersion`: `client.getServerInfo()`.
  - `status`: `"UP"` ou `"DOWN"`, por enquanto com base em `client.ping()`. Atenção: o ping usa o `request-timeout` (60s);
    se um upstream travar, a tela trava junto. Aceitável agora; timeout curto e status de verdade são fase 3.
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

## Estado atual: fase 3, resiliência
Objetivo: um upstream com problema não derruba nem trava o gateway; quando ele volta, as tools voltam sozinhas.
Pronto quando: com o gateway no ar, `pkill -f notion-mcp-server` → o filesystem continua respondendo pelo `/mcp`, o painel mostra
`notion` como `DOWN` e o `tools/list` cai para 14; depois, sem reiniciar o gateway, o Notion volta e as 24 tools reaparecem.

Passos:
0. ⬜ Investigar antes de codar (só leitura do código do Spring AI 2.0 e do MCP SDK 2.0; anotar aqui o que achar):
   a) Existe `spring.ai.mcp.client.initialized: false`? Com ele, os clientes são criados sem subir o processo e sem o handshake,
      e o gateway chama `initialize()` de cada um? Se sim, a subida tolerante (passo 1) não precisa criar clientes na mão.
   b) Um `McpSyncClient` cujo processo STDIO morreu pode ser inicializado de novo, ou o transporte fecha de vez?
      Se fecha de vez, a reconexão (passo 4) exige criar um cliente novo a partir das propriedades da conexão do yml
      (`McpStdioClientProperties`): é o "plano B" da fase 2. Conversar antes de seguir por ele.
   c) Como avisar os clientes do gateway: confirmar que `McpSyncServer.removeTool`/`addTool` disparam `notifications/tools/list_changed`.
   d) Como receber o `list_changed` de um upstream no Spring AI (provavelmente um `McpSyncClientCustomizer` com `toolsChangeConsumer`).
1. ⬜ Subida tolerante. Cada upstream é inicializado separadamente; se falhar, fica `DOWN`, sem tools, e o gateway sobe com os outros.
   Pronto quando: com a conexão `notion` apontando para um pacote inexistente, o gateway sobe, o filesystem funciona pelo `/mcp`
   e `/admin/upstreams` mostra `notion` `DOWN` com 0 tools.
2. ⬜ Timeouts curtos, por propriedade (ex.: `gate.upstreams.ping-timeout: 3s`, `gate.upstreams.call-timeout: 30s`).
   Se o SDK não aceitar timeout por chamada, rodar a chamada num executor e usar `Future.get(timeout)`.
   Pronto quando: com o processo do Notion congelado (`pkill -STOP -f notion-mcp-server`), `/admin/upstreams` responde em ~3s
   e uma chamada `notion__...` devolve erro de tool em vez de travar. (`pkill -CONT -f notion-mcp-server` para descongelar.)
3. ⬜ Health check. Um `@Scheduled` (ex.: `gate.upstreams.health-interval: 15s`) pinga cada upstream e guarda o status num lugar só
   em `upstream/`. O `/admin` passa a ler esse status guardado, sem pingar na hora da requisição.
   Na transição UP → DOWN: tirar as tools daquele upstream do `McpSyncServer` e do `ToolRouter` (o `ToolRouter` vai precisar de um
   `unregister` por chave) e fechar o cliente, para não sobrar processo zumbi.
   Pronto quando: `pkill -f notion-mcp-server` → em até 15s o painel mostra `DOWN` e o `tools/list` do `/mcp` tem 14 tools.
4. ⬜ Reconexão. Upstream `DOWN` é tentado de novo com backoff (ex.: 15s, 30s, 60s, teto de 5 min). Ao conectar, as tools são
   registradas de novo (no `McpSyncServer` e no `ToolRouter`) e o status volta a `UP`. Depende do que o passo 0b mostrar.
   Pronto quando: depois do `pkill`, o gateway sobe o `npx` de novo sozinho e as 24 tools do Notion reaparecem sem reiniciar.
5. ⬜ Repasse do `list_changed` dos upstreams. Quando um upstream avisar que a lista mudou, sincronizar só as tools dele:
   remover as que sumiram, adicionar as novas, manter as iguais. O filesystem anuncia `listChanged=true`.
   Pronto quando: teste unitário da sincronização (lista antiga × lista nova) passando; teste manual se der para provocar o aviso.

Cuidados:
- O estado de cada upstream (cliente, status, tentativas) muda numa thread de agendamento enquanto chamadas chegam em outras:
  usar estruturas thread-safe e não registrar a mesma tool duas vezes (o `register` do `ToolRouter` falha com nome repetido).
- Chamada para upstream `DOWN` que ainda chegue ao router: `CallToolResult` com `isError: true` e uma mensagem clara
  ("upstream notion indisponível"), nunca exceção.
- O status passa a ter três valores possíveis se fizer sentido (ex.: `UP`, `DOWN`, `RECONNECTING`); se mudar, o contrato do
  `/admin` muda junto e o front precisa saber.
- Fora da fase 3: autenticação, filtro de tools e métricas.

## Notas para depois
- O servidor anuncia capacidades de resources, prompts e completions sem ter nenhum; dá para desligar e anunciar só tools.

## Próximas fases
4) controle de acesso (API key, depois OAuth); 5) auditoria e métricas (Micrometer).

## Testes rápidos
Handshake e listagem de tools no gateway:
```bash
curl -i -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
# copie o Mcp-Session-Id dos headers
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

