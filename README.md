# mushroom-server

蘑菇大冒险（Mushroom Adventure）后端服务。

**技术栈**：Ktor 3.4.0 + PostgreSQL + Exposed ORM + JWT 认证

---

## 项目结构

```
src/main/kotlin/
├── Application.kt      — 入口，组装各模块
├── Databases.kt        — 数据库连接 + 表初始化
├── Routing.kt          — 路由定义
└── Serialization.kt    — JSON 序列化配置

src/main/resources/
├── application.yaml.example  — 配置文件模板（复制为 application.yaml 后填写真实值）
└── logback.xml               — 日志配置
```

---

## 本地开发

### 环境要求

- JDK 17+（推荐使用 Android Studio 自带的 JBR：`D:/tools/Android/Android Studio/jbr`）
- PostgreSQL（或使用 Docker 启动，见下文）

### 配置文件

```bash
cp src/main/resources/application.yaml.example src/main/resources/application.yaml
```

编辑 `application.yaml`，填入真实数据库连接信息：

```yaml
ktor:
    application:
        modules:
            - com.mushroom.ApplicationKt.module
    deployment:
        port: 8080

postgres:
    url: "jdbc:postgresql://localhost:5432/mushroom"
    user: "mushroom"
    password: "your_password_here"
```

### 启动本地 PostgreSQL（Docker）

```bash
docker run -d \
  --name mushroom-postgres \
  -e POSTGRES_DB=mushroom \
  -e POSTGRES_USER=mushroom \
  -e POSTGRES_PASSWORD=yourpassword \
  -p 5432:5432 \
  postgres:16
```

### 编译与运行

```bash
# Windows（使用 Android Studio 自带 JDK）
set JAVA_HOME=D:\tools\Android\Android Studio\jbr

# 运行测试（使用 H2 内嵌数据库，无需真实 PostgreSQL）
./gradlew test

# 编译打包（生成 fat jar）
./gradlew build -x test

# 直接运行
./gradlew run
```

### 验证服务

```bash
# 健康检查
curl http://localhost:8080/health

# 写入测试数据
curl -X POST http://localhost:8080/ping \
  -H "Content-Type: application/json" \
  -d '{"message":"hello"}'

# 读取测试数据
curl http://localhost:8080/ping
```

---

## 生产部署（腾讯云轻量服务器）

### 服务器环境要求

- Ubuntu 20.04+
- Docker + Docker Compose
- JRE 17（运行 jar 用）
- 防火墙开放 8080 端口（或 80/443）

### 第一次部署

**1. 服务器安装 Java**

```bash
apt-get update && apt-get install -y openjdk-17-jre-headless
java -version
```

**2. 创建目录和配置文件**

```bash
mkdir -p ~/mushroom
cat > ~/mushroom/application.yaml << 'EOF'
ktor:
    application:
        modules:
            - com.mushroom.ApplicationKt.module
    deployment:
        port: 8080

postgres:
    url: "jdbc:postgresql://localhost:5432/mushroom"
    user: "mushroom"
    password: "替换成真实密码"
EOF
```

**3. 启动 PostgreSQL**

```bash
docker run -d \
  --name mushroom-postgres \
  --restart always \
  -e POSTGRES_DB=mushroom \
  -e POSTGRES_USER=mushroom \
  -e POSTGRES_PASSWORD=替换成真实密码 \
  -p 127.0.0.1:5432:5432 \
  postgres:16
```

**4. 从本地上传 jar（在 Windows 开发机执行）**

```bash
# 先编译
./gradlew build -x test

# 上传到服务器
scp build/libs/server-all.jar root@服务器IP:/root/mushroom/
```

**5. 启动服务**

```bash
cd ~/mushroom

# 后台运行，日志写到 app.log
nohup java -jar server-all.jar \
  -config=application.yaml \
  >> app.log 2>&1 &

echo $! > app.pid
echo "服务已启动，PID: $(cat app.pid)"
```

**6. 验证**

```bash
# 本地验证
curl http://localhost:8080/health

# 外网验证（在任意机器执行）
curl http://服务器IP:8080/health
```

---

### 日常更新部署

```bash
# 1. 本地编译（Windows 开发机）
./gradlew build -x test

# 2. 上传新 jar
scp build/libs/server-all.jar root@服务器IP:/root/mushroom/

# 3. 服务器重启服务
ssh root@服务器IP
kill $(cat ~/mushroom/app.pid)
cd ~/mushroom
nohup java -jar server-all.jar -config=application.yaml >> app.log 2>&1 &
echo $! > app.pid
```

---

### 查看日志

```bash
# 实时查看日志
tail -f ~/mushroom/app.log

# 查看最近 100 行
tail -n 100 ~/mushroom/app.log

# 查看错误
grep -i "error\|exception" ~/mushroom/app.log | tail -20
```

### 停止服务

```bash
kill $(cat ~/mushroom/app.pid)
```

---

## API 文档

### GET /health

健康检查，用于验证服务是否正常运行。

**响应**
```json
{"status": "ok", "version": "0.0.1"}
```

---

### POST /ping

写入一条测试记录到数据库，用于验证数据库读写是否正常。

**请求**
```json
{"message": "任意字符串"}
```

**响应** `201 Created`
```json
{"id": 1}
```

---

### GET /ping

读取最近 10 条测试记录（按写入时间倒序）。

**响应**
```json
[
  {"id": 2, "message": "hello", "createdAt": 1741234567890},
  {"id": 1, "message": "world", "createdAt": 1741234560000}
]
```

---

## 注意事项

- `application.yaml` 含数据库密码，已加入 `.gitignore`，**不会提交到 git**
- 测试使用 H2 内嵌数据库，**不需要启动真实 PostgreSQL**
- 生产环境 PostgreSQL 只绑定 `127.0.0.1`，不对外暴露
