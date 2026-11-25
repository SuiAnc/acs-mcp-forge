#!/usr/bin/env bash
#
# End-to-end self test for the Android Code Studio MCP server.
#
# Usage:
#   ./docs/mcp/selftest.sh [base-url] [token]
#
#   base-url  defaults to http://127.0.0.1:6688
#   token     optional bearer token (only needed when an access token is configured)
#
# Enable the server first: Settings -> Developer options -> MCP server -> Enable MCP server.
#
set -u

BASE="${1:-http://127.0.0.1:6688}"
TOKEN="${2:-}"
PASSED=0
FAILED=0

auth_header=()
if [ -n "$TOKEN" ]; then
  auth_header=(-H "Authorization: Bearer $TOKEN")
fi

pass() { PASSED=$((PASSED + 1)); echo "PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); echo "FAIL  $1"; echo "      $2"; }

post() {
  curl -sS -m 60 -X POST "$BASE/mcp" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    "${auth_header[@]}" \
    -d "$1"
}

echo "MCP self test against $BASE"
echo

# 1. Health --------------------------------------------------------------------------------------
health="$(curl -sS -m 10 "$BASE/health" 2>&1)"
case "$health" in
  *'"status":"ok"'*) pass "GET /health" ;;
  *) fail "GET /health" "$health" ;;
esac

# 2. initialize ----------------------------------------------------------------------------------
init="$(post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"selftest","version":"1"}}}')"
case "$init" in
  *'"serverInfo"'*) pass "initialize" ;;
  *unauthorized*) fail "initialize" "server requires a token; pass it as the second argument" ;;
  *) fail "initialize" "$init" ;;
esac

# 3. notifications/initialized -------------------------------------------------------------------
code="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" \
  -H "Content-Type: application/json" "${auth_header[@]}" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')"
[ "$code" = "202" ] && pass "notifications/initialized returns 202" \
                     || fail "notifications/initialized returns 202" "HTTP $code"

# 4. tools/list ----------------------------------------------------------------------------------
tools="$(post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
for tool in file_list file_read file_search workspace_list_projects project_templates build_start; do
  case "$tools" in
    *"\"$tool\""*) pass "tools/list contains $tool" ;;
    *) echo "SKIP  tools/list contains $tool (disabled by the current switches)" ;;
  esac
done

# 5. workspace + file_read -----------------------------------------------------------------------
work="$(post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workspace_list_projects","arguments":{}}}')"
case "$work" in
  *'"projectsDir"'*) pass "workspace_list_projects" ;;
  *) fail "workspace_list_projects" "$work" ;;
esac

current="$(post '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"workspace_current_project","arguments":{}}}')"
case "$current" in
  *'"path"'*) pass "workspace_current_project" ;;
  *) echo "SKIP  workspace_current_project (no project is open)" ;;
esac

listing="$(post '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"file_list","arguments":{}}}')"
case "$listing" in
  *'"entries"'*) pass "file_list" ;;
  *) fail "file_list" "$listing" ;;
esac

# 6. Sandbox enforcement ------------------------------------------------------------------------
escape="$(post '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"file_read","arguments":{"path":"/etc/passwd"}}}')"
case "$escape" in
  *'"isError":true'*) pass "sandbox rejects /etc/passwd" ;;
  *) fail "sandbox rejects /etc/passwd" "$escape" ;;
esac

# 7. Unknown method -----------------------------------------------------------------------------
unknown="$(post '{"jsonrpc":"2.0","id":7,"method":"does/not/exist"}')"
case "$unknown" in
  *'-32601'*) pass "unknown method returns -32601" ;;
  *) fail "unknown method returns -32601" "$unknown" ;;
esac

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
