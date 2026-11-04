# Post-mortem INC-2026-118 — Transferencias fallidas y duplicadas tras desplegar 2.3.0

**Cuándo:** viernes por la tarde · **Severidad:** alta (dinero de clientes) · **Formato:** sin culpables; se analizan procesos, no personas.

## Qué pasó e impacto

Se desplegó `transfers-api-2.3.0-SNAPSHOT` apuntando al **core de QA** y con la **idempotencia apagada**.

**Línea de tiempo:** despliegue antes de las 16:40 (supuesto: el log no trae hora) → **16:40** empiezan los 504 → **16:55** los clientes reportan el problema desde la app → **17:20** terminan los 504.
- 14 % de respuestas 504; **37 clientes** con transferencias duplicadas (doble débito); contraseña del core expuesta.
- Transferencias en el core de QA y con **hora corrida**: 14:32 (-06:00) → 08:32, no 20:32 UTC.

## Causa raíz

- **Técnica:** core de QA → p95 9.8 s > timeout 5 s → 504 → la app reintenta → sin idempotencia → transferencia nueva. Era un timeout de **lectura**: el core ya había recibido y procesado la solicitud.
- **Hora (bug aparte):** `hh` (12 h) en vez de `HH`, o restar el offset; ambos dan 08:32. Se distinguen en las de 19:00–06:59 hora local: con `hh` quedan bien; con la resta, 12 h antes.
- **Sistémica:** el pipeline dejaba saltarse todos los controles: no existían o se podían apagar.
- **Detección:** lo reportaron los clientes, 15 min después del primer 504; el monitoreo no alertó.

## Corrección inmediata

1. Congelar despliegues, volver a la versión estable anterior, rotar la contraseña de `svc_core` y revocar el usuario personal.
2. Conciliar 16:40–17:20 en ambos cores, revertir los duplicados y avisar a los 37 clientes.
3. Confirmar la hipótesis del bug de hora y corregir los registros afectados.

## Controles preventivos

| Pipeline: antes (log del incidente) | Después | Dónde |
|---|---|---|
| `Coverage: 61% (threshold check disabled)` | JaCoCo ≥ 80 % fijo en `verify`; falla si no hay reporte o se ejecutaron 0 pruebas | [pom.xml](../pom.xml), [verificar-evidencia.sh](../scripts/ci/verificar-evidencia.sh) |
| `Authenticating as user: jlopez.contractor` | `GITHUB_TOKEN` efímero del workflow; `packages: write` solo al publicar | [cd.yml](../.github/workflows/cd.yml) |
| `Artifact: transfers-api-2.3.0-SNAPSHOT` | Solo tag `vX.Y.Z` = versión del pom, sin SNAPSHOT, desde `main`; la misma imagen **por digest** en dev → qa → prod | [cd.yml](../.github/workflows/cd.yml) |
| `runtime 4.3.0 not supported, using fallback` | Versiones exactas (Temurin 21.0.13 en la imagen, Kong 3.9.1, actions por SHA); si no arranca, la prueba de humo detiene el despliegue: no hay *fallback* | [Dockerfile](../Dockerfile), [smoke-ambiente.sh](../scripts/cd/smoke-ambiente.sh) |
| `Approval step: skipped (branch = hotfix/*)` | Environment `prod`: revisor ≠ autor, sin bypass, solo tags; `main` con ruleset sin excepciones | [cd.yml](../.github/workflows/cd.yml), [README](../README.md#configurar-los-environments-en-github) |
| Despliegue un viernes por la tarde | Prod solo de lunes a jueves en horario, después de la aprobación. Emergencia: aprueba el responsable de cambios, motivo registrado, revisión posterior | [ventana-de-cambio.sh](../scripts/cd/ventana-de-cambio.sh), [proceso](../README.md#cambio-de-emergencia-fuera-de-la-ventana) |

| Hallazgo | Control | Dónde |
|---|---|---|
| Core de QA | URL fuera del artefacto; con perfil `prod` la app no arranca si la URL es de otro ambiente; el CI valida los overlays | [GuardiaAmbiente](../src/main/java/com/izalco/transferencias/config/GuardiaAmbiente.java), [validar-config-ambientes.sh](../scripts/ci/validar-config-ambientes.sh) |
| Contraseña | Solo por variable de entorno o Secret; sin ella no arranca; el build falla si un YAML trae un secreto | [CoreProperties](../src/main/java/com/izalco/transferencias/config/CoreProperties.java), [ArchivosDeConfiguracionTest](../src/test/java/com/izalco/transferencias/config/ArchivosDeConfiguracionTest.java) |
| Timeout < p95 | Conexión 2 s y lectura 15 s; lectura vencida → `PENDIENTE_CONFIRMACION` | [ClienteCore](../src/main/java/com/izalco/transferencias/core/ClienteCore.java) |
| Sin idempotencia | `X-Idempotency-Key` obligatorio, reservado en PostgreSQL antes del core; no se puede apagar | [ServicioTransferencias](../src/main/java/com/izalco/transferencias/dominio/ServicioTransferencias.java) |
| `apiId` de QA | `client_id` propio por environment; prod falla si su huella es la de dev o qa | [kong-prod.yaml](../gateway/kong-prod.yaml), [cd.yml](../.github/workflows/cd.yml) |
| Una réplica | 2 réplicas de API y Kong, con PDB; el CI lo exige | [overlay prod](../k8s/overlays/prod/kustomization.yaml) |
| Detección | Alertas: 504 > 2 % en 5 min; p95 del core > 80 % del timeout de lectura | *Propuesto* (logs ECS) |
| Hora corrida | `withOffsetSameInstant(UTC)` + `HH`; pruebas de las dos hipótesis | [MapeadorCore](../src/main/java/com/izalco/transferencias/core/MapeadorCore.java), [MapeadorCoreTest](../src/test/java/com/izalco/transferencias/core/MapeadorCoreTest.java) |

Configuración comparada: [original](incidente/config-prod.original.yaml) → [corregida](incidente/config-prod.corregido.yaml), con un comentario por cambio.
**Pendiente:** conciliación automática de `PENDIENTE_CONFIRMACION` y medir el p95 del core de prod.
