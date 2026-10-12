#!/usr/bin/env bash
# Adds a dashboard user straight to APP_USER on a deployed host — the break-glass path for when
# nobody can log in as an administrator, so the Users page (which needs one) is out of reach.
#
#   ./scripts/add-dashboard-user.sh [ssh-host]        # default host: mti-dev-inc01
#
# The password is typed at a silent prompt on YOUR machine and hashed here with bcrypt
# (htpasswd -B, cost 10). Only the hash crosses SSH; the plaintext never appears in argv, shell
# history, or on the host. Spring's BCryptPasswordEncoder accepts htpasswd's $2y$ prefix.
#
# INSERT only: an existing username is refused, never overwritten.
set -euo pipefail

HOST="${1:-mti-dev-inc01}"
ENV_FILE='~/incident-dashboard-deploy/bundle/incident-dashboard/deploy/.env'

command -v htpasswd >/dev/null || { echo "htpasswd not found (macOS ships it at /usr/sbin/htpasswd)" >&2; exit 1; }

read -r -p "Username (letters, digits, . _ -): " USERNAME
[[ "$USERNAME" =~ ^[A-Za-z0-9._-]{3,64}$ ]] || { echo "Invalid username." >&2; exit 1; }

read -r -p "Display name [${USERNAME}]: " DISPLAY_NAME
DISPLAY_NAME="${DISPLAY_NAME:-$USERNAME}"
DISPLAY_NAME="${DISPLAY_NAME//\'/\'\'}"

echo "Roles: ADMIN, OPS_MANAGER, EXEC, WALLBOARD, VIEWER"
read -r -p "Role [ADMIN]: " ROLE
ROLE="${ROLE:-ADMIN}"
[[ "$ROLE" =~ ^(ADMIN|OPS_MANAGER|EXEC|WALLBOARD|VIEWER)$ ]] || { echo "Unknown role." >&2; exit 1; }

read -r -s -p "Password (min 12 characters): " PASSWORD; echo
[[ ${#PASSWORD} -ge 12 ]] || { echo "Password must be at least 12 characters." >&2; exit 1; }
read -r -s -p "Repeat password: " PASSWORD2; echo
[[ "$PASSWORD" == "$PASSWORD2" ]] || { echo "Passwords do not match." >&2; exit 1; }
unset PASSWORD2

HASH="$(printf '%s' "$PASSWORD" | htpasswd -niB -C 10 "$USERNAME" | cut -d: -f2-)"
unset PASSWORD
[[ "$HASH" == \$2y\$10\$* ]] || { echo "Hashing failed." >&2; exit 1; }

SQL="$(cat <<SQL
set define off
set feedback off
whenever sqlerror exit failure rollback
INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD, PASSWORD_CHANGED_AT)
VALUES ('${USERNAME}', '${HASH}', '${DISPLAY_NAME}', 'Y', 'N', SYSTIMESTAMP);
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('${USERNAME}', '${ROLE}');
COMMIT;
set pagesize 20 linesize 120
col USERNAME format a24
col ROLE_ID format a12
SELECT u.USERNAME, r.ROLE_ID, u.IS_ACTIVE FROM APP_USER u JOIN APP_USER_ROLE r ON r.USERNAME = u.USERNAME WHERE u.USERNAME = '${USERNAME}';
exit
SQL
)"

printf '%s\n' "$SQL" | ssh -o BatchMode=yes "$HOST" \
  "PW=\$(grep ^INCIDENT_DASH_DB_PASSWORD= $ENV_FILE | cut -d= -f2-); docker exec -i incident-demo-oracle sqlplus -s \"INCIDENT_DASH/\${PW}@//localhost:1521/INCIDENTDB\"" \
  2> >(grep -v "post-quantum\|store now\|may need\|^\*\*" >&2)

echo "Done. Log in at http://172.16.54.213:8090 as ${USERNAME}."
