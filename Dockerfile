# Dockerfile for Google Cloud Run deployment
# Bible Search - Local-First Semantic Bible Search
# Multi-stage build for optimized image size

# =============================================================================
# Stage 1: Build the application with Maven
# =============================================================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Install Maven and curl
RUN apt-get update && apt-get install -y maven curl && rm -rf /var/lib/apt/lists/*

# Copy pom.xml first to leverage Docker layer caching for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Download ONNX model if not present (it's in .gitignore so won't be in repo)
# Using the official sentence-transformers model from HuggingFace
RUN mkdir -p src/main/resources/models/multilingual-minilm && \
    if [ ! -f src/main/resources/models/multilingual-minilm/model.onnx ] || [ $(stat -c%s src/main/resources/models/multilingual-minilm/model.onnx 2>/dev/null || echo 0) -lt 1000000 ]; then \
      echo "Downloading ONNX model from HuggingFace..." && \
      curl -fSL --retry 3 --retry-delay 5 -o src/main/resources/models/multilingual-minilm/model.onnx \
        'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model.onnx' && \
      MODEL_SIZE=$(stat -c%s src/main/resources/models/multilingual-minilm/model.onnx) && \
      echo "Model downloaded: ${MODEL_SIZE} bytes" && \
      if [ "$MODEL_SIZE" -lt 10000000 ]; then \
        echo "ERROR: Model file too small, download may have failed" && \
        cat src/main/resources/models/multilingual-minilm/model.onnx && \
        exit 1; \
      fi; \
    else \
      echo "ONNX model already exists"; \
    fi

# Download tokenizer files if not present
RUN if [ ! -f src/main/resources/models/multilingual-minilm/tokenizer.json ]; then \
      echo "Downloading tokenizer files..." && \
      curl -fSL --retry 3 -o src/main/resources/models/multilingual-minilm/tokenizer.json \
        'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json' && \
      curl -fSL --retry 3 -o src/main/resources/models/multilingual-minilm/tokenizer_config.json \
        'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer_config.json' && \
      curl -fSL --retry 3 -o src/main/resources/models/multilingual-minilm/special_tokens_map.json \
        'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/special_tokens_map.json' && \
      echo "Tokenizer files downloaded successfully"; \
    else \
      echo "Tokenizer already exists"; \
    fi

# Copy pre-built SQLite embedding database (downloaded by Cloud Build from GCS)
# This is much faster than generating embeddings during build (~10-20s vs ~3-5 min)
# To update embeddings: ./build-embeddings.sh && ./upload-embeddings.sh
COPY src/main/resources/embeddings/bible-embeddings.db src/main/resources/embeddings/

# Build the application (includes embedding database in JAR)
RUN mvn package -DskipTests -B

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

# Enable SQLite embedding store (pre-built in image for instant cold start)
ENV EMBEDDING_SQLITE_ENABLED=true
ENV EMBEDDING_SQLITE_PATH=classpath:embeddings/bible-embeddings.db

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
# Note: With SQLite embeddings, cold start is ~5-10 seconds (vs 2+ minutes without)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
