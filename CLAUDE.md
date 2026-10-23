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
<pacote base>
├── upstream/   conexões com os servidores MCP de fora (ex.: UpstreamRegistry guarda os McpSyncClient e lista tools)
├── proxy/      o que o gateway expõe no /mcp (ex.: ToolMirror registra as tools no McpSyncServer e repassa o callTool)
└── config/     @ConfigurationProperties e beans
```
- Fases futuras viram pacotes novos: `routing/` (prefixos), `security/` (API key/OAuth), `audit/` (logs e métricas).
- `proxy` depende de `upstream`, nunca o contrário.
- Sem interfaces antes de existir uma segunda implementação; com um upstream só, classes concretas.
- O componente do passo 3 (listar e logar as tools) fica em `upstream/`.

## Estado atual (MVP: escrever no Notion pelo gateway)
1. ✅ Integração interna do Notion criada e conectada à página "Teste MCP".
2. ✅ Servidor do Notion testado sozinho no MCP Inspector: `API-post-page` criou uma página filha.
   Argumentos que funcionaram: `parent: {"page_id": "..."}`, `properties: {"title": [{"text": {"content": "..."}}]}`.
3. ✅ Gateway como cliente: conexão STDIO `notion` no application.yml; `upstream/UpstreamToolsLogger` loga no `ApplicationReadyEvent`
   as 24 tools do Notion (`API-post-page`, `API-post-search` etc.).
4. ✅ Tools espelhadas: `proxy/ToolMirror` registra no `McpSyncServer`, no `ApplicationReadyEvent`, o mesmo `McpSchema.Tool` do upstream;
   o handler repassa o `CallToolRequest` para `client.callTool` e devolve o resultado sem alterar. `tools/list` no `/mcp` mostra as 24 tools.
5. ✅ Ponta a ponta: `claude mcp add --transport http gate-of-truth http://localhost:8080/mcp`; pelo gateway, o Claude Code
   achou "Teste MCP" (`API-post-search`), criou a página filha "Escrito pelo Gate of Truth" (`API-post-page`) e escreveu
   um parágrafo nela (`API-patch-block-children`).

## Depois do MVP
2) vários upstreams com prefixo por upstream e tabela de roteamento; 3) resiliência (timeouts, reconexão, list_changed);
4) controle de acesso (API key, depois OAuth); 5) auditoria e métricas (Micrometer).

## Testes rápidos
Handshake e listagem de tools no gateway:
```bash
curl -i -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
# copie o Mcp-Session-Id dos headers
curl -X POST http://localhost:8080/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <id>" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```
