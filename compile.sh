#!/bin/bash
set -e

# 颜色定义
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}正在编译 Mushroom Server v0.2.0...${NC}"
echo ""

# 修复 gradle.properties
echo -e "${YELLOW}1. 修复 gradle 配置...${NC}"
cat > gradle.properties << 'EOF'
kotlin.code.style=official
org.gradle.daemon=false
org.gradle.parallel=true
EOF
echo -e "${GREEN}✓ Gradle 配置已修复${NC}"

# 尝试编译，最多重试 3 次
RETRY_COUNT=0
MAX_RETRIES=3

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    echo ""
    echo -e "${YELLOW}2. 编译项目 (尝试 $((RETRY_COUNT + 1))/$MAX_RETRIES)...${NC}"

    if chmod +x gradlew && ./gradlew build -x test --no-daemon; then
        echo -e "${GREEN}✓ 编译成功${NC}"
        break
    else
        RETRY_COUNT=$((RETRY_COUNT + 1))
        if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
            echo -e "${YELLOW}编译失败，等待 10 秒后重试...${NC}"
            sleep 10
        else
            echo -e "${RED}✗ 编译失败 (已重试 $MAX_RETRIES 次)${NC}"
            echo ""
            echo "可能的原因:"
            echo "1. 网络连接问题 (Gradle 需要下载依赖)"
            echo "2. DNS 问题"
            echo "3. 防火墙限制"
            echo ""
            echo "建议:"
            echo "  - 检查网络连接"
            echo "  - 等待几分钟后重试"
            echo "  - 使用代理配置"
            exit 1
        fi
    fi
done

echo ""
echo -e "${GREEN}✓ Mushroom Server v0.2.0 编译完成${NC}"
echo ""
echo "下一步:"
echo "  bash docker-build.sh"
