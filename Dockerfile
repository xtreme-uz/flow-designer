# Build stage — the Maven build also downloads Node and bundles the React app
# into the JAR, so one command covers both sides.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies first: they change far less often than the source, so this layer
# survives most rebuilds
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests

COPY src ./src
COPY frontend ./frontend
RUN mvn -B -q clean package -DskipTests

# Runtime stage — a JRE, not a JDK: the compiler is not needed to serve requests
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is here for the health probe below; the JRE image ships no HTTP client
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Git clones live here. Mount a volume: the workspaces hold work that is saved
# but not yet committed or pushed, and it is gone with the container otherwise.
ENV GIT_MAIN_REPO_PATH=/var/lib/flowdesigner/main \
    GIT_WORKSPACES_PATH=/var/lib/flowdesigner/workspaces

# An unprivileged user owns the data directory; nothing here needs root
RUN groupadd --system flowdesigner \
    && useradd --system --gid flowdesigner --home /app flowdesigner \
    && mkdir -p /var/lib/flowdesigner \
    && chown -R flowdesigner:flowdesigner /app /var/lib/flowdesigner

COPY --from=build --chown=flowdesigner:flowdesigner /build/target/flow-designer-*.jar /app/flow-designer.jar

USER flowdesigner
EXPOSE 8080
VOLUME ["/var/lib/flowdesigner"]

# start-period covers the clone of the flows repository on first boot
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# MaxRAMPercentage, not -Xmx: the heap follows the container's memory limit
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/flow-designer.jar"]
