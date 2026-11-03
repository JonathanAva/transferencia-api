#!/usr/bin/env bash
#
# Prueba de humo de la imagen: la arranca junto a un PostgreSQL desechable y espera a que
# /actuator/health responda UP. Comprueba que la imagen realmente funciona (JRE, capas, usuario,
# Flyway), no solo que se construyó. Al final borra todo lo que creó.
#
# Uso: scripts/ci/smoke-imagen.sh <imagen>      p. ej. scripts/ci/smoke-imagen.sh transferencia-api:ci
set -euo pipefail

IMAGEN="${1:?Uso: smoke-imagen.sh <imagen>}"
RED="smoke-$$"
DB="smoke-db-$$"
API="smoke-api-$$"
CLAVE_FALSA="clave-falsa-solo-smoke"

# shellcheck disable=SC2329  # se invoca desde el trap de abajo, no directamente
limpiar() {
  docker logs "$API" > smoke-api.log 2>&1 || true
  docker rm -f "$API" "$DB" > /dev/null 2>&1 || true
  docker network rm "$RED" > /dev/null 2>&1 || true
}
trap limpiar EXIT

# Corre como no root (UID 10001), como exige Kubernetes con runAsNonRoot.
usuario=$(docker image inspect "$IMAGEN" --format '{{.Config.User}}')
[ "$usuario" = "10001:10001" ] || { echo "::error::La imagen corre como '$usuario', se esperaba 10001:10001"; exit 1; }

docker network create "$RED" > /dev/null
docker run -d --name "$DB" --network "$RED" \
  -e POSTGRES_DB=transferencias -e POSTGRES_USER=transferencias -e POSTGRES_PASSWORD="$CLAVE_FALSA" \
  postgres:17-alpine > /dev/null

# El core no hace falta para el health; se apunta a un puerto sin servicio (credenciales falsas).
docker run -d --name "$API" --network "$RED" -p 127.0.0.1:18080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e DB_URL="jdbc:postgresql://$DB:5432/transferencias" -e DB_USUARIO=transferencias -e DB_PASSWORD="$CLAVE_FALSA" \
  -e CORE_URL=http://core-inexistente:9/core/transferencias -e CORE_USUARIO=usuario-smoke -e CORE_PASSWORD="$CLAVE_FALSA" \
  "$IMAGEN" > /dev/null

for _ in $(seq 1 60); do
  if curl --fail --silent http://127.0.0.1:18080/actuator/health | grep -q '"status":"UP"'; then
    echo "✅ La imagen arrancó y /actuator/health responde UP."
    curl --silent http://127.0.0.1:18080/actuator/info; echo
    exit 0
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$API")" != "true" ]; then
    echo "::error::El contenedor de la API se detuvo durante el arranque."
    docker logs "$API" | tail -40
    exit 1
  fi
  sleep 2
done

echo "::error::La API no respondió UP en 120 s."
docker logs "$API" | tail -40
exit 1
