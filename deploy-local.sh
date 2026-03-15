#!/bin/bash
set -e

# 本地快速部署脚本 - 使用 docker-compose
# 用法: bash deploy-local.sh [环境] [镜像标签]
# 示例: bash deploy-local.sh production latest
#      bash deploy-local.sh development latest

ENVIRONMENT="${1:-production}"
IMAGE_TAG="${2:-latest}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}🍄 Mushroom Server - Local Deployment${NC}"
echo "Environment: $ENVIRONMENT"
echo "Image Tag: $IMAGE_TAG"
echo "================================================"
echo ""

# 验证 docker-compose 文件
if [ ! -f "docker-compose.yml" ]; then
    echo -e "${RED}❌ docker-compose.yml not found${NC}"
    exit 1
fi

# 验证 .env 文件
if [ ! -f ".env" ]; then
    echo -e "${RED}❌ .env file not found${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 1: Building Docker image...${NC}"
bash docker-build.sh
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Docker build failed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Docker image built successfully${NC}"
echo ""

echo -e "${YELLOW}Step 2: Stopping old containers...${NC}"
docker-compose -f docker-compose.yml down || true
echo -e "${GREEN}✅ Old containers stopped${NC}"
echo ""

echo -e "${YELLOW}Step 3: Starting new containers...${NC}"
docker-compose -f docker-compose.yml up -d
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Failed to start containers${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Containers started${NC}"
echo ""

echo -e "${YELLOW}Step 4: Waiting for services to be ready...${NC}"
sleep 5

# 检查 PostgreSQL
echo -n "Checking PostgreSQL..."
for i in {1..30}; do
    if docker exec mushroom-postgres pg_isready -U mushroom > /dev/null 2>&1; then
        echo -e " ${GREEN}✅${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e " ${RED}❌${NC}"
        echo -e "${RED}PostgreSQL failed to start${NC}"
        docker logs mushroom-postgres | tail -20
        exit 1
    fi
    echo -n "."
    sleep 1
done

# 检查应用健康状态
echo -n "Checking application health..."
for i in {1..30}; do
    if curl -s http://localhost:8080/health > /dev/null 2>&1; then
        echo -e " ${GREEN}✅${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e " ${RED}❌${NC}"
        echo -e "${RED}Application failed to start${NC}"
        docker logs mushroom-server | tail -50
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""

echo -e "${YELLOW}Step 5: Verifying deployment...${NC}"
HEALTH=$(curl -s http://localhost:8080/health)
echo "Health check response: $HEALTH"
echo ""

echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN}✅ Deployment completed successfully!${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo "Service Details:"
echo "  Application: http://localhost:8080"
echo "  Database: localhost:5432"
echo "  Environment: $ENVIRONMENT"
echo ""
echo "Useful Commands:"
echo "  View logs:        docker logs mushroom-server -f"
echo "  Stop services:    docker-compose -f docker-compose.yml down"
echo "  Restart services: docker-compose -f docker-compose.yml restart"
echo "  Test API:         curl http://localhost:8080/health"
echo ""
