#!/usr/bin/env bash
# Tries both services with Codex CLI. Registering either server is fully automatic.
#
# jme-mcp-authenticated's authorization_code + PKCE login is *also* automated here, unlike the equivalent
# Claude Code / Copilot CLI scripts: Codex's `mcp login` just prints an authorize URL and listens on a local
# callback port, rather than requiring an already-open browser or a pasted-back redirect URL on stdin -- so a
# script can drive the same HTTP steps a browser would (hit the authorize URL, submit the mock's login form
# picking demo-user + the READER role, follow the final redirect to Codex's own listener) and complete a real
# login, with Codex itself doing the actual token exchange. This only works because jeap-oauth-mock-server's
# login form is a trivial pick-a-user form for local dev/test use, not real credential checking.
#
# Calling a tool through either server additionally needs Codex's own separate OpenAI auth/quota ("codex
# login"), which this script does not set up or check beforehand -- it can't succeed without your own working
# account. The script still attempts a real call on both services (stdin closed, same reasoning as the
# backgrounded "mcp add" below); if Codex itself isn't logged in or is out of quota, that attempt fails with a
# clear message instead of hanging, and the script tells you how to fix that and retry.
#
# jme-mcp-authenticated is tried before jme-mcp-anonymous: without an OpenAI account, the anonymous section's
# "codex exec" call retries against the API for 20-30s before failing, and running it first slows down the
# login in the background.
#
# No global Codex install is needed: the first call below falls back to `npx -y @openai/codex`, which caches
# the package; every subsequent call resolves that cached binary directly instead of going through `npx`
# again, since `npx -y` re-checks the registry for a newer version on every call.
# See the README's "Verified with real clients" and "OAuth login flow" sections for what this is demonstrating.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=check-services.sh
source "$HERE/check-services.sh"

# Codex's config is user-global, not tied to the current directory the way Claude Code's "local" scope is, but
# cd to the repo root anyway for consistency with the other try-*.sh scripts.
cd "$HERE/.."

find_cached_codex() {
  # npx's own node_modules/.bin/codex is a symlink, not a regular file -- "-type f" alone misses it entirely,
  # silently falling through to the slow path below every time. Match both.
  find -L "$HOME/.npm/_npx" -maxdepth 4 -type f -path "*/.bin/codex" 2>/dev/null | head -1
}

CACHED_CODEX="$(find_cached_codex)"
if [ -z "$CACHED_CODEX" ]; then
  echo "First run: resolving Codex CLI via npx (one-time network check)..."
  npx -y @openai/codex --version >/dev/null 2>&1 || true
  CACHED_CODEX="$(find_cached_codex)"
fi

if [ -n "$CACHED_CODEX" ] && [ -x "$CACHED_CODEX" ]; then
  CODEX=("$CACHED_CODEX")
else
  echo "warning: couldn't resolve a cached Codex binary, falling back to 'npx -y' for every call (slower)" >&2
  CODEX=(npx -y @openai/codex)
fi

echo "=== jme-mcp-authenticated (login driven automatically) ==="
echo

"${CODEX[@]}" mcp remove jme-mcp-authenticated >/dev/null 2>&1 || true

LOGIN_LOG="$(mktemp)"
trap 'rm -f "$LOGIN_LOG"' EXIT

echo "Registering jme-mcp-authenticated with the login-capable client (mcp-demo-user-client)..."
# "mcp add --oauth-client-id" doesn't just register -- it immediately performs a blocking login attempt of its
# own right there (prints the authorize URL, then waits on its own local callback), so it has to be
# backgrounded here too, not just the separate "mcp login" fallback below. stdin is also explicitly closed (</dev/null): a
# backgrounded process still inheriting this script's stdin can block indefinitely if it ever reads from it.
"${CODEX[@]}" mcp add jme-mcp-authenticated --url http://localhost:8081/mcp \
  --oauth-client-id mcp-demo-user-client </dev/null >"$LOGIN_LOG" 2>&1 &
LOGIN_PID=$!

AUTHZ_URL=""
for _ in $(seq 1 30); do
  AUTHZ_URL=$(grep -oE 'http://localhost:8180/oauth-mock/oauth2/authorize\?[^[:space:]]+' "$LOGIN_LOG" | head -1)
  [ -n "$AUTHZ_URL" ] && break
  sleep 1
done

if [ -z "$AUTHZ_URL" ]; then
  # Either the registration itself failed, or it logged in immediately from an already-stored credential for
  # this OAuth client -- either way there's no URL to drive, so fall through to the status check below rather
  # than treating this as fatal.
  echo "note: no authorize URL appeared from 'mcp add' -- it may have reused an existing credential. Output:"
  cat "$LOGIN_LOG"
fi

if [ -n "$AUTHZ_URL" ]; then
  echo "Driving the same HTTP steps a browser would: authorize -> mock login form (demo-user, READER role) ->"
  echo "follow the redirect back to Codex's own local callback listener."
  COOKIES="$(mktemp)"
  trap 'rm -f "$LOGIN_LOG" "$COOKIES"' EXIT

  curl -sf -c "$COOKIES" -b "$COOKIES" -o /dev/null "$AUTHZ_URL" || echo "warning: authorize step failed" >&2
  LOGIN_REDIRECT=$(curl -sf -i -c "$COOKIES" -b "$COOKIES" -X POST "http://localhost:8180/oauth-mock/openIdMockServerLogin" \
    --data-urlencode "username=demo-user" --data-urlencode "password=secret" \
    --data-urlencode "additionalbproles=" --data-urlencode "userroles=READER" \
    | grep -i "^Location:" | sed 's/Location: //I' | tr -d '\r')

  if [ -z "$LOGIN_REDIRECT" ]; then
    echo "error: login form submission produced no redirect -- see the output above" >&2
  else
    # The redirect above lands back on the mock's own authorize endpoint first (not directly on Codex's
    # listener), which 302s again to the actual callback -- "-L" follows that automatically. The listener may
    # not accept connections yet right after the URL is printed, so retry a few times instead of giving up on
    # the first "connection refused".
    DELIVERED=""
    for _ in $(seq 1 10); do
      if curl -sf -L -o /dev/null -c "$COOKIES" -b "$COOKIES" "$LOGIN_REDIRECT"; then
        DELIVERED=1
        break
      fi
      sleep 0.5
    done
    if [ -z "$DELIVERED" ]; then
      echo "error: couldn't deliver the callback to Codex's listener after 10 attempts" >&2
    fi
  fi
fi

# Codex usually stores the credential within a few seconds of the callback, but it can take longer on a busy
# machine, so poll for up to a minute.
LOGGED_IN=""
for _ in $(seq 1 30); do
  if "${CODEX[@]}" mcp list 2>/dev/null | grep -q "^jme-mcp-authenticated .*OAuth$"; then
    LOGGED_IN=1
    break
  fi
  sleep 2
done
kill "$LOGIN_PID" 2>/dev/null || true
cat "$LOGIN_LOG"

echo
echo "Connection status (the source of truth -- the polling above is just to avoid a fixed sleep):"
"${CODEX[@]}" mcp list

if [ -n "$LOGGED_IN" ]; then
  echo
  echo "Login succeeded. Calling a tool on jme-mcp-authenticated:"
  "${CODEX[@]}" exec --skip-git-repo-check </dev/null \
    "Call get_weather with city=Bern on jme-mcp-authenticated and report the result" \
    || echo "(failed -- make sure Codex itself is logged in and has quota: 'codex login' or 'codex login --with-api-key')"
else
  echo "(didn't see 'OAuth' in time above; check the Auth column in the table just printed before retrying)"
fi

echo
echo "=== jme-mcp-anonymous (no MCP-side auth needed) ==="
echo

"${CODEX[@]}" mcp remove jme-mcp-anonymous >/dev/null 2>&1 || true
echo "Registering jme-mcp-anonymous..."
"${CODEX[@]}" mcp add jme-mcp-anonymous --url http://localhost:8082/mcp || exit 1

echo "Asking Codex to discover and call its tools..."
if "${CODEX[@]}" exec --skip-git-repo-check </dev/null \
  "List the tools on the jme-mcp-anonymous MCP server, then call is_public_holiday with date=2026-12-25 and \
call whoami. Report the tool names and the exact result (or error) of each call, nothing else."; then
  echo
  echo "Expected: is_public_holiday answers true with no token; whoami is correctly FORBIDDEN, permanently,"
  echo "since this service has no resource server configured at all to ever grant it a role."
else
  echo "(failed -- make sure Codex itself is logged in and has quota: 'codex login' or 'codex login --with-api-key')"
fi

cat <<'EOF'

Clean up afterwards with:
  npx -y @openai/codex mcp remove jme-mcp-anonymous
  npx -y @openai/codex mcp remove jme-mcp-authenticated
  npx -y @openai/codex logout
EOF
