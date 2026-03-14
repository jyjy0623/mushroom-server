# 🚀 快速开始 - 手机访问 Mushroom Server

## 📱 连接信息

```
服务器 IP: 10.0.0.17
端口: 8080
API 地址: http://10.0.0.17:8080
```

## 🔗 可用的 API 端点

### 1️⃣ 健康检查
```
GET http://10.0.0.17:8080/health
↓ 响应 → {"status":"ok"}
```

### 2️⃣ 获取所有消息
```
GET http://10.0.0.17:8080/ping
↓ 响应 → [{"id":1,"message":"...","createdAt":...}]
```

### 3️⃣ 创建新消息
```
POST http://10.0.0.17:8080/ping
Content-Type: application/json

请求体:
{"message":"您的消息内容"}

↓ 响应 → {"id":2}
```

## ✅ 验证步骤

1. **在手机上打开浏览器**
   - 输入: `http://10.0.0.17:8080/health`
   - 应该看到: `{"status":"ok"}`

2. **或使用 Postman/Insomnia 等工具**
   - 测试 GET /ping
   - 测试 POST /ping (附加 JSON 数据)

3. **或使用代码**
   ```javascript
   fetch('http://10.0.0.17:8080/ping')
     .then(r => r.json())
     .then(data => console.log(data))
   ```

## 📋 需要的信息汇总

| 项目 | 值 |
|------|------|
| 服务器地址 | `10.0.0.17` |
| 端口 | `8080` |
| API 基础 URL | `http://10.0.0.17:8080` |
| 协议 | HTTP |
| 数据格式 | JSON |
| 认证 | 无 (开发环境) |

## 🚨 常见问题

**Q: 手机无法连接?**
- 确保手机和服务器在同一网络
- 尝试 ping 10.0.0.17
- 检查防火墙设置

**Q: 如何测试连接?**
- 手机浏览器访问: http://10.0.0.17:8080/health
- 应该立即看到响应

**Q: 数据是否会保存?**
- 是的，所有数据存储在 PostgreSQL 数据库
- 可以永久查询和修改

## 🛠 常用 curl 命令

```bash
# 健康检查
curl http://10.0.0.17:8080/health

# 获取所有消息
curl http://10.0.0.17:8080/ping

# 创建消息
curl -X POST http://10.0.0.17:8080/ping \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello"}'
```

---

**准备好了！您的手机应用现在可以访问这个服务。** 🎉
