# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy all sources and build
COPY . .
RUN mvn clean install -DskipTests -B && \
    echo "=== BUILD COMPLETED ===" && \
    echo "All JAR files:" && \
    find . -name "*.jar" -type f && \
    echo "=== WEB TARGET ===" && \
    ls -la web/target/ && \
    echo "=== JAR MANIFEST CHECK ===" && \
    if [ -f web/target/graphhopper-web-*.jar ]; then \
        echo "JAR file found, checking manifest:" && \
        jar tf web/target/graphhopper-web-*.jar | grep -E "(MANIFEST|Main-Class)" || echo "No manifest found in JAR"; \
    else \
        echo "ERROR: No JAR file found in web/target/"; \
        exit 1; \
    fi

# Runtime image
FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

# Copy jar and config
COPY --from=builder /app/web/target/graphhopper-web-*.jar ./graphhopper.jar
COPY config-example.yml ./config.yml
COPY *.json ./

# Verify JAR file and check manifest
RUN ls -la /app/ && echo "JAR file size:" && ls -la graphhopper.jar && \
    echo "JAR manifest:" && jar tf graphhopper.jar | grep -E "(MANIFEST|Main-Class)" || echo "No manifest found"

# Create required directories and download OSM data
RUN mkdir -p /app/logs /app/turkey-graph-cache && \
    if [ ! -f /app/turkey-latest.osm.pbf ]; then \
        echo "Downloading Turkey OSM data..." && \
        wget -O /app/turkey-latest.osm.pbf https://download.geofabrik.de/europe/turkey-latest.osm.pbf && \
        echo "OSM data downloaded successfully" && \
        ls -la /app/turkey-latest.osm.pbf; \
    else \
        echo "OSM data already exists, skipping download"; \
    fi

EXPOSE 8989 8990

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8989/health || exit 1

CMD ["java", "-Xmx4g", "-Xms1g", "-jar", "graphhopper.jar", "server", "config.yml"]
