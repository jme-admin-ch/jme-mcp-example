#!/usr/bin/env bash
# Tries both services with Claude Code: jme-mcp-anonymous needs no auth at all, so that part runs fully
# automatically and prints real results. jme-mcp-authenticated's interactive authorization_code + PKCE login
# needs a real terminal with a browser -- not a non-interactive SSH session -- so that part registers the
# server and starts the login, then stops for you to finish it. See the README's "Verified with real clients"
# and "OAuth login flow" sections for what this is demonstrating.
#
# Each non-interactive call below is scoped to exactly the one MCP server it needs via --allowedTools
# "mcp__<server>", not --dangerously-skip-permissions (which would bypass every permission check for the
# whole session, including file edits and arbitrary shell commands).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=check-services.sh
source "$HERE/check-services.sh"

# Claude Code's "local" scope is tied to the directory you're in when you run `claude mcp ...`, not to this
# script's own location -- cd to the repo root first so it always lands in the same project regardless of
# where this script was invoked from.
cd "$HERE/.."

echo "=== jme-mcp-anonymous (no auth needed) ==="
echo

claude mcp remove jme-mcp-anonymous >/dev/null 2>&1 || true
echo "Registering jme-mcp-anonymous..."
claude mcp add --transport http jme-mcp-anonymous http://localhost:8082/mcp || exit 1

echo "Asking a fresh Claude Code session to discover and call its tools..."
claude -p "List the tools on the jme-mcp-anonymous MCP server, then call is_public_holiday with date=2026-12-25 \
and call whoami. Report the tool names and the exact result (or error) of each call, nothing else." \
  --allowedTools "mcp__jme-mcp-anonymous"

cat <<'EOF'

Expected: is_public_holiday answers true with no token; whoami is correctly FORBIDDEN, permanently, since this
service has no resource server configured at all to ever grant it a role.

=== jme-mcp-authenticated (needs a real login) ===

EOF

claude mcp remove jme-mcp-authenticated >/dev/null 2>&1 || true
echo "Registering jme-mcp-authenticated with the login-capable client (mcp-demo-user-client)..."
claude mcp add --transport http jme-mcp-authenticated http://localhost:8081/mcp \
  --client-id mcp-demo-user-client || exit 1

cat <<'EOF'

Starting login now. This should open a browser to the mock server's login form:
  1. User: demo-user (should be preselected)
  2. Role checkboxes: check READER -- if you skip this, login still "succeeds" but every call afterward gets
     a correct but confusing FORBIDDEN, since the resulting token carries no roles
  3. Submit

EOF

# "claude mcp login" blocks until the browser flow actually finishes (or you Ctrl-C it), so by the time it
# returns successfully the login is genuinely done -- the tool call right after it needs no waiting/polling
# of its own, unlike codex's backgrounded equivalent in try-codex.sh.
if claude mcp login jme-mcp-authenticated; then
  echo
  echo "Login succeeded. Calling a tool on jme-mcp-authenticated:"
  claude -p "Call get_weather with city=Bern on the jme-mcp-authenticated MCP server and tell me the result" \
    --allowedTools "mcp__jme-mcp-authenticated"
else
  echo "(login did not complete -- see the error above and retry)"
fi

echo
echo "Connection status:"
claude mcp list | grep -E "jme-mcp-anonymous|jme-mcp-authenticated"

cat <<'EOF'

Clean up afterwards with:
  claude mcp remove jme-mcp-anonymous
  claude mcp remove jme-mcp-authenticated
EOF
