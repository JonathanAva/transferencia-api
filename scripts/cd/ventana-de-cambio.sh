#!/usr/bin/env bash
#
# Ventana de cambio de producción: prod solo se despliega de LUNES a JUEVES, dentro del horario configurado
# (por defecto de 08:00 a 15:59), hora de El Salvador.
#
# Uso:  scripts/cd/ventana-de-cambio.sh                                   (hora actual)
#       AHORA="2026-11-06T16:55:00-06:00" scripts/cd/ventana-de-cambio.sh  (otra fecha, para las pruebas)
#
# Horario: VENTANA_HORA_INICIO y VENTANA_HORA_FIN (horas enteras; FIN no incluida). En el pipeline vienen de las
# variables del repositorio VENTANA_PROD_HORA_INICIO / VENTANA_PROD_HORA_FIN; si no existen, se usa 8 y 16.
# Una variable del repositorio se cambia sin PR, por eso los LÍMITES están aquí y solo cambian con un PR revisado:
#   - días: lunes a jueves, siempre (nunca viernes ni fin de semana);
#   - horas: inicio desde las 06:00 y fin hasta las 18:00, con inicio < fin.
# Si las variables piden algo fuera de esos límites, el despliegue falla (no se "corrige" en silencio).
#
# Incidente INC-2026-118: el despliegue a prod se hizo un viernes por la tarde (los clientes reportaron a las 4:55 p.m.).
# Con esta ventana, después de un despliegue quedan horas de oficina y un día hábil con el equipo completo.
# - Corre en el job desplegar-prod DESPUÉS de la aprobación: aprobar fuera de ventana tampoco despliega.
# - No hay excepción por rama, usuario ni parámetro. Emergencias: proceso de cambio de emergencia del README.
# - Feriados: fuera del alcance (se agregarían como una lista de fechas).
set -euo pipefail

# El Salvador usa UTC-6 todo el año (sin horario de verano). "CST6" es la zona POSIX equivalente: no depende de la
# base de zonas horarias del sistema, así que el resultado es el mismo en el runner y en Git Bash.
ZONA="CST6"
DIAS_PERMITIDOS="1 2 3 4"   # date +%u: 1 = lunes … 7 = domingo. Fijo: solo cambia con un PR.
LIMITE_INICIO=6             # ninguna ventana empieza antes de las 06:00
LIMITE_FIN=18               # ninguna ventana termina después de las 18:00

fallar() { echo "::error::$1"; exit 1; }

INICIO="${VENTANA_HORA_INICIO:-8}"
FIN="${VENTANA_HORA_FIN:-16}"
[[ "$INICIO" =~ ^[0-9]{1,2}$ && "$FIN" =~ ^[0-9]{1,2}$ ]] \
  || fallar "Ventana de cambio inválida: inicio '$INICIO' y fin '$FIN' deben ser horas enteras."
INICIO=$((10#$INICIO)); FIN=$((10#$FIN))   # "08" → 8 (sin 10#, bash leería "08" como octal inválido)
(( INICIO >= LIMITE_INICIO && FIN <= LIMITE_FIN && INICIO < FIN )) \
  || fallar "Ventana de cambio fuera de los límites: $INICIO–$FIN h. Debe estar entre las 0$LIMITE_INICIO:00 y las $LIMITE_FIN:00, con inicio < fin (cambiar los límites exige un PR)."

AHORA="${AHORA:-now}"
dia=$(TZ="$ZONA" date -d "$AHORA" +%u)
hora=$(TZ="$ZONA" date -d "$AHORA" +%H)
hora=$((10#$hora))
legible=$(TZ="$ZONA" date -d "$AHORA" '+%A %Y-%m-%d %H:%M (UTC-6)')
ventana=$(printf 'lunes a jueves, %02d:00–%02d:59' "$INICIO" "$((FIN - 1))")

# Queda en el resumen del run: qué ventana se aplicó y a qué hora se evaluó.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "- **Ventana de cambio de prod:** $ventana (UTC-6). Evaluada: $legible" >> "$GITHUB_STEP_SUMMARY"
fi

if [[ " $DIAS_PERMITIDOS " != *" $dia "* ]] || (( hora < INICIO || hora >= FIN )); then
  fallar "Fuera de la ventana de cambio de prod ($ventana, UTC-6): $legible."
fi
echo "✔ Dentro de la ventana de cambio de prod ($ventana): $legible"
