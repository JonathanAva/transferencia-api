#!/usr/bin/env bash
#
# Falla si "mvnw verify" terminó en verde SIN haber probado nada de verdad.
#
# Por qué hace falta: con -DskipTests (o -Dmaven.test.skip, o -Djacoco.skip) Maven no ejecuta pruebas,
# JaCoCo no tiene datos de ejecución, el "check" de cobertura se SALTA ("Skipping JaCoCo execution due to
# missing execution data file") y el build termina en BUILD SUCCESS. Es el equivalente moderno del
# "threshold check disabled" del INC-2026-118. Este paso convierte ese verde falso en rojo.
#
# Uso: scripts/ci/verificar-evidencia.sh   (desde la raíz del repositorio, después de "mvnw verify")
set -euo pipefail

REPORTE_COBERTURA="target/site/jacoco/jacoco.xml"
REPORTES_PRUEBAS="target/surefire-reports"
RESUMEN="${GITHUB_STEP_SUMMARY:-/dev/null}"   # en GitHub Actions, se muestra en la página del run

fallar() {
  echo "::error::$1"
  echo "❌ $1" >> "$RESUMEN"
  exit 1
}

# 1. El reporte de cobertura debe existir: si no existe, JaCoCo no midió nada y su "check" no corrió.
[ -s "$REPORTE_COBERTURA" ] || fallar "No existe $REPORTE_COBERTURA: la cobertura no se midió (¿-DskipTests o -Djacoco.skip?)."

# 2. Deben haberse ejecutado pruebas. Se suma el atributo tests="N" de cada <testsuite> de surefire.
shopt -s nullglob
archivos=("$REPORTES_PRUEBAS"/TEST-*.xml)
[ ${#archivos[@]} -gt 0 ] || fallar "No hay reportes de pruebas en $REPORTES_PRUEBAS: no se ejecutó ninguna prueba."

total=0
for archivo in "${archivos[@]}"; do
  n=$(grep -o -m1 '<testsuite [^>]*' "$archivo" | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' || echo 0)
  total=$((total + n))
done
[ "$total" -gt 0 ] || fallar "Se ejecutaron 0 pruebas."

# 3. Resumen legible: cobertura de líneas total (último contador LINE del reporte = nivel proyecto).
linea=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$REPORTE_COBERTURA" | tail -1)
perdidas=$(echo "$linea" | sed -E 's/.*missed="([0-9]+)".*/\1/')
cubiertas=$(echo "$linea" | sed -E 's/.*covered="([0-9]+)".*/\1/')
porcentaje=$(awk -v c="$cubiertas" -v m="$perdidas" 'BEGIN { printf "%.1f", 100 * c / (c + m) }')

echo "✅ Pruebas ejecutadas: $total. Cobertura de líneas: $porcentaje % ($cubiertas de $((cubiertas + perdidas)))."
{
  echo "### Evidencia de pruebas"
  echo "| Pruebas ejecutadas | Cobertura de líneas | Umbral |"
  echo "|---|---|---|"
  echo "| $total | $porcentaje % ($cubiertas / $((cubiertas + perdidas))) | 80 % (gate de JaCoCo en verify) |"
} >> "$RESUMEN"
