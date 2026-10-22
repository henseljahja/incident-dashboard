#!/bin/sh
# Renders /config.js (served by nginx, see nginx.conf.template) from environment variables, so a
# single image can be pointed at any environment. Runs on every container start, as the
# unprivileged nginx user, and writes only under /tmp.
#
#   API_BASE_URL       backend base the browser calls            (default: /api, same origin)
#   REDMINE_BASE_URL   Redmine origin for ticket deep links      (default: empty = links disabled)
#   AI_INSIGHTS_LIVE   "true" enables the live AI Insights page  (default: empty = placeholder)
set -eu

OUT_DIR=/tmp/runtime-config
mkdir -p "$OUT_DIR"

# Quote a value as a JSON/JS string: escape backslash, double quote, and drop control characters
# (a newline inside a value would otherwise end the string early).
js() {
  printf '%s' "$1" | tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/</\\u003c/g'
}

cat > "$OUT_DIR/config.js" <<JS
window.__APP_CONFIG__ = {
  apiBaseUrl: "$(js "${API_BASE_URL:-}")",
  redmineBaseUrl: "$(js "${REDMINE_BASE_URL:-}")",
  aiInsightsLive: "$(js "${AI_INSIGHTS_LIVE:-}")"
}
JS
echo "runtime-config: wrote $OUT_DIR/config.js"
