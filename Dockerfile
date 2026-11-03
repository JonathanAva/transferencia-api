# syntax=docker/dockerfile:1
#
# Imagen de transferencia-api. Solo la última etapa llega a la imagen final.
#   1. compilacion: JDK + Maven, compila el jar.                 (solo en local)
#   2. jar:         el jar que va a la imagen.
#   3. capas:       separa el jar en capas (dependencias / aplicación) y verifica su SHA-256.
#   4. ejecucion:   solo JRE + las capas, usuario no root.
#
# Dos formas de construirla, con el MISMO Dockerfile:
#   Local:     docker build -t transferencia-api:local .
#              → la etapa "jar" toma el jar que compila la etapa "compilacion".
#   Pipeline:  docker build --build-context jar=artefacto --build-arg JAR_SHA256=<sha> .
#              → "--build-context jar=..." REEMPLAZA la etapa "jar" por la carpeta "artefacto", que contiene
#                el aplicacion.jar que ya pasó "mvnw verify". BuildKit solo construye las etapas que se usan,
#                así que "compilacion" ni se ejecuta: no se recompila nada (build once).
#
# Las versiones de las imágenes base están fijadas (no "latest") para que el build sea reproducible.

# ---------- 1. Compilación ----------
FROM eclipse-temurin:21.0.13_9-jdk-noble AS compilacion
WORKDIR /fuente

# Primero solo lo que define las dependencias: si pom.xml no cambia, Docker reutiliza esta capa
# (con las dependencias ya descargadas) y el build es mucho más rápido.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
# -DskipTests SOLO aquí, y esta etapa solo se usa en local: las pruebas necesitan Docker (Testcontainers)
# y no pueden correr dentro de "docker build". El pipeline no usa esta etapa: mete en la imagen el jar
# que ya pasó "mvnw verify" (pruebas + gate de cobertura).
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests

# ---------- 2. El jar que va a la imagen ----------
# En el pipeline esta etapa se reemplaza con --build-context jar=<carpeta con aplicacion.jar>.
FROM scratch AS jar
COPY --from=compilacion /fuente/target/transferencia-api-*.jar /aplicacion.jar

# ---------- 3. Capas ----------
FROM eclipse-temurin:21.0.13_9-jre-noble AS capas
WORKDIR /capas
COPY --from=jar /aplicacion.jar aplicacion.jar
# Si el pipeline pasa el SHA-256 del jar probado, se exige que sea exactamente ese jar.
# Cualquier diferencia (otro jar, un jar recompilado, un archivo corrupto) detiene el build.
ARG JAR_SHA256=""
RUN if [ -n "$JAR_SHA256" ]; then echo "$JAR_SHA256  aplicacion.jar" | sha256sum --check --strict; fi
# Spring Boot separa el jar en capas que cambian con distinta frecuencia: las dependencias casi nunca,
# el código de la aplicación en cada versión. Así, al publicar una versión nueva solo viaja la capa pequeña.
RUN java -Djarmode=tools -jar aplicacion.jar extract --layers --launcher --destination extraido

# ---------- 4. Ejecución ----------
FROM eclipse-temurin:21.0.13_9-jre-noble AS ejecucion

# Trazabilidad (etiquetas OCI): de qué commit y de qué jar exacto salió esta imagen.
ARG JAR_SHA256=""
ARG VERSION="local"
ARG REVISION="local"
LABEL org.opencontainers.image.title="transferencia-api" \
      org.opencontainers.image.source="https://github.com/JonathanAva/transferencia-api" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      com.izalco.jar.sha256="${JAR_SHA256}"

# Usuario sin privilegios con UID numérico fijo: Kubernetes puede verificar "runAsNonRoot" con un número,
# no con un nombre. Si alguien explota la aplicación, no es root dentro del contenedor.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# De la capa que menos cambia a la que más (mejor aprovechamiento de la caché).
COPY --from=capas /capas/extraido/dependencies/ ./
COPY --from=capas /capas/extraido/spring-boot-loader/ ./
COPY --from=capas /capas/extraido/snapshot-dependencies/ ./
COPY --from=capas /capas/extraido/application/ ./

USER 10001:10001
EXPOSE 8080

# La JVM usa como máximo el 75 % de la memoria del contenedor (el resto: hilos, buffers, metaspace).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Docker marca el contenedor como "unhealthy" si /actuator/health no responde UP.
# start-period: tiempo de gracia mientras arranca Spring y Flyway migra la base.
# (En Kubernetes se usan las sondas liveness/readiness del manifiesto, no este HEALTHCHECK.)
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
