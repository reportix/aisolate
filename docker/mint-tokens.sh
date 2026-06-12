#!/usr/bin/env bash
# Mints one access token per demo user and prints them as an Insomnia environment object.
# Re-run after `docker compose down -v`: that wipes Keycloak, which regenerates the realm signing
# keys and the user ids, so every previously issued token stops verifying.
set -euo pipefail
ISSUER=${AISOLATE_ISSUER_URI:-http://localhost:8180/realms/acme}

mint() { # mint <username> <client-id>
  curl -sf -X POST "$ISSUER/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$2" -d username="$1" -d password=password \
    -d scope="openid aisolate profile email" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

employee=$(mint eric acme-chat)
ceo=$(mint nora acme-chat)
founder=$(mint leo acme-chat)
intern=$(mint ivy acme-chat)
developer=$(mint jasmine aisolate-ui)
admin=$(mint andrew aisolate-ui)

python3 - "$employee" "$ceo" "$founder" "$intern" "$developer" "$admin" <<'PY'
import json, sys
employee, ceo, founder, intern, developer, admin = sys.argv[1:7]
print(json.dumps({
    "token": developer,
    "token_employee": employee,
    "token_ceo": ceo,
    "token_founder": founder,
    "token_intern": intern,
    "token_developer": developer,
    "token_admin": admin,
}, indent=2))
PY
