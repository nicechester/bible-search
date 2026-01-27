#!/bin/bash
#
# Build the SQLite embedding database for Bible Search.
# This script generates embeddings for all Bible verses and stores them in SQLite.
#
# Usage:
#   ./build-embeddings.sh                    # Build to default location
#   ./build-embeddings.sh --output /path/to/db  # Custom output path
#
# The generated database can be:
# - Included in the Docker image for instant cold starts
# - Used locally for development
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default output path
OUTPUT_PATH="${1:-src/main/resources/embeddings/bible-embeddings.db}"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --output)
            OUTPUT_PATH="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [--output <path>]"
            echo ""
            echo "Options:"
            echo "  --output <path>  Output path for SQLite database"
            echo "                   Default: src/main/resources/embeddings/bible-embeddings.db"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

echo "╔════════════════════════════════════════════════════════════╗"
echo "║           Bible Embedding Database Builder                 ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Create output directory
mkdir -p "$(dirname "$OUTPUT_PATH")"

# Ensure the project is compiled
echo "Step 1/3: Compiling project..."
mvn compile -q -DskipTests

# Run the embedding builder
echo "Step 2/3: Generating embeddings (this takes ~3-5 minutes)..."
mvn exec:java \
    -Dexec.mainClass="io.github.nicechester.biblesearch.tool.EmbeddingDatabaseBuilder" \
    -Dexec.args="--output $OUTPUT_PATH" \
    -q

# Show result
if [ -f "$OUTPUT_PATH" ]; then
    SIZE=$(du -h "$OUTPUT_PATH" | cut -f1)
    echo ""
    echo "Step 3/3: Verifying..."
    echo "✓ Database created: $OUTPUT_PATH ($SIZE)"
    echo ""
    echo "To use this database:"
    echo "  1. Set EMBEDDING_SQLITE_ENABLED=true"
    echo "  2. Set EMBEDDING_SQLITE_PATH=classpath:embeddings/bible-embeddings.db"
    echo ""
    echo "Or for Docker builds, the database will be automatically included."
else
    echo "ERROR: Database not created!"
    exit 1
fi
