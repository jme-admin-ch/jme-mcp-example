# Sourced by the try-*.sh scripts in this directory, not meant to be run directly. Fails fast with a clear
# message if the services aren't reachable yet, rather than letting a client CLI fail later with a confusing
# connection error. jme-mcp-anonymous-service needs no oauth-mock, so it's checked and reported independently.

is_reachable() {
  curl -s -o /dev/null --max-time 2 "$1"
}

if ! is_reachable "http://localhost:8180/oauth-mock/oauth2/token" \
  || ! is_reachable "http://localhost:8081/mcp"; then
  echo "error: jme-mcp-oauth-mock and/or jme-mcp-authenticated-service aren't reachable." >&2
  echo "Start them first, from the repository root:" >&2
  echo "  ./mvnw --projects jme-mcp-oauth-mock spring-boot:run" >&2
  echo "  ./mvnw --projects jme-mcp-authenticated-service spring-boot:run" >&2
  exit 1
fi

if ! is_reachable "http://localhost:8082/mcp"; then
  echo "error: jme-mcp-anonymous-service isn't reachable. Start it first, from the repository root:" >&2
  echo "  ./mvnw --projects jme-mcp-anonymous-service spring-boot:run" >&2
  exit 1
fi
