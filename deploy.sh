#!/bin/bash
set -e

# 使用 --network host 模式的部署脚本
# 这是之前验证成功的部署方式

CONTAINER_NAME="mushroom-server"
IMAGE_NAME="mushroom-server:v0.5.1"
PORT=8080
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="mushroom"
DB_USER="mushroom"
DB_PASSWORD="mushroom_password_123"

echo "🍄 Mushroom Server v0.5.1 - Docker Deployment"
echo "================================================"
echo ""

# 步骤1：检查 PostgreSQL 是否运行
echo "Step 1: Checking PostgreSQL..."
if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" > /dev/null 2>&1; then
    echo "❌ PostgreSQL is not accessible at $DB_HOST:$DB_PORT"
    exit 1
fi
echo "✅ PostgreSQL is accessible"
echo ""

# 步骤2：重新编译项目
echo "Step 2: Building project..."
./gradlew build -x test 2>&1 | tail -5
echo "✅ Project built successfully"
echo ""

# 步骤3：构建 Docker 镜像
echo "Step 3: Building Docker image..."
docker build -t "$IMAGE_NAME" -f Dockerfile . 2>&1 | tail -10
echo "✅ Docker image built: $IMAGE_NAME"
echo ""

# 步骤4：停止并删除旧容器
echo "Step 4: Cleaning up old container..."
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
    echo "✅ Old container removed"
fi
echo ""

# 步骤5：启动容器 (使用 --network host)
echo "Step 5: Starting Docker container with --network host..."
docker run -d \
    --name "$CONTAINER_NAME" \
    -p ${PORT}:${PORT} \
    --network host \
    -e POSTGRES_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
    -e POSTGRES_USER="$DB_USER" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    -e TZ="UTC" \
    --restart unless-stopped \
    "$IMAGE_NAME"

if [ $? -eq 0 ]; then
    echo "✅ Container started successfully!"
else
    echo "❌ Failed to start container"
    exit 1
fi
echo ""

# 步骤6：等待应用启动
echo "Step 6: Waiting for application to start..."
sleep 3

# 步骤7：检查容器是否仍在运行
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "❌ Container exited unexpectedly"
    echo "Container logs:"
    docker logs "$CONTAINER_NAME" | tail -50
    exit 1
fi
echo "✅ Container is running"
echo ""

# 步骤8：检查应用健康状态
echo "Step 7: Checking application health..."
for i in {1..15}; do
    if curl -s http://localhost:${PORT}/health > /dev/null 2>&1; then
        echo "✅ Application is healthy!"
        break
    fi
    if [ $i -eq 15 ]; then
        echo "⚠️  Health check timeout - checking logs..."
        docker logs "$CONTAINER_NAME" | tail -30
    else
        echo -n "."
        sleep 1
    fi
done
echo ""

# 完成
echo "================================================"
echo "✅ Mushroom Server v0.5.1 deployed successfully!"
echo ""
echo "Service Details:"
echo "  Container Name: $CONTAINER_NAME"
echo "  Image: $IMAGE_NAME"
echo "  Port: $PORT"
echo "  Network: host"
echo ""
echo "Database Configuration:"
echo "  Host: $DB_HOST"
echo "  Port: $DB_PORT"
echo "  Database: $DB_NAME"
echo "  User: $DB_USER"
echo ""
echo "Useful Commands:"
echo "  View logs:        docker logs $CONTAINER_NAME -f"
echo "  Stop container:   docker stop $CONTAINER_NAME"
echo "  Start container:  docker start $CONTAINER_NAME"
echo "  Test API:         curl http://localhost:${PORT}/health"
echo ""
