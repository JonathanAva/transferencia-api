# transferencia-api — Banco Izalco Digital

API para que la app móvil de Banco Izalco Digital registre y consulte transferencias
interbancarias, integrándose con el core bancario legado (XML).

> Documento en construcción. Las secciones de arquitectura, ejecución y pipeline se completan
> en las siguientes fases.

## Contrato de la API

El contrato es **contract-first** en OpenAPI 3.1:
[`src/main/resources/openapi/transferencias.yaml`](src/main/resources/openapi/transferencias.yaml).

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/transferencias` | Registra una transferencia (requiere `X-Idempotency-Key`). |
| `GET` | `/transferencias/{id}` | Consulta una transferencia en cualquier estado. |

Validar el contrato:

```powershell
npx --yes @redocly/cli@2.64.1 lint
```

## Ejecutar en local

Requisitos: Docker Desktop y JDK 21.

```powershell
.\mvnw.cmd clean verify          # pruebas + gate de cobertura (usa Testcontainers: Docker encendido)
docker compose up --build -d     # PostgreSQL + core simulado (WireMock) + API en http://localhost:8080
.\scripts\probar-local.ps1       # recorre todos los escenarios del core
docker compose down -v           # apaga todo y borra los datos
```

El core simulado ([`wiremock/mappings`](wiremock/mappings)) decide la respuesta según la **cuenta origen**:

| Cuenta origen | Respuesta del core | Respuesta de la API |
|---|---|---|
| cualquier otra (p. ej. `100200300`) | `00` aprobada | `201` `APROBADA` |
| `510000000` | `51` saldo insuficiente | `422` `SALDO_INSUFICIENTE` |
| `620000000` | `62` cuenta bloqueada | `422` `CUENTA_BLOQUEADA` |
| `910000000` | `91` no disponible | `503` `CORE_NO_DISPONIBLE` |
| `990000001` | tarda 20 s (más que el timeout de lectura de 15 s) | `504` `CORE_TIMEOUT` |
| `990000002` | código `77` no documentado | `502` `RESPUESTA_CORE_INVALIDA` |
| `990000003` | cuerpo que no es XML | `502` `RESPUESTA_CORE_INVALIDA` |

Las credenciales de `docker-compose.yml` son falsas y solo sirven en local; los puertos se publican en
`127.0.0.1`. Si las credenciales del core no coinciden, el simulador responde `401` (la API lo traduce a `502`).

## Integración continua

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) corre en cada push a `main` y en cada pull request:

```mermaid
flowchart LR
    A[push / PR] --> C[contrato<br/>Redocly lint]
    A --> B[build<br/>mvnw verify<br/>JDK 21 Temurin]
    B --> E{¿jacoco.xml existe<br/>y pruebas > 0?}
    E -- no --> X[falla]
    E -- sí --> R[artefactos:<br/>cobertura, pruebas,<br/>jar probado + SHA-256]
    R --> I[imagen<br/>docker build con<br/>EL MISMO jar]
    I --> S[prueba de humo<br/>PostgreSQL + health UP]
```

| Job | Qué hace | Falla si… |
|---|---|---|
| `contrato` | `redocly lint` del contrato OpenAPI | el contrato tiene errores o advertencias |
| `build` | `mvnw verify`: pruebas (Testcontainers) + gate JaCoCo ≥ 80 % de líneas | una prueba falla o la cobertura baja de 80 % |
| | [`verificar-evidencia.sh`](scripts/ci/verificar-evidencia.sh) | no existe `jacoco.xml` o se ejecutaron 0 pruebas (p. ej. con `-DskipTests`) |
| | Publica `reporte-cobertura`, `reporte-pruebas` y `jar-probado` (jar + SHA-256) | — |
| `imagen` | Descarga `jar-probado`, verifica el SHA-256 y construye la imagen **sin recompilar** | el jar no es el que pasó `verify` |
| | [`smoke-imagen.sh`](scripts/ci/smoke-imagen.sh): arranca la imagen con PostgreSQL | no corre como UID 10001 o `/actuator/health` no responde `UP` |

**Build once.** El jar que pasa `verify` es el que entra en la imagen: el job `imagen` lo pasa al Dockerfile
con `--build-context jar=artefacto`, que reemplaza la etapa `jar` del Dockerfile, así que la etapa de
compilación no se ejecuta. El build vuelve a comprobar el SHA-256 (`JAR_SHA256`) y lo deja como etiqueta
`com.izalco.jar.sha256` en la imagen, junto con el commit (`org.opencontainers.image.revision`).

**Seguridad del workflow.**
- Actions fijadas por **SHA completo** del commit (con la versión en un comentario).
- `permissions: contents: read`.
- `checkout` con `persist-credentials: false`.
- Runner `ubuntu-24.04` fijo.

### Protección de `main`

La protección de ramas exige un repositorio público en el plan gratuito. Configuración (ruleset):

1. *Settings → Rules → Rulesets → New ruleset → New branch ruleset*.
2. Nombre `proteger-main`, *Enforcement status*: **Active**, *Bypass list*: **vacía**.
3. *Target branches → Add target → Include default branch*.
4. Reglas:
   - **Restrict deletions** y **Block force pushes**.
   - **Require a pull request before merging**.
   - **Require status checks to pass**, con *Require branches to be up to date before merging*. Agregar los tres checks:
     - `Lint del contrato OpenAPI`
     - `Build, pruebas y cobertura`
     - `Imagen Docker con el jar probado`

Con esto nadie puede llevar a `main` un cambio que no haya pasado el CI, ni siquiera el administrador. La bypass list está vacía.

## Configuración por ambiente

Un solo artefacto para todos los ambientes; lo que cambia se elige con `SPRING_PROFILES_ACTIVE`
(`dev`, `qa` o `prod`) y **las URLs y credenciales llegan solo por variables de entorno** (en Kubernetes,
desde un `Secret`). Ningún archivo de configuración contiene URLs ni contraseñas, y una prueba lo verifica.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sí | `dev`, `qa` o `prod` (solo uno) |
| `DB_URL`, `DB_USUARIO`, `DB_PASSWORD` | sí | Conexión a PostgreSQL |
| `CORE_URL`, `CORE_USUARIO`, `CORE_PASSWORD` | sí | Endpoint y usuario de servicio del core |
| `CORE_TIMEOUT_CONEXION` | no (2 s) | Timeout para conectar con el core |
| `CORE_TIMEOUT_LECTURA` | no (15 s) | Timeout para recibir la respuesta; debe superar el p95 real del core |
| `IDEMPOTENCIA_EXPIRACION` | no (24 h) | Tiempo durante el cual se recuerda una llave |
| `DB_POOL_MAXIMO` | no (5 / 10 / 20) | Conexiones del pool por réplica (dev / qa / prod) |

**Fail fast:** si falta una variable obligatoria, la aplicación no arranca.
**Guardia de prod:** con el perfil `prod`, la aplicación no arranca si `CORE_URL` o `DB_URL` contienen
`dev`, `qa`, `test`, `uat`, `staging`… (palabra completa), ni si hay dos perfiles de ambiente activos.
Corre antes de crear cualquier bean, así que Flyway nunca llega a conectarse a la base equivocada.

| | dev | qa | prod |
|---|---|---|---|
| Logs | texto, `DEBUG` para `com.izalco` | JSON (ECS), `INFO` | JSON (ECS), `INFO` |
| Pool de conexiones | 5 | 10 | 20 |

Todos los logs llevan el `correlationId` y los números de cuenta enmascarados (`*****0300`); el concepto no
se registra. Actuator expone solo `/actuator/health` (con `liveness` y `readiness`) e `/actuator/info`
(ambiente y versión del artefacto).

## Cadena de timeouts

Cada eslabón debe esperar **más** que el siguiente. Si no, alguien se rinde mientras el de atrás
todavía está trabajando, y un reintento puede duplicar la transferencia.

```
app móvil (30 s)  >  gateway Kong (20 s)  >  API → core, lectura (15 s)  >  p95 del core (medido)
```

| Eslabón | Valor de referencia | Configuración |
|---|---|---|
| App móvil | 30 s | Cliente HTTP de la app |
| Gateway (Kong) | 20 s | `read_timeout` del servicio en Kong (Fase 7) |
| API → core (lectura) | 15 s | `CORE_TIMEOUT_LECTURA` |
| API → core (conexión) | 2 s | `CORE_TIMEOUT_CONEXION` (debe ser menor que la lectura) |
| Core | p95 medido por ambiente | Monitoreo; en el incidente el p95 fue 9.8 s con timeout de 5 s |
| Llave atrapada | lectura + 30 s = 45 s | `IDEMPOTENCIA_MARGEN_EN_PROCESO` |

**¿Qué pasa si alguien se rinde antes?**

- **La app se rinde antes que la API** (por ejemplo, su timeout es menor) y reintenta con la **misma**
  llave mientras la primera solicitud sigue esperando al core → `409 SOLICITUD_EN_PROCESO`. No se llama otra
  vez al core ni se duplica nada. La app reintenta en unos segundos y, cuando la primera termina, recibe la
  respuesta guardada (`Idempotent-Replayed: true`).
- **El gateway se rinde antes que la API** → la app recibe un 504 de Kong, pero la API sigue y guarda el
  resultado; el reintento con la misma llave recibe 409 o la respuesta guardada.
- **La API se rinde ante el core** (timeout de lectura) → `504 CORE_TIMEOUT`, transferencia
  `PENDIENTE_CONFIRMACION`, llave cerrada: los reintentos reciben siempre ese 504 guardado.
- **El pod de la API muere a mitad de la llamada** → la llave queda `EN_PROCESO`. Pasado
  *timeout de lectura + margen*, el siguiente reintento la cierra como incierta (`504`,
  `PENDIENTE_CONFIRMACION`). Nunca se libera, porque el core pudo haber recibido la transferencia.

La regla para la app: **un reintento siempre usa la misma `X-Idempotency-Key`**. Una llave nueva solo
para una transferencia nueva.

## Supuestos

Decisiones tomadas donde el enunciado no define el comportamiento:

1. **Formato de `X-Idempotency-Key`:** UUID generado por la app, uno por intento lógico de transferencia.
2. **Validaciones de entrada:** cuentas de 6 a 20 dígitos y distintas entre sí; `bancoDestino` con
   formato `AAA-9999` (según el ejemplo `BIZ-0042`); `concepto` de 1 a 140 caracteres; monto mayor que 0,
   máximo 2 decimales significativos y 13 dígitos enteros (ver supuesto 16); `fechaSolicitud` debe incluir zona horaria.
3. **Glosa del core:** si el concepto supera 30 caracteres se **trunca** (no se rechaza), se pasa a
   mayúsculas y se eliminan tildes y caracteres no ASCII.
4. **Respuesta del core:** no está definida en el enunciado; se asume
   `<TransferenciaResponse><Codigo/><Referencia/><Mensaje/></TransferenciaResponse>`.
5. **Rechazos de negocio (51, 62):** se guardan con estado `RECHAZADA` y se devuelven como `422`
   con el `transferenciaId` para poder consultarlos.
6. **Resultado incierto (timeout o código desconocido del core):** se guarda como
   `PENDIENTE_CONFIRMACION`, no se reintenta contra el core y la respuesta queda guardada para
   *replay*. En producción se necesita un proceso de conciliación (fuera del alcance).
7. **Core no disponible (91 o conexión rechazada):** la transferencia no se procesó con certeza,
   así que **no** se guarda y la llave de idempotencia se libera para que la app pueda reintentar
   con la misma llave.
8. **Errores:** Problem Details (RFC 9457) usando el campo estándar `detail` para el texto
   legible, más las extensiones `codigo` y `correlationId`.
9. **Client ID y rate limiting:** los aplica el API Gateway (Kong); los errores `401` y `429` tienen
   el formato propio de Kong.
10. **Dos timeouts distintos hacia el core:**
    - *Conexión* (`CORE_TIMEOUT_CONEXION`, por defecto 2 s): si vence, o si la conexión es rechazada,
      la solicitud nunca llegó al core → `503 CORE_NO_DISPONIBLE` y la llave se libera.
    - *Lectura* (`CORE_TIMEOUT_LECTURA`, por defecto 15 s): si vence, el core recibió la solicitud y
      pudo procesarla → `504 CORE_TIMEOUT`, `PENDIENTE_CONFIRMACION` y la llave **no** se libera.
    - El de lectura debe ajustarse al p95 real del core de cada ambiente (en el incidente el p95 fue
      9.8 s contra un timeout de 5 s). La aplicación no arranca si el de conexión no es menor que el de lectura.
11. **Otras fallas con resultado incierto:** conexión cortada a mitad de la respuesta, status HTTP
    distinto de 2xx, cuerpo vacío o XML inválido → `502 RESPUESTA_CORE_INVALIDA` y `PENDIENTE_CONFIRMACION`.
    Ante la duda, se asume que el core pudo procesar la transferencia (nunca se arriesga un duplicado).
12. **Sin reintentos automáticos hacia el core:** el core no es idempotente.
13. **Autenticación con el core:** HTTP Basic con un usuario de servicio (`CORE_USUARIO` / `CORE_PASSWORD`),
    sobre HTTP/1.1 y sin seguir redirecciones.
14. **Glosa vacía:** si el concepto queda vacío tras quitar caracteres no ASCII (por ejemplo, solo
    emojis), se envía la glosa `TRANSFERENCIA`.
15. **Monto hacia el core:** siempre con 2 decimales (`100` → `100.00`).
16. **Decimales del monto:** se cuentan los decimales **significativos** (sin ceros a la derecha), porque
    en JSON `150.750` y `150.75` son el mismo número: `150.750` se acepta y se guarda como `150.75`;
    `150.755` se rechaza con 400. El contrato, la validación (`@MontoValido`) y las pruebas aplican la misma regla.
17. **Huella de la solicitud (idempotencia):** SHA-256 del *contenido* interpretado, no del texto JSON. El
    orden de los campos, los espacios o el offset de la fecha (si es el mismo instante) no la cambian.
18. **Llave atrapada:** una llave `EN_PROCESO` por más de *timeout de lectura + margen* (por defecto
    15 s + 30 s) se cierra como resultado incierto (`504 CORE_TIMEOUT`, `PENDIENTE_CONFIRMACION`). Nunca se
    libera automáticamente ni se vuelve a llamar al core.
19. **Error inesperado tras reservar la llave:** se responde 500 y la llave queda `EN_PROCESO`; pasado el
    umbral se cierra como incierta (conservador: nunca se libera algo que pudo llegar al core).
20. **Llaves vencidas:** pasadas 24 h (`IDEMPOTENCIA_EXPIRACION`) la llave se borra al reutilizarse y se
    trata como nueva. Una purga periódica de llaves vencidas queda fuera del alcance.
21. **Errores de Spring no listados en el contrato** (ruta inexistente, 405, 415): Problem Details con
    `codigo` `SOLICITUD_INVALIDA` y su status real.
22. **`Location`:** ruta relativa (`/transferencias/{id}`), válida según RFC 9110 y correcta detrás del gateway.
23. **Perfil sin ambiente:** sin `SPRING_PROFILES_ACTIVE` la aplicación arranca con la configuración común
    (útil en local). Los manifiestos de cada ambiente siempre fijan el perfil.
24. **Guardia de prod por palabras completas:** se busca `qa`, `dev`, etc. como palabra separada en la URL, no
    como subcadena, para no rechazar hosts legítimos como `devoluciones.izalco.com`. No reemplaza la
    validación de configuración del pipeline (Fase 7); es la última barrera dentro de la aplicación.
25. **Datos que se registran en logs:** id, estado, cuentas enmascaradas (últimos 4 dígitos), banco, monto
    y referencia del core. No se registra el concepto (texto libre, puede contener datos personales).
26. **Imagen:** `eclipse-temurin` con versión fija (`21.0.13_9-jre-noble`), usuario UID 10001. Las pruebas no
    corren dentro de `docker build` (necesitan Docker); el pipeline ejecuta `verify` antes de construirla.
27. **Un solo Dockerfile para local y pipeline:** en local compila solo (cómodo); en el pipeline recibe el jar
    probado con `--build-context`. Se descartó un segundo Dockerfile "solo CI" porque dos archivos que
    deben producir la misma imagen terminan divergiendo.
28. **El CI no publica la imagen:** la construye y la prueba en cada push/PR, pero solo el workflow de
    release (Fase 7, al crear un tag `vX.Y.Z`) la publica en GHCR. Así el registro solo contiene versiones
    de release, nunca imágenes de ramas.
29. **Pruebas mínimas:** el paso de evidencia exige más de 0 pruebas, no un número fijo. Un mínimo fijo
    obligaría a cambiar el workflow cada vez que se agregan pruebas; borrar pruebas en masa ya lo detecta
    el gate de cobertura.
