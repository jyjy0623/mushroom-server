# 🚀 实施步骤汇总

## 立即可用的方案 (30 秒)

### 最快上手方案: 使用 Ngrok

```bash
# 1. 下载 Ngrok
wget https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-stable-linux-amd64.zip
unzip ngrok-stable-linux-amd64.zip

# 2. 注册账号 (免费)
# 访问 https://ngrok.com/signup

# 3. 获取 authtoken (从 dashboard)
# 复制您的 authtoken

# 4. 配置并启动
./ngrok authtoken <YOUR_AUTHTOKEN>
./ngrok http 8080

# 5. 获得公网 URL
# 输出类似: https://abc123.ngrok.io

# 6. 在手机上访问
# https://abc123.ngrok.io/ping
```

---

## 标准方案 (10 分钟) - 推荐

### 使用公网 IP + 防火墙配置

**步骤 1: 获取公网 IP**
```bash
curl ifconfig.me
# 记录输出，例如: 203.0.113.42
```

**步骤 2: 配置云服务商防火墙**
- 登录云服务商控制台 (阿里云/腾讯云/AWS 等)
- 找到安全组 -> 入站规则
- 添加规则:
  - 协议: TCP
  - 端口: 8080
  - 源: 0.0.0.0/0

**步骤 3: 验证**
```bash
# 从手机浏览器访问
http://203.0.113.42:8080/health
# 应该看到: {"status":"ok"}
```

---

## 完整方案 (30 分钟) - 生产级别

### 使用 Nginx + Let's Encrypt SSL

**步骤 1: 安装依赖**
```bash
sudo apt update
sudo apt install nginx certbot python3-certbot-nginx
```

**步骤 2: 配置域名 DNS**
- 在域名服务商处添加 A 记录
- 指向您的公网 IP

**步骤 3: 获取 SSL 证书**
```bash
sudo certbot certonly --standalone -d api.example.com
```

**步骤 4: 配置 Nginx**
```bash
sudo tee /etc/nginx/sites-available/mushroom > /dev/null <<EOF
server {
    listen 80;
    server_name api.example.com;
    return 301 https://\$server_name\$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;

    ssl_certificate /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/mushroom /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

**步骤 5: 手机访问**
```
https://api.example.com/ping
```

**步骤 6: 设置自动续期**
```bash
sudo systemctl enable certbot.timer
sudo systemctl start certbot.timer
```

---

## 🔐 添加 API 认证 (可选但推荐)

### 修改应用代码

编辑 `src/main/kotlin/Application.kt`:

```kotlin
package com.mushroom

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.routing.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureDatabases()
    configureRouting()
}

fun Application.configureRouting() {
    routing {
        // 验证 API 密钥的中间件
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            // 健康检查不需要认证
            if (path != "/health") {
                val apiKey = call.request.headers["X-API-Key"]
                val expectedKey = environment.config.property("api.key").getString()

                if (apiKey != expectedKey) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid API Key")
                    )
                    finish()
                }
            }
        }

        // ... 原有的路由代码
    }
}
```

编辑 `src/main/resources/application.yaml`:

```yaml
ktor:
    application:
        modules:
            - com.mushroom.ApplicationKt.module
    deployment:
        port: 8080

postgres:
    url: "jdbc:postgresql://172.17.0.2:5432/mushroom"
    user: "mushroom"
    password: "postgres_root_password_123"

api:
    key: "your-secret-api-key-here-change-me"
```

重建 Docker 镜像:
```bash
bash docker-build.sh
docker restart mushroom-server
```

手机访问时添加密钥:
```javascript
fetch('http://10.0.0.17:8080/ping', {
  headers: {
    'X-API-Key': 'your-secret-api-key-here-change-me'
  }
})
```

---

## 📊 方案对比

| 特性 | Ngrok | 公网IP | Nginx+SSL |
|------|-------|--------|-----------|
| 配置时间 | 5分钟 | 10分钟 | 30分钟 |
| 成本 | 免费/付费 | 免费 | 免费* |
| HTTPS | ✅ | ❌ | ✅ |
| 域名 | 不需要 | 不需要 | 需要 |
| 稳定性 | 中 | 高 | 高 |
| 推荐用途 | 演示 | 内网+测试 | 生产 |

*仅需域名成本 (可选免费子域)

---

## ✅ 选择建议

### 如果您想...

**快速演示应用** → 使用 Ngrok
```bash
./ngrok http 8080
# 立即获得公网 URL
```

**在公司内网使用** → 使用公网 IP
```bash
# 配置防火墙，直接访问 http://<IP>:8080
```

**生产环境** → 使用 Nginx + SSL
```bash
# 完整的企业级配置，包含 HTTPS 和认证
```

---

## 🧪 测试清单

- [ ] 服务器应用运行正常 (docker ps 显示 healthy)
- [ ] 数据库连接正常 (docker logs 显示"Database connected")
- [ ] 内网访问正常 (http://10.0.0.17:8080/health 返回 OK)
- [ ] 防火墙规则已配置 (允许 8080 或 443 端口)
- [ ] 公网访问测试成功 (手机可以访问)
- [ ] HTTPS 工作正常 (如果使用 SSL)
- [ ] API 认证工作正常 (如果添加了)

---

## 🚨 常见错误解决

### 错误 1: "连接被拒绝"
```bash
# 检查应用是否运行
docker ps | grep mushroom-server

# 检查防火墙
sudo ufw status
sudo iptables -L -n | grep 8080
```

### 错误 2: "超时"
```bash
# 检查网络连接
ping <服务器IP>
telnet <服务器IP> 8080
```

### 错误 3: "SSL 证书错误"
```bash
# 检查证书有效期
sudo certbot certificates

# 强制更新
sudo certbot renew --force-renewal
```

---

**选择您的方案，开始部署！** 🎯
