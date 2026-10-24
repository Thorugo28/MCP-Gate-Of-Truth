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
└── config/     @ConfigurationProperties e beans
```
- Fases futuras viram pacotes novos: `routing/` (fase 2, tabela de roteamento), `security/` (API key/OAuth), `audit/` (logs e métricas).
- `proxy` depende de `upstream`, nunca o contrário.
- Sem interfaces antes de existir uma segunda implementação; com um upstream só, classes concretas.

## Concluído: MVP (2026-10-23)
Claude Code → gateway → servidor do Notion criou uma página ponta a ponta.
- `upstream/UpstreamToolsLogger` loga as 24 tools do upstream "Notion API" na subida.
- `proxy/ToolMirror` registra cada tool do upstream no `McpSyncServer` (mesmo nome, descrição e inputSchema) e repassa o `callTool`.
- Argumentos que funcionaram em `API-post-page`: `parent: {"page_id": "..."}`, `properties: {"title": [{"text": {"content": "..."}}]}`.
- WARNs de BeanPostProcessorChecker e "No tool/sampling/elicitation methods found" são esperados.

## Estado atual: fase 2, vários upstreams com prefixo e roteamento
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

## Notas para depois
- O servidor anuncia capacidades de resources, prompts e completions sem ter nenhum; dá para desligar e anunciar só tools.

## Próximas fases
3) resiliência (timeouts, reconexão, list_changed); 4) controle de acesso (API key, depois OAuth); 5) auditoria e métricas (Micrometer).

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
