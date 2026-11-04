#!/usr/bin/env bash
#
# Valida la configuración de los tres ambientes ANTES de desplegar. Cada regla responde a un hallazgo
# del INC-2026-118:
#   - Manifiestos de Kubernetes válidos (kustomize build + kubeconform estricto).
#   - prod con al menos 2 réplicas de la API y de Kong                     (incidente: replicas: 1).
#   - Ningún ambiente usa URLs de otro ambiente; prod ninguna de no-prod    (incidente: prod → core-qa).
#   - Namespaces, consumidores y etiquetas propios de cada ambiente         (incidente: apiId igual a QA).
#   - Kong: configuración válida, límites distintos por ambiente y ninguna llave escrita en el archivo
#                                                                          (incidente: password en texto plano).
#
# Usa imágenes Docker con versión fija para kustomize, kubeconform y kong (mismo resultado en el CI y en local).
# Uso: scripts/ci/validar-config-ambientes.sh   (desde la raíz del repositorio)
#
# SC2015: "cond && ok || fallar" es seguro porque ok() siempre termina bien.
# SC2016: '${CLIENT_ID_APP_MOVIL}' va entre comillas simples a propósito: es el texto literal de la plantilla.
# shellcheck disable=SC2015,SC2016
set -euo pipefail

AMBIENTES=(dev qa prod)
MARCAS_NO_PROD="dev|desa|desarrollo|qa|test|pruebas|uat|staging"
KUSTOMIZE=registry.k8s.io/kustomize/kustomize:v5.8.3
KUBECONFORM=ghcr.io/yannh/kubeconform:v0.8.0
KONG=kong:3.9.1
KUBERNETES_VERSION=1.33.0

RAIZ="$(pwd -W 2>/dev/null || pwd)"       # en Git Bash de Windows, Docker necesita la ruta C:/...
SALIDA=target/config-ambientes
mkdir -p "$SALIDA"
export MSYS_NO_PATHCONV=1                  # que Git Bash no reescriba las rutas /repo/... de los contenedores
errores=0

fallar() { echo "::error::$1"; errores=$((errores + 1)); }
ok() { echo "  ✔ $1"; }

for amb in "${AMBIENTES[@]}"; do
  echo "== Ambiente $amb"
  render="$SALIDA/k8s-$amb.yaml"

  # 1. Manifiestos: kustomize build + kubeconform estricto (rechaza campos desconocidos o mal escritos).
  docker run --rm -v "$RAIZ:/repo" -w /repo "$KUSTOMIZE" build "k8s/overlays/$amb" > "$render"
  if docker run --rm -v "$RAIZ:/repo" -w /repo "$KUBECONFORM" -strict -summary \
       -kubernetes-version "$KUBERNETES_VERSION" "$render"; then
    ok "manifiestos válidos (kubeconform -strict)"
  else
    fallar "$amb: kubeconform rechazó los manifiestos"
  fi

  # 2. Namespace propio: todo lo que se despliega va a transferencias-<ambiente>.
  otros_ns=$(grep -E '^  namespace: ' "$render" | sort -u | grep -v "namespace: transferencias-$amb$" || true)
  [ -z "$otros_ns" ] && ok "namespace transferencias-$amb" || fallar "$amb: namespaces ajenos: $otros_ns"

  # 3. URLs: ningún ambiente apunta a otro. prod no puede contener ninguna marca de no-producción.
  urls=$(grep -E '^  (CORE_URL|DB_URL): ' "$render" || true)
  [ -n "$urls" ] || fallar "$amb: no se encontraron CORE_URL/DB_URL en el ConfigMap"
  if [ "$amb" = prod ]; then
    prohibidas="$MARCAS_NO_PROD"
  else
    prohibidas=$(printf '%s\n' "${AMBIENTES[@]}" | grep -v "^$amb$" | paste -sd'|')
  fi
  if echo "$urls" | tr '[:upper:]' '[:lower:]' | grep -Eq "(^|[^a-z0-9])($prohibidas)([^a-z0-9]|$)"; then
    fallar "$amb: URL de otro ambiente: $(echo "$urls" | tr '\n' ' ')"
  else
    ok "URLs sin marcas de otros ambientes"
  fi

  # 4. Alta disponibilidad en prod.
  if [ "$amb" = prod ]; then
    for dep in transferencia-api kong; do
      replicas=$(awk -v d="$dep" '/^kind: Deployment/{k=1} k&&/^  name: /{n=$2} k&&/^  replicas: /{if(n==d){print $2}; k=0}' "$render")
      [ "${replicas:-0}" -ge 2 ] && ok "$dep con $replicas réplicas" || fallar "prod: $dep tiene ${replicas:-0} réplicas (mínimo 2)"
    done
    grep -q '^kind: PodDisruptionBudget' "$render" && ok "PodDisruptionBudget presente" || fallar "prod: falta PodDisruptionBudget"
  fi

  # 5. Kong: plantilla sin llaves literales, etiquetada con su ambiente, consumidor propio, y válida para Kong.
  kong="gateway/kong-$amb.yaml"
  llaves=$(grep -E '^\s*-?\s*key:' "$kong" | grep -v 'key: "${CLIENT_ID_APP_MOVIL}"' || true)
  [ -z "$llaves" ] && ok "kong: ninguna llave escrita (solo \${CLIENT_ID_APP_MOVIL})" || fallar "$amb: llave literal en $kong"
  ajenas=$(grep -oE 'ambiente-[a-z]+' "$kong" | sort -u | grep -v "^ambiente-$amb$" || true)
  [ -z "$ajenas" ] && ok "kong: etiquetas solo de ambiente-$amb" || fallar "$amb: $kong tiene etiquetas de otro ambiente: $ajenas"
  grep -q "username: app-movil-$amb$" "$kong" && ok "kong: consumidor app-movil-$amb" || fallar "$amb: consumidor no es app-movil-$amb"

  # Se completa con una llave de prueba (no real) solo para que Kong valide el archivo.
  CLIENT_ID_APP_MOVIL="llave-de-validacion-$amb" envsubst '${CLIENT_ID_APP_MOVIL}' < "$kong" > "$SALIDA/kong-$amb.yaml"
  if docker run --rm -v "$RAIZ/$SALIDA:/k" -e KONG_DATABASE=off "$KONG" kong config parse "/k/kong-$amb.yaml" > /dev/null 2>&1; then
    ok "kong config parse"
  else
    fallar "$amb: Kong rechazó $kong"
  fi
done

# 6. Límites de rate limiting distintos por ambiente (políticas propias, no copiadas).
limites=$(for amb in "${AMBIENTES[@]}"; do grep -E '^\s+minute:' "gateway/kong-$amb.yaml" | awk '{print $2}'; done)
if [ "$(echo "$limites" | sort -u | wc -l)" -eq "${#AMBIENTES[@]}" ]; then
  echo "== Rate limiting por minuto (dev qa prod): $(echo "$limites" | paste -sd' ')"
else
  fallar "Los límites de rate limiting se repiten entre ambientes: $(echo "$limites" | paste -sd' ')"
fi

rm -f "$SALIDA"/kong-*.yaml   # solo eran para validar
if [ "$errores" -gt 0 ]; then
  echo "❌ $errores regla(s) incumplida(s)."
  exit 1
fi
echo "✅ Configuración de los ambientes válida."
