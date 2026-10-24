#!/usr/bin/env bash
# Mints one access token per demo user and prints them as an Insomnia environment object.
# Re-run after `docker compose down -v`: that wipes Keycloak, which regenerates the realm signing
# keys and the user ids, so every previously issued token stops verifying.
# Application users come from the organisation's realm (acme), operators from AIsolate's (aisolate).
set -euo pipefail
USERS=${AISOLATE_USERS_ISSUER:-http://localhost:8180/realms/acme}
OPERATORS=${AISOLATE_OPERATORS_ISSUER:-http://localhost:8180/realms/aisolate}

mint() { # mint <issuer> <username> <client-id>
  curl -sf -X POST "$1/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$3" -d username="$2" -d password=password \
    -d scope="openid aisolate profile email" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

employee=$(mint "$USERS" eric acme-chat)
ceo=$(mint "$USERS" nora acme-chat)
founder=$(mint "$USERS" leo acme-chat)
intern=$(mint "$USERS" ivy acme-chat)
developer=$(mint "$OPERATORS" jasmine aisolate-ui)
admin=$(mint "$OPERATORS" andrew aisolate-ui)
guest=$(mint "$OPERATORS" guest aisolate-ui)

python3 - "$employee" "$ceo" "$founder" "$intern" "$developer" "$admin" "$guest" <<'PY'
import json, sys
employee, ceo, founder, intern, developer, admin, guest = sys.argv[1:8]
print(json.dumps({
    "token": developer,
    "token_employee": employee,
    "token_ceo": ceo,
    "token_founder": founder,
    "token_intern": intern,
    "token_developer": developer,
    "token_admin": admin,
    "token_guest": guest,
}, indent=2))
PY
