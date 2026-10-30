#!/usr/bin/env bash
# Tries both services with Copilot CLI. jme-mcp-anonymous needs no auth at all, so that part runs fully
# automatically and prints real results.
#
# jme-mcp-authenticated is different: Copilot CLI (1.0.105) has no interactive OAuth login flow for MCP
# servers at all -- `copilot mcp add` has no
# client-id/OAuth flag, and there is no `copilot mcp login` subcommand (`error: unrecognized subcommand
# 'login'`). Registering the server with no auth and using it non-interactively doesn't trigger a login
# either -- Copilot just silently excludes the server from the available tools. So unlike try-claude-code.sh
# and try-codex.sh, this script cannot demonstrate an interactive login for it; it falls back to the
# client_credentials flow that does work with Copilot CLI today (same as the README's "Try it" section), and
# says so. If a future Copilot CLI version adds its own login flow, replace that part of this script's body
# accordingly.
#
# Each non-interactive call below is scoped to exactly the one MCP server it needs via --allow-tool=
# '<server>', not --allow-all-tools (which would auto-approve every tool, including arbitrary shell commands).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=check-services.sh
source "$HERE/check-services.sh"

echo "=== jme-mcp-anonymous (no auth needed) ==="
echo

copilot mcp remove jme-mcp-anonymous >/dev/null 2>&1 || true
echo "Registering jme-mcp-anonymous..."
copilot mcp add --transport http jme-mcp-anonymous http://localhost:8082/mcp

echo "Asking Copilot to discover and call its tools..."
copilot -p "List the tools on the jme-mcp-anonymous MCP server, then call is_public_holiday with date=2026-12-25 \
and call whoami. Report the tool names and the exact result (or error) of each call, nothing else." \
  --allow-tool='jme-mcp-anonymous' -s

cat <<'EOF'

Expected: is_public_holiday answers true with no token; whoami is correctly FORBIDDEN, permanently, since this
service has no resource server configured at all to ever grant it a role.

=== jme-mcp-authenticated (no login flow available in this CLI -- see the comment at the top) ===

EOF

copilot mcp remove jme-mcp-authenticated >/dev/null 2>&1 || true

echo "Fetching a client_credentials token from jme-mcp-oauth-mock..."
TOKEN=$(curl -s -u "mcp-demo-client:demo-secret" -d "grant_type=client_credentials" \
  http://localhost:8180/oauth-mock/oauth2/token | jq -r .access_token)

echo "Registering jme-mcp-authenticated with that token as a static bearer header..."
copilot mcp add --transport http jme-mcp-authenticated http://localhost:8081/mcp \
  --header "Authorization: Bearer $TOKEN"

echo "Asking Copilot to call a tool on it..."
copilot -p "Call get_weather with city=Bern on the jme-mcp-authenticated MCP server and report the result." \
  --allow-tool='jme-mcp-authenticated' -s

cat <<'EOF'

Clean up afterwards with:
  copilot mcp remove jme-mcp-anonymous
  copilot mcp remove jme-mcp-authenticated
EOF
