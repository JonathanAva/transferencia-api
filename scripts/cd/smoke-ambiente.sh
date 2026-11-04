#!/usr/bin/env bash
#
# Prueba de humo de un ambiente recién desplegado (API en :8080 solo para health, Kong en :8000).
# Comprueba que la imagen funciona Y que las políticas de API se aplican:
#   1. /actuator/health = UP y /actuator/info dice el ambiente correcto (perfil bien configurado).
#   2. Por Kong, sin client_id → 401; con un client_id inválido → 401.
#   3. Por Kong, con el client_id del ambiente → POST /transferencias responde 201 (flujo completo hasta el core).
#   4. Superar el límite del ambiente → 429 con Retry-After.
#
# Uso: CLIENT_ID_APP_MOVIL=<secreto> scripts/cd/smoke-ambiente.sh <dev|qa|prod>
set -euo pipefail

AMBIENTE="${1:?Uso: smoke-ambiente.sh <dev|qa|prod>}"
API=http://127.0.0.1:8080
KONG=http://127.0.0.1:8000
LIMITE=$(grep -E '^\s+minute:' "gateway/kong-$AMBIENTE.yaml" | awk '{print $2}')

fallar() { echo "::error::[$AMBIENTE] $1"; exit 1; }
ok() { echo "  ✔ $1"; }
uuid() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'; }
status() { curl --silent --output /dev/null --write-out '%{http_code}' "$@"; }

# 1. Salud y perfil.
salud=$(curl --silent --fail "$API/actuator/health") || fallar "health no responde"
echo "$salud" | grep -q '"status":"UP"' || fallar "health no está UP: $salud"
info=$(curl --silent --fail "$API/actuator/info")
echo "$info" | grep -q "\"ambiente\":\"$AMBIENTE\"" || fallar "la API no corre con el perfil $AMBIENTE: $info"
ok "health UP, perfil $AMBIENTE ($info)"

# 2. Client ID obligatorio.
codigo=$(status "$KONG/transferencias/$(uuid)")
[ "$codigo" = 401 ] || fallar "sin client_id se esperaba 401 y llegó $codigo"
ok "sin client_id → 401"
codigo=$(status -H "client_id: no-es-valido" "$KONG/transferencias/$(uuid)")
[ "$codigo" = 401 ] || fallar "con client_id inválido se esperaba 401 y llegó $codigo"
ok "client_id inválido → 401"

# 3. Flujo completo con el client_id del ambiente.
cuerpo='{"cuentaOrigen":"100200300","cuentaDestino":"900800700","bancoDestino":"BIZ-0042","monto":150.75,"moneda":"USD","concepto":"Prueba de humo","fechaSolicitud":"2026-10-01T14:32:10-06:00"}'
codigo=$(status -X POST "$KONG/transferencias" -H "client_id: $CLIENT_ID_APP_MOVIL" \
  -H "X-Idempotency-Key: $(uuid)" -H "Content-Type: application/json" --data "$cuerpo")
[ "$codigo" = 201 ] || fallar "POST con client_id válido: se esperaba 201 y llegó $codigo"
ok "POST /transferencias con client_id → 201"

# 4. Rate limiting: se envían solicitudes hasta recibir 429. El tope (2 x límite) cubre el caso de que la
#    ráfaga cruce el cambio de minuto y el contador se reinicie a mitad.
intentos=0; recibido=""
while [ "$intentos" -lt $((LIMITE * 2 + 5)) ]; do
  intentos=$((intentos + 1))
  recibido=$(status -H "client_id: $CLIENT_ID_APP_MOVIL" "$KONG/transferencias/$(uuid)")
  [ "$recibido" = 429 ] && break
  [ "$recibido" = 404 ] || fallar "GET con client_id: se esperaba 404 (no existe) o 429 y llegó $recibido"
done
[ "$recibido" = 429 ] || fallar "no se recibió 429 tras $intentos solicitudes (límite $LIMITE/min)"
# El POST anterior también cuenta: el 429 puede llegar en la solicitud número LIMITE, nunca antes.
[ "$intentos" -ge "$LIMITE" ] || fallar "429 demasiado pronto: solicitud $intentos con límite $LIMITE"
curl --silent --dump-header - --output /dev/null -H "client_id: $CLIENT_ID_APP_MOVIL" "$KONG/transferencias/$(uuid)" \
  | grep -qi '^retry-after:' || fallar "el 429 no trae Retry-After"
ok "límite de $LIMITE/min → 429 en la solicitud $intentos, con Retry-After"

echo "✅ Prueba de humo de $AMBIENTE superada."
