# Incident service — Spring Boot 3.5 on Java 17.
#
# Multi-stage: the build stage keeps Maven and the full dependency tree out of the runtime image.
#
# NOTE: tests are deliberately SKIPPED here. Tier 2 needs a Testcontainers Oracle, which means a
# Docker daemon inside the build — run `./mvnw verify` on the host (or in CI) instead. This image
# builds the artifact; it does not certify it.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies first: this layer is cached until pom.xml itself changes, so an edit to a .java
# file does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root. The service opens no privileged port and writes no files.
# A NUMERIC uid: Kubernetes `runAsNonRoot` cannot verify a named user, and refuses to start it.
RUN groupadd --system --gid 10001 incident && useradd --system --uid 10001 --gid incident --no-create-home incident

COPY --from=build /build/target/incident-service-0.0.1.jar app.jar
RUN chown incident:incident /app/app.jar
USER 10001:10001

EXPOSE 8080

# Asia/Jakarta everywhere. Redmine's datetimes are local wall-clock with no offset and the mirror
# copies them verbatim, so a container on UTC shifts every TRUNC day boundary by 7 hours and moves
# AGING_* by a day for roughly a third of rows. This is one of the three places the timezone has to
# be pinned — the others are the compose file and the Testcontainers container.
ENV TZ=Asia/Jakarta
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Jakarta -XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
