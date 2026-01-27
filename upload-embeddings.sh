#!/bin/bash
#
# Upload the SQLite embedding database to Google Cloud Storage.
# This enables faster Docker builds by downloading pre-built embeddings.
#
# Prerequisites:
#   - gcloud CLI installed and authenticated
#   - SQLite database built via ./build-embeddings.sh
#
# Usage:
#   ./upload-embeddings.sh                    # Upload to default bucket
#   ./upload-embeddings.sh --bucket my-bucket # Upload to custom bucket
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default values
GCS_BUCKET="bible-ai-485118-embeddings"
GCS_PATH="embeddings/bible-embeddings.db"
LOCAL_FILE="src/main/resources/embeddings/bible-embeddings.db"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --bucket)
            GCS_BUCKET="$2"
            shift 2
            ;;
        --file)
            LOCAL_FILE="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [--bucket <gcs-bucket>] [--file <local-file>]"
            echo ""
            echo "Options:"
            echo "  --bucket <name>  GCS bucket name (default: $GCS_BUCKET)"
            echo "  --file <path>    Local SQLite file path (default: $LOCAL_FILE)"
            echo ""
            echo "Prerequisites:"
            echo "  1. Run ./build-embeddings.sh first to generate the database"
            echo "  2. Authenticate with: gcloud auth login"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

echo "╔════════════════════════════════════════════════════════════╗"
echo "║         Upload Embeddings to Google Cloud Storage          ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check if local file exists
if [ ! -f "$LOCAL_FILE" ]; then
    echo "ERROR: SQLite database not found: $LOCAL_FILE"
    echo ""
    echo "Please run ./build-embeddings.sh first to generate the database."
    exit 1
fi

# Check if gsutil is available
if ! command -v gsutil &> /dev/null; then
    echo "ERROR: gsutil not found. Please install Google Cloud SDK."
    echo "       https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# Get file size
FILE_SIZE=$(du -h "$LOCAL_FILE" | cut -f1)
echo "Local file: $LOCAL_FILE ($FILE_SIZE)"
echo "Destination: gs://$GCS_BUCKET/$GCS_PATH"
echo ""

# Upload to GCS
echo "Uploading..."
gsutil -h "Cache-Control:public, max-age=3600" cp "$LOCAL_FILE" "gs://$GCS_BUCKET/$GCS_PATH"

# Verify upload
echo ""
echo "Verifying upload..."
REMOTE_SIZE=$(gsutil ls -l "gs://$GCS_BUCKET/$GCS_PATH" | head -1 | awk '{print $1}')
LOCAL_SIZE=$(stat -f%z "$LOCAL_FILE" 2>/dev/null || stat -c%s "$LOCAL_FILE")

if [ "$REMOTE_SIZE" = "$LOCAL_SIZE" ]; then
    echo "✓ Upload successful!"
    echo ""
    echo "GCS location: gs://$GCS_BUCKET/$GCS_PATH"
    echo ""
    echo "Next steps:"
    echo "  - Cloud Build will download this during docker build"
    echo "  - Run: gcloud builds submit --config=cloudbuild.yaml"
else
    echo "WARNING: File sizes don't match (local: $LOCAL_SIZE, remote: $REMOTE_SIZE)"
    echo "         Upload may have been incomplete."
    exit 1
fi
