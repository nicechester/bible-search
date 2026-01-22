# Dockerfile for Google Cloud Run deployment
# Bible Search - Local-First Semantic Bible Search
# Multi-stage build for optimized image size

# =============================================================================
# Stage 1: Build the application with Maven
# =============================================================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy pom.xml first to leverage Docker layer caching for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# =============================================================================
# Stage 2: Create lightweight runtime image
# =============================================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root user for security
RUN groupadd --system --gid 1001 appgroup && \
    useradd --system --uid 1001 --gid appgroup appuser

# Copy the built JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port (Cloud Run uses PORT environment variable, default 8080)
EXPOSE 8080

# JVM options optimized for containers and Cloud Run
# - Uses container-aware memory settings
# - Optimized for startup time with CDS (Class Data Sharing)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+TieredCompilation \
    -XX:TieredStopAtLevel=1 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.backgroundpreinitializer.ignore=true"

# Run the application
# Cloud Run sets PORT environment variable (default 8080)
# Note: This app requires ~2GB memory and 2 CPU for embedding generation at startup
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
