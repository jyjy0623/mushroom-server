#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Building mushroom-server Docker image...${NC}"

cd /root/workspace/mushroom-server

# Build the JAR file using gradle
echo -e "${YELLOW}Compiling JAR...${NC}"
./gradlew clean build -x test --no-daemon

# Build the Docker image
echo -e "${YELLOW}Building Docker image...${NC}"
docker build -t mushroom-server:latest -f Dockerfile .

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Docker image built successfully!${NC}"
    echo ""
    echo "Image name: mushroom-server:latest"
    echo "Build completed at: $(date)"
    echo ""
    echo "Next steps:"
    echo "1. Configure application.yaml with your database credentials"
    echo "2. Run: bash docker-run.sh"
else
    echo -e "${RED}✗ Docker build failed!${NC}"
    exit 1
fi
