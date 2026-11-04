#!/usr/bin/env bash
#
# Despliega UN ambiente con la imagen indicada POR DIGEST y Kong con su política, y ejecuta la prueba de humo.
#
# Uso:  CLIENT_ID_APP_MOVIL=<secreto> scripts/cd/desplegar-ambiente.sh <dev|qa|prod> <imagen@sha256:...>
#
# - CLIENT_ID_APP_MOVIL viene del secreto del GitHub Environment del ambiente (nunca de Git).
# - La imagen DEBE venir por digest: es la garantía de que dev, qa y prod ejecutan exactamente los mismos bytes.
#   (Solo en local, EXIGIR_DIGEST=0 permite probar con una imagen etiquetada.)
# - Escribe en $GITHUB_OUTPUT la huella (SHA-256) del client_id, para que prod compruebe que no comparte
#   credencial con otro ambiente sin que la credencial salga nunca del job.
set -euo pipefail

AMBIENTE="${1:?Uso: desplegar-ambiente.sh <dev|qa|prod> <imagen@sha256:...>}"
IMAGEN="${2:?Falta la imagen (por digest)}"
EXIGIR_DIGEST="${EXIGIR_DIGEST:-1}"
LIMPIAR="${LIMPIAR:-1}"

case "$AMBIENTE" in dev|qa|prod) ;; *) echo "::error::Ambiente desconocido: $AMBIENTE"; exit 1 ;; esac

if [ "$EXIGIR_DIGEST" = 1 ] && [[ ! "$IMAGEN" =~ @sha256:[0-9a-f]{64}$ ]]; then
  echo "::error::La imagen debe indicarse por digest (…@sha256:<64 hex>), no por tag: $IMAGEN"
  exit 1
fi
if [ -z "${CLIENT_ID_APP_MOVIL:-}" ]; then
  echo "::error::Falta el secreto CLIENT_ID_APP_MOVIL del environment '$AMBIENTE' (Settings → Environments → $AMBIENTE)."
  exit 1
fi

# Contraseña de la base desechable: aleatoria por ejecución y oculta en los logs de Actions.
DB_PASSWORD="$(openssl rand -hex 16)"
echo "::add-mask::$DB_PASSWORD"

# kong.yaml completado con el secreto: en una carpeta privada temporal, nunca en el repositorio.
DIR_SECRETO="$(mktemp -d)"
chmod 700 "$DIR_SECRETO"
# shellcheck disable=SC2016  # '${CLIENT_ID_APP_MOVIL}' es literal: solo esa variable se reemplaza
envsubst '${CLIENT_ID_APP_MOVIL}' < "gateway/kong-$AMBIENTE.yaml" > "$DIR_SECRETO/kong.yaml"
chmod 644 "$DIR_SECRETO/kong.yaml"   # Kong corre con otro UID dentro del contenedor; la carpeta sigue siendo 700

export AMBIENTE IMAGEN DB_PASSWORD
export KONG_CONFIG="$DIR_SECRETO/kong.yaml"
# Solo en Git Bash de Windows: Docker necesita la ruta en formato C:/... (en Linux no aplica).
if command -v cygpath > /dev/null; then KONG_CONFIG="$(cygpath -m "$KONG_CONFIG")"; fi
COMPOSE=(docker compose --file deploy/docker-compose.yml --project-name "transferencias-$AMBIENTE")

terminar() {
  codigo=$?
  if [ "$codigo" -ne 0 ]; then
    echo "::group::Logs del ambiente $AMBIENTE (falló)"
    "${COMPOSE[@]}" logs --no-color --tail 80 || true
    echo "::endgroup::"
  fi
  [ "$LIMPIAR" = 1 ] && "${COMPOSE[@]}" down --volumes --remove-orphans > /dev/null 2>&1 || true
  rm -rf "$DIR_SECRETO"
  exit "$codigo"
}
trap terminar EXIT

echo "== Desplegando $AMBIENTE con $IMAGEN"
"${COMPOSE[@]}" up --detach --wait --wait-timeout 180

# La imagen en ejecución es exactamente la pedida.
id_corriendo=$("${COMPOSE[@]}" ps --quiet transferencia-api | xargs docker inspect --format '{{.Image}}')
id_pedida=$(docker image inspect --format '{{.Id}}' "$IMAGEN")
[ "$id_corriendo" = "$id_pedida" ] || { echo "::error::El contenedor no ejecuta la imagen pedida"; exit 1; }

bash scripts/cd/smoke-ambiente.sh "$AMBIENTE"

huella=$(printf '%s' "$CLIENT_ID_APP_MOVIL" | sha256sum | cut -d' ' -f1)
echo "huella-client-id=$huella" >> "${GITHUB_OUTPUT:-/dev/null}"
{
  echo "### Ambiente $AMBIENTE desplegado"
  echo "- Imagen: \`$IMAGEN\`"
  echo "- Prueba de humo: health UP, perfil $AMBIENTE, 401 sin client_id, 201 con client_id, 429 al superar el límite"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
