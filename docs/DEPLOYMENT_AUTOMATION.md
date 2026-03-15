# 自动化部署配置指南

## 概述

v0.5.1 已实现完整的自动化部署流程：
- ✅ 镜像编译后自动部署
- ✅ 环境变量灵活注入
- ✅ 健康检查和服务验证
- ✅ 本地和远程部署支持

## 本地快速部署

### 前置条件
- Docker 和 Docker Compose 已安装
- PostgreSQL 容器已准备好
- `.env` 文件已配置

### 部署命令

```bash
# 完整部署（编译 + 构建 + 启动）
bash deploy-local.sh production latest

# 或指定开发环境
bash deploy-local.sh development latest
```

### 部署流程
1. 编译 JAR 文件
2. 构建 Docker 镜像
3. 停止旧容器
4. 启动新容器
5. 等待服务就绪
6. 验证健康状态

### 输出示例
```
🍄 Mushroom Server - Local Deployment
Environment: production
Image Tag: latest
================================================

Step 1: Building Docker image...
✅ Docker image built successfully

Step 2: Stopping old containers...
✅ Old containers stopped

Step 3: Starting new containers...
✅ Containers started

Step 4: Waiting for services to be ready...
Checking PostgreSQL... ✅
Checking application health... ✅

Step 5: Verifying deployment...
Health check response: {"status":"ok"}

================================================
✅ Deployment completed successfully!
================================================
```

## 远程自动化部署（CI/CD）

### 前置条件

#### 1. 配置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

| Secret 名称 | 说明 | 示例 |
|---|---|---|
| `DEPLOY_HOST` | 部署服务器 IP 或域名 | `203.0.113.42` |
| `DEPLOY_USER` | SSH 用户名 | `ubuntu` |
| `DEPLOY_KEY` | SSH 私钥（完整内容） | `-----BEGIN RSA PRIVATE KEY-----...` |
| `DEPLOY_PORT` | SSH 端口（可选，默认 22） | `22` |
| `DB_PASSWORD` | 数据库密码 | `mushroom_password_123` |
| `JWT_SECRET` | JWT 密钥 | `your_jwt_secret_key_...` |
| `UPLOAD_BASE_URL` | 上传文件基础 URL（可选） | `http://api.example.com` |

#### 2. 生成 SSH 密钥对

```bash
# 在本地生成密钥对
ssh-keygen -t rsa -b 4096 -f deploy_key -N ""

# 将公钥添加到服务器
ssh-copy-id -i deploy_key.pub ubuntu@203.0.113.42

# 复制私钥内容到 GitHub Secrets
cat deploy_key
```

#### 3. 配置服务器

在部署服务器上：

```bash
# 1. 安装 Docker 和 Docker Compose
sudo apt update
sudo apt install -y docker.io docker-compose

# 2. 添加用户到 docker 组
sudo usermod -aG docker ubuntu

# 3. 克隆项目
git clone <repository-url> /root/workspace/mushroom-server
cd /root/workspace/mushroom-server

# 4. 创建 .env 文件（会由 CI/CD 自动更新）
cat > .env << 'EOF'
POSTGRES_URL=jdbc:postgresql://mushroom-postgres:5432/mushroom
POSTGRES_USER=mushroom
POSTGRES_PASSWORD=mushroom_password_123
JWT_SECRET=your_jwt_secret_key_change_this_in_production
UPLOAD_BASE_URL=http://localhost:8080
EOF
```

### 部署流程

#### 自动触发条件
- 推送到 `master` 分支
- 或创建 `v*` 标签

#### 自动化步骤

1. **编译阶段**（GitHub Actions）
   - 检出代码
   - 设置 JDK 17
   - 编译 JAR 文件
   - 运行测试
   - 构建 Docker 镜像
   - 上传镜像到 Artifacts

2. **部署阶段**（远程服务器）
   - 下载镜像
   - 加载镜像到 Docker
   - 更新 `.env` 文件
   - 停止旧容器
   - 启动新容器
   - 等待服务就绪
   - 验证健康状态

### 监控部署

#### 查看 GitHub Actions 日志
1. 进入 GitHub 仓库
2. 点击 "Actions" 标签
3. 选择最新的工作流运行
4. 查看 "build-and-push" 和 "deploy" 任务的日志

#### 查看服务器日志

```bash
# SSH 连接到服务器
ssh -i deploy_key ubuntu@203.0.113.42

# 查看应用日志
docker logs mushroom-server -f

# 查看数据库日志
docker logs mushroom-postgres -f

# 查看容器状态
docker ps

# 查看 docker-compose 状态
cd /root/workspace/mushroom-server
docker-compose ps
```

## 故障排查

### 问题 1：部署失败 - SSH 连接错误

**症状**：`Permission denied (publickey)`

**解决**：
```bash
# 检查 SSH 密钥权限
chmod 600 ~/.ssh/deploy_key

# 测试 SSH 连接
ssh -i deploy_key -p 22 ubuntu@203.0.113.42 "echo OK"
```

### 问题 2：部署失败 - Docker 权限错误

**症状**：`permission denied while trying to connect to Docker daemon`

**解决**：
```bash
# 在服务器上添加用户到 docker 组
sudo usermod -aG docker ubuntu

# 重新登录或运行
newgrp docker
```

### 问题 3：应用启动失败 - 数据库连接错误

**症状**：`Connection refused` 或 `Connect timed out`

**解决**：
```bash
# 检查 .env 文件
cat /root/workspace/mushroom-server/.env

# 检查 PostgreSQL 容器
docker logs mushroom-postgres

# 检查网络连接
docker exec mushroom-server nc -zv mushroom-postgres 5432
```

### 问题 4：应用启动失败 - 环境变量未注入

**症状**：应用日志显示硬编码的 IP 地址

**解决**：
```bash
# 检查容器环境变量
docker exec mushroom-server env | grep POSTGRES

# 检查 docker-compose.yml 中的 env_file 配置
cat docker-compose.yml | grep -A 5 "env_file"
```

## 回滚部署

### 本地回滚

```bash
# 查看容器历史
docker ps -a

# 停止当前容器
docker-compose -f docker-compose.yml down

# 启动之前的容器
docker run -d --name mushroom-server -p 8080:8080 mushroom-server:<previous-tag>
```

### 远程回滚

```bash
# SSH 连接到服务器
ssh -i deploy_key ubuntu@203.0.113.42

# 查看镜像历史
docker images mushroom-server

# 停止当前容器
cd /root/workspace/mushroom-server
docker-compose -f docker-compose.yml down

# 修改 docker-compose.yml 中的镜像标签
# 然后重新启动
docker-compose -f docker-compose.yml up -d
```

## 最佳实践

### 1. 版本管理
```bash
# 创建版本标签
git tag -a v0.5.2 -m "Release v0.5.2"
git push origin v0.5.2

# 自动触发部署
```

### 2. 环境隔离
```bash
# 开发环境
bash deploy-local.sh development latest

# 生产环境
bash deploy-local.sh production latest
```

### 3. 监控和告警
```bash
# 定期检查应用健康状态
curl http://localhost:8080/health

# 查看容器资源使用
docker stats mushroom-server
```

### 4. 日志管理
```bash
# 查看最近 100 行日志
docker logs mushroom-server --tail 100

# 实时查看日志
docker logs mushroom-server -f

# 导出日志到文件
docker logs mushroom-server > /tmp/mushroom-server.log 2>&1
```

## 总结

| 场景 | 命令 | 说明 |
|---|---|---|
| 本地快速部署 | `bash deploy-local.sh production latest` | 编译 + 构建 + 启动 |
| 远程自动部署 | `git push origin master` | 自动触发 CI/CD |
| 查看日志 | `docker logs mushroom-server -f` | 实时监控 |
| 停止服务 | `docker-compose down` | 停止所有容器 |
| 重启服务 | `docker-compose restart` | 重启容器 |

现在，镜像编译后会自动部署，无需手动干预！🚀
