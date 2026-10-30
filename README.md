# jme-mcp-example

jEAP example for [`jeap-spring-boot-mcp-starter`](https://github.com/jeap-admin-ch/jeap-spring-boot-mcp-starter):
one service exposing authenticated MCP tools, and one with `jeap.mcp.security.anonymous-access` enabled for
public tools. Four Maven modules:

| Module | Port | What it shows |
|---|---|---|
| `jme-mcp-oauth-mock` | 8180 | A local [`jeap-oauth-mock-server`](https://github.com/jeap-admin-ch/jeap-oauth-mock-server) instance issuing bearer tokens for `jme-mcp-authenticated-service` below, for manual trying-out: `mcp-demo-client` for `client_credentials` (scripted/CI use), `mcp-demo-user-client` for an interactive `authorization_code` + PKCE login (what a real MCP client does). Local development only, never a real authorization server. |
| `jme-mcp-authenticated-service` | 8081 | Default-deny MCP security: every call needs a valid bearer token and the `READER` role. Tools: `get_weather`, `get_current_time`, `convert_temperature` (all protected). |
| `jme-mcp-anonymous-service` | 8082 | `jeap.mcp.security.anonymous-access: true` with **no jEAP OAuth2 resource server configured at all**: every caller is permanently anonymous. A public tool answers; a tool that still requires a role is permanently unreachable, not a bug. No `jme-mcp-oauth-mock` dependency. Tools: `is_public_holiday`, `get_current_time` (public), `whoami`, `convert_temperature` (always `FORBIDDEN`). |
| `jme-mcp-test` | - | Automated integration tests for the two services above, see [Tests](#tests). |

`get_current_time` and `convert_temperature` are intentionally identical in both services (same code, same
behavior), just with a different `@PreAuthorize` rule each, to show that the authorization decision is per tool,
not per service -- and, for `jme-mcp-anonymous-service`, not even dependent on a resource server existing at all.

## Build

```bash
./mvnw package -Dmaven.gitcommitid.skip=true -DskipTests
```

## Run

Start all three in separate terminals (or background them). All three skip `spring-boot:repackage` (so
`jme-mcp-test` can depend on the two services' application class and tools, and `jme-mcp-oauth-mock` has
nothing of its own to bundle in the first place), so run them with `spring-boot:run` rather than `java -jar`:

```bash
./mvnw --projects jme-mcp-oauth-mock spring-boot:run
./mvnw --projects jme-mcp-authenticated-service spring-boot:run
./mvnw --projects jme-mcp-anonymous-service spring-boot:run
```

### `local` and `ci` profiles

`jme-mcp-oauth-mock` and `jme-mcp-authenticated-service` ship `application-local.yml` and `application-ci.yml`,
same idea as [`jme-jwe-example`](https://github.com/jeap-admin-ch/jme-jwe-example): `local` has them reach each
other at `localhost` and is activated by default (`spring.profiles.default: local`), so the plain commands above
need no extra flag. `ci` switches the oauth-mock and service URLs to container hostnames (the module's artifact
id) for a future containerized CI run, activated with `--spring.profiles.active=ci` or `SPRING_PROFILES_ACTIVE=ci`.
This repository does not yet ship the docker-compose setup that would make `ci` actually reachable; it only
establishes the convention. `jme-mcp-anonymous-service` has neither profile: with no resource server to point
at, there is nothing left to differ between `local` and `ci` for it.

## Tests

`jme-mcp-test` covers everything demonstrated manually below with real JUnit assertions, without needing
`jme-mcp-oauth-mock` or any other process running: it boots each service's own `@SpringBootApplication` class
and tools in-process (the same classes `spring-boot:run` would use above), with a real signed-JWT JWKS endpoint
from [`jeap-spring-boot-mcp-starter-test`](https://github.com/jeap-admin-ch/jeap-spring-boot-mcp-starter/tree/main/jeap-spring-boot-mcp-starter-test)
standing in for the mock server -- the same test support the starter's own integration tests use.

```bash
./mvnw --projects jme-mcp-test verify -Dmaven.gitcommitid.skip=true
```

- `AuthenticatedServiceIT`: no token → 401 with `WWW-Authenticate`; a `READER` token can call every tool; a
  token without the role → `FORBIDDEN`; an expired token → 401.
- `AnonymousServiceIT`: both public tools answer anonymous callers; both tools that still require the `READER`
  role return `FORBIDDEN` -- permanently, since there is no resource server to ever satisfy that role with.

Tests use the `*IT` naming convention (Failsafe, bound to the `verify` phase), not `*Test` (Surefire's `test`
phase) -- `./mvnw test` alone will not run them.

## Try it

Get a bearer token from the mock server (`mcp-demo-client`, role `READER`, valid for `jme-mcp-authenticated-service`):

```bash
TOKEN=$(curl -s -u "mcp-demo-client:demo-secret" -d "grant_type=client_credentials" \
  http://localhost:8180/oauth-mock/oauth2/token | jq -r .access_token)
```

### Authenticated service (default deny)

```bash
# No token: rejected before it ever reaches a tool
curl -i -X POST http://localhost:8081/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_weather","arguments":{"city":"Bern"}}}'
# -> 401, with a WWW-Authenticate challenge

# With token: works
curl -X POST http://localhost:8081/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_weather","arguments":{"city":"Bern"}}}'
# -> {"result":{"content":[{"type":"text","text":"Partly cloudy, 14°C"}],"isError":false}, ...}
```

### Anonymous-access service (no resource server at all)

No token is needed, or even possible: `jme-mcp-anonymous-service` has no `jeap.security.oauth2.resourceserver.*`
configured, so there is nothing to validate a bearer token with, present or not.

```bash
# No token, public tool: still answers
curl -X POST http://localhost:8082/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"is_public_holiday","arguments":{"date":"2026-12-25"}}}'
# -> {"result":{"content":[{"type":"text","text":"true"}],"isError":false}, ...}

# No token, a tool that still requires the READER role: the HTTP call succeeds, the tool call itself is denied,
# permanently -- there is no way to ever have that role here
curl -X POST http://localhost:8082/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"whoami","arguments":{}}}'
# -> {"result":{"content":[{"type":"text","text":"FORBIDDEN: Access denied"}],"isError":true, ...}}
```

A real MCP client (Claude Desktop, MCP Inspector, ...) can connect to `http://localhost:8081/mcp` the same way,
using the token above as a static bearer header; `http://localhost:8082/mcp` needs no header at all. See
[Verified with real clients](#verified-with-real-clients) below for one actually doing so.

## Verified with real clients

Each client below was pointed at both services running locally as above. Claude Code and GitHub Copilot CLI are
verified end-to-end: connection, authentication, tool discovery and tool calls, including a denied call. Codex
CLI is verified up to server registration and login; tool calls through Codex are not verified.

Common setup for all clients:

```bash
TOKEN=$(curl -s -u "mcp-demo-client:demo-secret" -d "grant_type=client_credentials" \
  http://localhost:8180/oauth-mock/oauth2/token | jq -r .access_token)
```

### Claude Code (`2.1.314`)

```bash
claude mcp add --transport http jme-mcp-authenticated http://localhost:8081/mcp \
  --header "Authorization: Bearer $TOKEN"
claude mcp add --transport http jme-mcp-anonymous http://localhost:8082/mcp

claude mcp list
# jme-mcp-authenticated: http://localhost:8081/mcp (HTTP) - ✔ Connected
# jme-mcp-anonymous: http://localhost:8082/mcp (HTTP) - ✔ Connected
```

A fresh `claude -p` session (so it loads the servers just registered) listing tools and calling three of them:

```text
## Tools found
jme-mcp-authenticated: get_weather, get_current_time, convert_temperature
jme-mcp-anonymous: is_public_holiday, whoami, get_current_time, convert_temperature

## Call results
get_weather(city=Bern) -> Partly cloudy, 14°C
is_public_holiday(date=2026-12-25) -> true
whoami() -> error: FORBIDDEN: Access denied
```

Clean up afterwards (the token in it expires anyway):

```bash
claude mcp remove jme-mcp-authenticated
claude mcp remove jme-mcp-anonymous
```

### GitHub Copilot CLI (`1.0.105`)

Same servers, same token, Copilot's own `mcp add` syntax (otherwise identical to Claude Code's):

```bash
copilot mcp add --transport http jme-mcp-authenticated http://localhost:8081/mcp \
  --header "Authorization: Bearer $TOKEN"
copilot mcp add --transport http jme-mcp-anonymous http://localhost:8082/mcp

copilot mcp get jme-mcp-authenticated   # Status: Enabled
copilot mcp get jme-mcp-anonymous       # Status: Enabled
```

A non-interactive `copilot -p --allow-tool='jme-mcp-authenticated' --allow-tool='jme-mcp-anonymous'` run listing
tools and calling the same three:

```text
**jme-mcp-authenticated**
- jme-mcp-authenticated-convert_temperature
- jme-mcp-authenticated-get_current_time
- jme-mcp-authenticated-get_weather

**jme-mcp-anonymous**
- jme-mcp-anonymous-convert_temperature
- jme-mcp-anonymous-get_current_time
- jme-mcp-anonymous-is_public_holiday
- jme-mcp-anonymous-whoami

get_weather(city="Bern") -> Partly cloudy, 14°C
is_public_holiday(date="2026-12-25") -> true
whoami -> {"message":"Access denied","code":"FORBIDDEN"}
```

Copilot prefixes each tool name with its server name (`jme-mcp-anonymous-whoami`, not `whoami`) -- a
Copilot-side display convention, not something either service does. Clean up afterwards:

```bash
copilot mcp remove jme-mcp-authenticated
copilot mcp remove jme-mcp-anonymous
```

### Codex CLI (`0.166.0`) -- registration and login only

Installed via `npx -y @openai/codex` (no global install needed). Before Codex can call any tool it needs its own
OpenAI account, either via `codex login` or an API key:

```bash
echo "$OPENAI_API_KEY" | npx -y @openai/codex login --with-api-key
npx -y @openai/codex login status   # API key configured - sk-proj-***...
```

Register the servers with `--url` and, for the authenticated one, `--bearer-token-env-var` (Codex reads the token
from an env var at call time instead of taking it as a literal flag):

```bash
export JME_MCP_TOKEN="$TOKEN"
npx -y @openai/codex mcp add jme-mcp-authenticated --url http://localhost:8081/mcp \
  --bearer-token-env-var JME_MCP_TOKEN
npx -y @openai/codex mcp add jme-mcp-anonymous --url http://localhost:8082/mcp

npx -y @openai/codex mcp list
# jme-mcp-authenticated  http://localhost:8081/mcp  JME_MCP_TOKEN  enabled  Bearer token
# jme-mcp-anonymous      http://localhost:8082/mcp  -              enabled  Unsupported
```

To list and call tools (needs an OpenAI account with available quota):

```bash
npx -y @openai/codex exec --skip-git-repo-check \
  "List the tools on jme-mcp-authenticated and jme-mcp-anonymous, then call get_weather(city=Bern), \
   is_public_holiday(date=2026-12-25) and whoami, and report the exact results."
```

Clean up afterwards:

```bash
npx -y @openai/codex mcp remove jme-mcp-authenticated
npx -y @openai/codex mcp remove jme-mcp-anonymous
npx -y @openai/codex logout
```

### OAuth login flow (`authorization_code` + PKCE)

The sections above hand the client a `client_credentials` token as a static header. A client can instead
discover the authorization server via `/.well-known/oauth-protected-resource` and log in itself with
`authorization_code` + PKCE.

`jme-mcp-oauth-mock` configures `mcp-demo-user-client` (`context: USER`, no secret) and a `demo-user` to log in
as. Its `registered-redirect-uri` lists both `"http://localhost:*"` and `"http://127.0.0.1:*"`, since clients
differ in which host they use for their local OAuth callback and the mock matches the wildcard as a plain string
prefix.

**Claude Code** (needs a terminal and a browser):

```bash
claude mcp add --transport http jme-mcp-authenticated http://localhost:8081/mcp --client-id mcp-demo-user-client
claude mcp login jme-mcp-authenticated
# opens the mock server's login form in a browser: pick demo-user and the READER role, then it redirects
# back and stores the token
```

**Codex CLI** prints the authorize URL and listens on a local callback port (on `127.0.0.1`), so the login can
also be completed without a browser, as `scripts/try-codex.sh` does:

```bash
codex mcp add jme-mcp-authenticated --url http://localhost:8081/mcp --oauth-client-id mcp-demo-user-client
codex mcp login jme-mcp-authenticated
```

**Copilot CLI** (`1.0.105`) has no OAuth login for MCP servers: `copilot mcp add` has no client-id option and
there is no `copilot mcp login` command. Use the `client_credentials` token from above instead.

When submitting the mock's login form with `curl` instead of a browser, send `userroles=READER` (otherwise the
token has no roles and protected tools return `FORBIDDEN`) and an empty `additionalbproles=` field (without it,
the mock answers `500`).

### Scripts

`scripts/try-claude-code.sh`, `scripts/try-codex.sh` and `scripts/try-copilot.sh` run the steps above with one
command per client. They share `scripts/check-services.sh`, which stops with a clear message unless
`jme-mcp-oauth-mock`, `jme-mcp-authenticated-service` and `jme-mcp-anonymous-service` are all running. Each script:

- registers `jme-mcp-anonymous` and calls its tools;
- registers `jme-mcp-authenticated`, logs in and calls a tool through it. Codex completes the login itself,
  Claude Code waits until you finish it in the browser, and Copilot uses a `client_credentials` token instead.

What to expect:

- `try-claude-code.sh` and `try-copilot.sh` call tools on both services. Without a terminal and browser,
  `try-claude-code.sh` stops at the login and skips the authenticated tool call.
- `try-codex.sh` ends with `jme-mcp-authenticated ... OAuth` in `codex mcp list`. Its tool calls need an
  OpenAI account with quota; without one they fail with a message saying so.

## Running on CI

[`.github/workflows/build.yml`](.github/workflows/build.yml) builds and installs the Maven reactor on every push,
using the shared `jme-admin-ch/.github` reusable workflow. `mvn install` runs the `verify` phase first, so
`jme-mcp-test`'s integration tests already run on every push. The `ci` Spring profile is not exercised by them
though (they use `jeap-spring-boot-mcp-starter-test`'s in-process JWKS mock, not `jme-mcp-oauth-mock`, so they
never need it); see [`local` and `ci` profiles](#local-and-ci-profiles) above for what that profile prepares for.

## Note

This repository is part of the open source distribution of jEAP. See
[github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap) for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
