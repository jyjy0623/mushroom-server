# 📡 公网访问配置指南 - Mushroom Server

## 当前环境信息

```
内网 IP: 10.0.0.17
端口: 8080
当前仅支持内网访问
```

## 🌍 公网访问方案

### 方案 1: 通过公网 IP 直接访问 (推荐 - 最简单)

#### 前提条件
- 云服务器有公网 IP
- 云服务商控制台中的防火墙规则允许 8080 端口

#### 配置步骤

**1. 获取云服务器的公网 IP**
```bash
# 查看公网 IP
curl ifconfig.me
# 或
curl icanhazip.com
```

**2. 确认服务器防火墙设置**
```bash
# 检查应用是否在监听 8080
ss -tuln | grep 8080
# 或
netstat -tuln | grep 8080
```

应该显示:
```
tcp  0  0  0.0.0.0:8080  0.0.0.0:*  LISTEN
```

**3. 配置云服务商防火墙规则**

进入云服务商控制台 (阿里云/腾讯云/AWS/Azure 等):
- 找到安全组/防火墙设置
- 添加入站规则:
  - **协议**: TCP
  - **端口**: 8080
  - **源地址**: 0.0.0.0/0 (允许所有 IP)

**4. 手机上访问**
```
http://<公网IP>:8080/ping
例如: http://202.18.x.x:8080/ping
```

---

### 方案 2: 使用域名 + SSL (推荐 - 生产环境)

#### 步骤 1: 获取域名
- 购买域名 (如 godaddy.com, 阿里云域名等)
- 在 DNS 服务中添加 A 记录指向公网 IP

```
示例:
api.mushroom.com  A  202.18.x.x
```

#### 步骤 2: 配置 SSL 证书
```bash
# 使用 Let's Encrypt (免费)
sudo apt update
sudo apt install certbot

# 获取证书
sudo certbot certonly --standalone -d api.mushroom.com
```

#### 步骤 3: 更新 Ktor 配置

修改 `application.yaml`:
```yaml
ktor:
    application:
        modules:
            - com.mushroom.ApplicationKt.module
    deployment:
        port: 8080
        sslPort: 443
        ssl:
            keyStore: /path/to/keystore.jks
            keyStorePassword: "password"
            keyAlias: "tomcat"
            privateKeyPassword: "password"

postgres:
    url: "jdbc:postgresql://172.17.0.2:5432/mushroom"
    user: "mushroom"
    password: "postgres_root_password_123"
```

#### 步骤 4: 手机上访问
```
https://api.mushroom.com/ping
```

---

### 方案 3: 使用内网穿透工具 (简单但需额外成本)

#### 3.1 Ngrok (最流行)

**安装 Ngrok**:
```bash
# 下载
wget https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-stable-linux-amd64.zip
unzip ngrok-stable-linux-amd64.zip

# 注册账号并获取 authtoken
# 访问: https://dashboard.ngrok.com/

# 配置 authtoken
./ngrok authtoken your_token_here

# 启动穿透
./ngrok http 8080
```

输出示例:
```
ngrok by @inconshreveable

Session Status        online
Account               user@example.com
Version               3.0.0
Region                us (United States)
Forwarding            http://abc123def.ngrok.io -> http://localhost:8080
Forwarding            https://abc123def.ngrok.io -> http://localhost:8080
```

**手机访问**:
```
https://abc123def.ngrok.io/ping
```

#### 3.2 Frp (内网穿透)

**服务器端配置** (`frps.ini`):
```ini
[common]
bind_addr = 0.0.0.0
bind_port = 7000
dashboard_port = 7500
dashboard_user = admin
dashboard_pwd = admin123
```

**客户端配置** (`frpc.ini`):
```ini
[common]
server_addr = <云服务器公网IP>
server_port = 7000

[mushroom-api]
type = http
local_ip = 127.0.0.1
local_port = 8080
custom_domains = api.mushroom.com
```

**启动**:
```bash
./frps -c frps.ini  # 服务器端
./frpc -c frpc.ini  # 客户端
```

---

### 方案 4: 使用反向代理 (Nginx/Caddy)

#### 使用 Nginx

**安装**:
```bash
sudo apt install nginx
```

**配置** (`/etc/nginx/sites-available/mushroom`):
```nginx
server {
    listen 80;
    server_name api.mushroom.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**启用**:
```bash
sudo ln -s /etc/nginx/sites-available/mushroom /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

#### 使用 Caddy (更简单)

```bash
# 安装
sudo apt install caddy

# 配置 (Caddyfile)
api.mushroom.com {
    reverse_proxy localhost:8080
}

# 启动
sudo systemctl restart caddy
```

---

## 🔒 安全建议 (重要!)

### 1. 启用认证

修改 `Application.kt`:
```kotlin
fun Application.configureRouting() {
    routing {
        // 添加认证中间件
        intercept(ApplicationCallPipeline.Plugins) {
            val apiKey = call.request.headers["X-API-Key"]
            if (apiKey != "your-secret-key") {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
            }
        }

        get("/ping") {
            // ... 代码
        }
    }
}
```

### 2. 启用 CORS (跨域)

修改 `Application.kt`:
```kotlin
fun Application.configureSerialization() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }
}
```

### 3. 启用 HTTPS

强制所有请求使用 HTTPS:
```kotlin
fun Application.configureRouting() {
    routing {
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.protocol != "https" &&
                environment.config.property("ktor.environment").getString() == "prod") {
                call.respond(HttpStatusCode.TemporaryRedirect) {
                    header("Location", "https://${call.request.host}${call.request.uri}")
                }
                finish()
            }
        }
    }
}
```

### 4. 限制请求率

```kotlin
install(RateLimit) {
    // 每秒最多 100 个请求
    register(object : RateLimitProvider {
        override suspend fun tryConsume(key: Any): Boolean {
            // 实现速率限制逻辑
            return true
        }
    })
}
```

---

## 📝 完整的公网部署清单

- [ ] 获取云服务器公网 IP
- [ ] 配置防火墙规则 (允许 8080 端口)
- [ ] 验证服务器监听 0.0.0.0:8080
- [ ] (可选) 购买域名
- [ ] (可选) 配置 DNS A 记录
- [ ] (可选) 配置 SSL 证书
- [ ] (可选) 配置反向代理 (Nginx/Caddy)
- [ ] 添加 API 认证
- [ ] 测试手机公网访问
- [ ] 配置日志和监控
- [ ] 备份数据库

---

## 🧪 测试公网访问

```bash
# 从任何地方测试
curl http://<公网IP>:8080/ping

# 或使用 Postman
# GET http://<公网IP>:8080/health

# 或使用手机浏览器
# 访问 http://<公网IP>:8080/health
```

---

## 📞 故障排查

### 1. 公网无法访问

```bash
# 检查防火墙规则
sudo iptables -L -n | grep 8080

# 检查端口监听
sudo ss -tuln | grep 8080

# 测试远程连接
telnet <公网IP> 8080
```

### 2. DNS 解析失败

```bash
# 检查 DNS
nslookup api.mushroom.com
dig api.mushroom.com

# 检查 A 记录
host api.mushroom.com
```

### 3. SSL 证书问题

```bash
# 检查证书
openssl x509 -in /path/to/cert.pem -text -noout

# 更新证书
sudo certbot renew --dry-run
```

---

## 💡 推荐方案选择

| 方案 | 难度 | 成本 | 安全性 | 推荐用途 |
|------|------|------|--------|---------|
| 直接 IP 访问 | ⭐ | 免费 | ⭐ | 测试/开发 |
| Ngrok | ⭐⭐ | 免费/付费 | ⭐⭐ | 快速演示 |
| 域名 + SSL | ⭐⭐⭐ | 低 | ⭐⭐⭐⭐ | 小型应用 |
| Nginx + SSL | ⭐⭐⭐ | 低 | ⭐⭐⭐⭐⭐ | 生产环境 |

---

**立即开始配置，让您的手机应用接入公网服务！** 🚀
