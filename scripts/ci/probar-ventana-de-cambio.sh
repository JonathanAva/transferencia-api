#!/usr/bin/env bash
#
# Pruebas de scripts/cd/ventana-de-cambio.sh: cada caso fija la fecha con AHORA (y, si hace falta, el horario)
# y compara el resultado esperado. Las corre el job config-ambientes del CI, así que la ventana de prod no puede
# romperse sin que falle un PR.
set -euo pipefail

SCRIPT="scripts/cd/ventana-de-cambio.sh"
fallos=0

# caso <permite|bloquea> <fecha> <descripción> [inicio fin]
caso() {
  local esperado="$1" ahora="$2" descripcion="$3" inicio="${4:-}" fin="${5:-}" obtenido
  if AHORA="$ahora" VENTANA_HORA_INICIO="$inicio" VENTANA_HORA_FIN="$fin" GITHUB_STEP_SUMMARY="" \
       bash "$SCRIPT" > /dev/null 2>&1; then
    obtenido="permite"
  else
    obtenido="bloquea"
  fi
  if [ "$obtenido" = "$esperado" ]; then
    echo "  ok   $esperado  $ahora  ${inicio:+[$inicio–$fin h] }$descripcion"
  else
    echo "::error::Ventana de cambio: $ahora ${inicio:+[$inicio–$fin h] }($descripcion) debía '$esperado' y obtuvo '$obtenido'"
    fallos=$((fallos + 1))
  fi
}

echo "Horario por defecto (lunes a jueves, 08:00–15:59, UTC-6):"
caso bloquea "2026-11-06T16:55:00-06:00" "viernes 16:55: la hora del reporte del incidente"
caso bloquea "2026-11-06T09:00:00-06:00" "viernes por la mañana"
caso bloquea "2026-11-07T10:00:00-06:00" "sábado"
caso bloquea "2026-11-08T10:00:00-06:00" "domingo"
caso permite "2026-11-02T08:00:00-06:00" "lunes 08:00, primer minuto"
caso permite "2026-11-04T08:50:00-06:00" "miércoles por la mañana"
caso permite "2026-11-05T15:59:00-06:00" "jueves 15:59, último minuto"
caso bloquea "2026-11-05T16:00:00-06:00" "jueves 16:00, ya cerrada"
caso bloquea "2026-11-04T07:59:00-06:00" "miércoles 07:59, aún no abre"
# El runner trabaja en UTC: la ventana se evalúa en hora de El Salvador, no en la del runner.
caso bloquea "2026-11-04T13:59:00Z"      "miércoles 13:59 UTC = 07:59 en El Salvador"
caso permite "2026-11-04T14:00:00Z"      "miércoles 14:00 UTC = 08:00 en El Salvador"
caso permite "2026-11-05T21:59:00Z"      "jueves 21:59 UTC = 15:59 en El Salvador"
caso bloquea "2026-11-05T22:00:00Z"      "jueves 22:00 UTC = 16:00 en El Salvador"
caso bloquea "2026-11-06T02:00:00Z"      "viernes 02:00 UTC = jueves 20:00 en El Salvador"

echo "Horario desde las variables del repositorio:"
caso bloquea "2026-11-04T08:30:00-06:00" "antes del inicio configurado" 9 15
caso permite "2026-11-04T14:59:00-06:00" "último minuto configurado" 9 15
caso bloquea "2026-11-04T15:00:00-06:00" "fin configurado (no incluido)" 9 15
caso permite "2026-11-04T09:30:00-06:00" "horas con cero a la izquierda" 09 15
caso bloquea "2026-11-06T10:00:00-06:00" "viernes: los días no se configuran" 9 15

echo "Límites fijos en el código (las variables no pueden saltárselos):"
caso bloquea "2026-11-04T10:00:00-06:00" "toda la noche (0–24)" 0 24
caso bloquea "2026-11-04T10:00:00-06:00" "termina después de las 18:00" 8 20
caso bloquea "2026-11-04T10:00:00-06:00" "empieza antes de las 06:00" 5 12
caso bloquea "2026-11-04T10:00:00-06:00" "inicio igual al fin" 10 10
caso bloquea "2026-11-04T10:00:00-06:00" "inicio después del fin" 15 9
caso bloquea "2026-11-04T10:00:00-06:00" "valor no numérico" ocho 16

if [ "$fallos" -gt 0 ]; then
  echo "::error::$fallos caso(s) de la ventana de cambio fallaron."
  exit 1
fi
echo "✔ Ventana de cambio: todos los casos correctos."
