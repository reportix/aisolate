#!/usr/bin/env bash
# Mints a long-lived (1 year) access token against the local dev Keycloak, for Insomnia/curl debugging.
# Dev only: it raises the realm's token and session lifespans to a year on the RUNNING container.
# Tokens die whenever the container is recreated (start-dev keeps the realm keys in the container only),
# so re-run this after `docker compose down`.
#
#   ./docker/dev-token.sh [username] [client_id]     defaults: jasmine aisolate-ui
#   KC_REALM=acme ./docker/dev-token.sh eric acme-chat   an application user (default realm: aisolate, the operators')
set -euo pipefail

KC=${KC_URL:-http://localhost:8180}
REALM=${KC_REALM:-aisolate}
USER_NAME=${1:-jasmine}
CLIENT=${2:-aisolate-ui}
PASSWORD=${KC_USER_PASSWORD:-password}
YEAR=31536000

admin=$(curl -sf -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d username="${KC_ADMIN:-admin}" -d password="${KC_ADMIN_PASSWORD:-admin}" \
  -d grant_type=password | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

# Access-token expiry is capped by the session lifespans, so all of them have to move.
curl -sf "$KC/admin/realms/$REALM" -H "Authorization: Bearer $admin" \
  | python3 -c "
import sys, json
realm = json.load(sys.stdin)
realm.update({k: $YEAR for k in ('accessTokenLifespan', 'ssoSessionIdleTimeout', 'ssoSessionMaxLifespan',
                                 'clientSessionIdleTimeout', 'clientSessionMaxLifespan')})
print(json.dumps(realm))
" \
  | curl -sf -X PUT "$KC/admin/realms/$REALM" -H "Authorization: Bearer $admin" \
      -H 'Content-Type: application/json' --data-binary @-

curl -sf -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d client_id="$CLIENT" -d username="$USER_NAME" -d password="$PASSWORD" -d grant_type=password -d scope="openid email" \
  | python3 -c '
import sys, json, base64, datetime
token = json.load(sys.stdin)["access_token"]
payload = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "=="))
print(token)
print("sub       ", payload["sub"], file=sys.stderr)
print("user      ", payload.get("preferred_username"), payload.get("realm_access", {}).get("roles"), file=sys.stderr)
print("aud / azp ", payload["aud"], "/", payload["azp"], file=sys.stderr)
print("expires   ", datetime.datetime.fromtimestamp(payload["exp"]).isoformat(sep=" "), file=sys.stderr)
'
