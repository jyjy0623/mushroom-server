# 自动化部署配置指南

## 概述

mushroom-server 项目已配置 GitHub Actions 自动化部署流程：
- **CI 工作流** (ci.yml): 编译和测试
- **CD 工作流** (cd.yml): 构建镜像和自动部署

## 部署流程

```
Push to master
    ↓
CI: 编译 + 测试
    ↓
CD: 构建 Docker 镜像
    ↓
CD: 部署到服务器
    ↓
CD: 验证部署
```

## 配置步骤

### 1. 设置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

| Secret 名称 | 说明 | 示例 |
|-----------|------|------|
| `DEPLOY_HOST` | 部署服务器地址 | `your-server.com` |
| `DEPLOY_USER` | SSH 用户名 | `root` |
| `DEPLOY_KEY` | SSH 私钥 (完整内容) | `-----BEGIN RSA PRIVATE KEY-----...` |
| `DEPLOY_PORT` | SSH 端口 (可选) | `22` |
| `DB_PASSWORD` | 数据库密码 | `postgres_root_password_123` |

### 2. 生成 SSH 密钥对

```bash
# 在本地生成密钥对
ssh-keygen -t rsa -b 4096 -f deploy_key -N ""

# 查看公钥
cat deploy_key.pub

# 查看私钥（用于 GitHub Secret）
cat deploy_key
```

### 3. 配置服务器

在部署服务器上：

```bash
# 1. 创建部署用户（如果需要）
sudo useradd -m -s /bin/bash deploy

# 2. 添加公钥到 authorized_keys
mkdir -p ~/.ssh
cat deploy_key.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# 3. 安装 Docker（如果未安装）
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# 4. 添加用户到 docker 组
sudo usermod -aG docker $USER
newgrp docker
```

### 4. 测试 SSH 连接

```bash
ssh -i deploy_key -p 22 deploy@your-server.com "docker ps"
```

## 工作流详解

### CI 工作流 (ci.yml)

**触发条件**:
- Push 到 master 分支
- Pull Request 到 master 分支

**步骤**:
1. 检出代码
2. 设置 JDK 17
3. 缓存 Gradle 依赖
4. 编译项目
5. 运行测试
6. 上传测试报告

### CD 工作流 (cd.yml)

**触发条件**:
- Push 到 master 分支
- 创建 tag (v*)

**步骤**:

#### 构建阶段 (build-and-push)
1. 检出代码
2. 编译 JAR
3. 运行测试
4. 构建 Docker 镜像
5. 上传镜像制品

#### 部署阶段 (deploy)
1. 下载 Docker 镜像
2. 加载镜像
3. SSH 连接到服务器
4. 停止旧容器
5. 启动新容器
6. 等待容器健康
7. 验证部署

## 部署流程详解

### 自动部署脚本

```bash
# 1. 停止旧容器
docker stop mushroom-server || true
docker rm mushroom-server || true

# 2. 启动新容器
docker run -d \
  --name mushroom-server \
  -p 8080:8080 \
  -e DB_URL="jdbc:postgresql://172.17.0.2:5432/mushroom" \
  -e DB_USER="postgres" \
  -e DB_PASSWORD="${DB_PASSWORD}" \
  --health-cmd='curl -f http://localhost:8080/health || exit 1' \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  mushroom-server:${COMMIT_SHA}

# 3. 等待容器健康（最多 60 秒）
for i in {1..30}; do
  if docker inspect mushroom-server --format='{{.State.Health.Status}}' | grep -q healthy; then
    echo "Container is healthy!"
    exit 0
  fi
  sleep 2
done
```

### 健康检查

容器启动后会执行健康检查：
- 间隔: 30 秒
- 超时: 5 秒
- 重试: 3 次
- 命令: `curl -f http://localhost:8080/health`

## 监控和日志

### 查看 GitHub Actions 日志

1. 进入 GitHub 仓库
2. 点击 "Actions" 标签
3. 选择工作流运行
4. 查看详细日志

### 查看服务器日志

```bash
# 实时日志
docker logs -f mushroom-server

# 最后 100 行
docker logs --tail 100 mushroom-server

# 健康检查日志
docker inspect mushroom-server --format='{{json .State.Health}}' | jq .
```

## 故障排查

### 部署失败

**检查清单**:
1. ✅ SSH 密钥配置正确
2. ✅ 服务器地址和端口正确
3. ✅ 数据库密码正确
4. ✅ Docker 已安装
5. ✅ 用户有 Docker 权限

**常见错误**:

| 错误 | 原因 | 解决 |
|------|------|------|
| `Permission denied` | SSH 密钥权限问题 | 检查 `~/.ssh/authorized_keys` |
| `docker: command not found` | Docker 未安装 | 安装 Docker |
| `Container unhealthy` | 应用启动失败 | 检查 `docker logs` |
| `Port already in use` | 端口被占用 | 停止旧容器或更改端口 |

### 测试部署

```bash
# 手动测试部署流程
ssh -i deploy_key deploy@your-server.com << 'EOF'
docker stop mushroom-server || true
docker rm mushroom-server || true
docker run -d --name mushroom-server -p 8080:8080 mushroom-server:latest
sleep 5
curl http://localhost:8080/health
EOF
```

## 回滚部署

如果新版本有问题，可以快速回滚：

```bash
# 查看历史镜像
docker images | grep mushroom-server

# 启动旧版本
docker stop mushroom-server
docker rm mushroom-server
docker run -d --name mushroom-server -p 8080:8080 mushroom-server:old-tag
```

## 最佳实践

1. **使用 Git Tags 标记版本**
   ```bash
   git tag -a v0.4.0 -m "Release v0.4.0"
   git push origin v0.4.0
   ```

2. **在 master 分支上工作**
   - 所有提交到 master 都会自动部署
   - 使用 Pull Request 进行代码审查

3. **监控部署**
   - 查看 GitHub Actions 日志
   - 监控服务器日志
   - 设置告警

4. **安全建议**
   - 定期轮换 SSH 密钥
   - 使用强密码
   - 限制 SSH 访问 IP
   - 启用 2FA

## 下一步

1. ✅ 配置 GitHub Secrets
2. ✅ 生成 SSH 密钥对
3. ✅ 配置服务器
4. ✅ 测试 SSH 连接
5. ✅ 推送代码触发部署

## 相关文件

- `.github/workflows/ci.yml` - CI 工作流
- `.github/workflows/cd.yml` - CD 工作流
- `Dockerfile` - Docker 镜像配置
- `docker-build.sh` - 本地构建脚本
- `compile.sh` - 编译脚本
