#!/bin/bash
# One-command deployment for Bible Search
# Local-First Semantic Bible Search with Two-Stage Retrieval

set -e

echo "========================================"
echo "Bible Search"
echo "Cloud Run Deployment"
echo "========================================"
echo ""

# Environment variables are loaded from .gcp-config file
if [ -f ".gcp-config" ]; then
    echo "Loading configuration from .gcp-config..."
    source .gcp-config
    echo "✓ Configuration loaded"
    echo ""
else
    echo "❌ Error: .gcp-config file not found!"
    echo "   Create a .gcp-config file with the following variables:"
    echo "     PROJECT_ID='your-gcp-project-id'"
    echo "     SERVICE_NAME='bible-search'"
    echo "     REGION='us-central1'"
    echo ""
    echo "   Or run the setup script first:"
    echo "     ./1-setup-gcp-project.sh"
    exit 1
fi

IMAGE_NAME="gcr.io/${PROJECT_ID}/${SERVICE_NAME}"

# Check prerequisites
echo "Checking prerequisites..."

# 1. Check if pom.xml exists (Maven project)
if [ ! -f "pom.xml" ]; then
    echo "❌ Error: pom.xml not found. Are you in the project root?"
    exit 1
fi

# 2. Check if ONNX model exists
if [ ! -f "src/main/resources/models/multilingual-minilm/model.onnx" ]; then
    echo "❌ Error: ONNX model not found!"
    echo "   Download the model first:"
    echo ""
    echo "   mkdir -p src/main/resources/models/multilingual-minilm"
    echo "   curl -L -o src/main/resources/models/multilingual-minilm/model.onnx \\"
    echo "     'https://huggingface.co/nicekchester/paraphrase-multilingual-MiniLM-L12-v2-onnx/resolve/main/model_quantized.onnx'"
    echo ""
    exit 1
fi

# 3. Check if tokenizer exists
if [ ! -f "src/main/resources/models/multilingual-minilm/tokenizer.json" ]; then
    echo "❌ Error: Tokenizer not found!"
    echo "   Download the tokenizer first:"
    echo ""
    echo "   curl -L -o src/main/resources/models/multilingual-minilm/tokenizer.json \\"
    echo "     'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json'"
    echo ""
    exit 1
fi

# 4. Check if Bible data exists
if [ ! -f "src/main/resources/bible/bible_krv.json" ] || [ ! -f "src/main/resources/bible/bible_asv.json" ]; then
    echo "❌ Error: Bible data files not found!"
    echo "   Expected files:"
    echo "     src/main/resources/bible/bible_krv.json"
    echo "     src/main/resources/bible/bible_asv.json"
    exit 1
fi

# 5. Check if gcloud is installed
if ! command -v gcloud &> /dev/null; then
    echo "❌ Error: gcloud CLI not installed"
    echo "   Install: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# 6. Check if Docker is running
if ! docker info &> /dev/null; then
    echo "❌ Error: Docker is not running"
    echo "   Please start Docker Desktop or Docker daemon"
    exit 1
fi

echo "✓ All prerequisites met"
echo ""

# Set project
echo "Setting GCP project..."
gcloud config set project ${PROJECT_ID}

# Enable required APIs (first time only)
echo "Enabling Cloud Run API..."
gcloud services enable run.googleapis.com --quiet 2>/dev/null || true
gcloud services enable containerregistry.googleapis.com --quiet 2>/dev/null || true

echo "✓ APIs enabled"
echo ""

# Build Docker image
echo "Building Docker image (this may take 3-5 minutes on first build)..."
docker build --platform linux/amd64 -f Dockerfile.cloudrun -t ${IMAGE_NAME} .

echo "✓ Image built"
echo ""

# Configure Docker for GCR
echo "Configuring Docker authentication..."
gcloud auth configure-docker --quiet

# Push to Google Container Registry
echo "Pushing image to GCR (this may take 2-5 minutes)..."
docker push ${IMAGE_NAME}

echo "✓ Image pushed"
echo ""

# Deploy to Cloud Run
echo "Deploying to Cloud Run..."

gcloud run deploy ${SERVICE_NAME} \
  --image ${IMAGE_NAME} \
  --platform managed \
  --region ${REGION} \
  --allow-unauthenticated \
  --memory 2Gi \
  --cpu 2 \
  --max-instances 10 \
  --min-instances 0 \
  --timeout 300 \
  --set-env-vars="JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

echo "✓ Deployed!"
echo ""

# Get service URL
SERVICE_URL=$(gcloud run services describe ${SERVICE_NAME} \
  --platform managed \
  --region ${REGION} \
  --format 'value(status.url)')

echo "========================================"
echo "🎉 Deployment Complete!"
echo "========================================"
echo ""
echo "Service URL: ${SERVICE_URL}"
echo ""
echo "Test your endpoints:"
echo ""
echo "1. Open Web UI:"
echo "   ${SERVICE_URL}"
echo ""
echo "2. Health Check (Actuator):"
echo "   curl ${SERVICE_URL}/actuator/health"
echo ""
echo "3. Search API (POST):"
echo "   curl -X POST ${SERVICE_URL}/api/search \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"query\": \"love your neighbor\", \"maxResults\": 5}'"
echo ""
echo "4. Search API (GET):"
echo "   curl '${SERVICE_URL}/api/search?q=eternal+life&max=5'"
echo ""
echo "5. Stats:"
echo "   curl ${SERVICE_URL}/api/search/stats"
echo ""
echo "========================================"
echo "Monitoring & Logs:"
echo "========================================"
echo ""
echo "View logs:"
echo "  gcloud run logs read ${SERVICE_NAME} --region ${REGION}"
echo ""
echo "Stream logs:"
echo "  gcloud run logs tail ${SERVICE_NAME} --region ${REGION}"
echo ""
echo "View in console:"
echo "  https://console.cloud.google.com/run/detail/${REGION}/${SERVICE_NAME}"
echo ""
