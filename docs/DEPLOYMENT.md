# 🍄 Mushroom Server Deployment

Complete deployment infrastructure for the mushroom-server game backend application.

## 📋 Quick Start

### 1. Initialize PostgreSQL Database

```bash
sudo bash /root/scripts/setup-database.sh
```

This creates the `mushroom` database and user with all necessary permissions.

### 2. Build Docker Image

```bash
cd /root/workspace/mushroom-server
bash docker-build.sh
```

This creates the `mushroom-server:latest` Docker image using a multi-stage build.

### 3. Start the Application

```bash
sudo systemctl start mushroom-server
```

The application will start automatically on system boot due to systemd auto-start configuration.

### 4. Verify Deployment

```bash
bash /root/scripts/verify-deployment.sh
```

This checklist script verifies all deployment components are properly configured.

## 📁 Deployment Files

### Project Files
- **`Dockerfile`** - Multi-stage Docker build (builder + runtime)
- **`.dockerignore`** - Files to exclude from Docker build context
- **`docker-build.sh`** - Convenient build script with colored output
- **`src/main/resources/application.yaml`** - Application configuration

### Deployment Scripts
- **`/root/scripts/setup-database.sh`** - PostgreSQL database and user initialization
- **`/root/scripts/verify-deployment.sh`** - Deployment verification checklist
- **`/usr/local/bin/docker-run.sh`** - Container startup script with health checks

### System Configuration
- **`/etc/systemd/system/mushroom-server.service`** - systemd service unit
- **`/etc/nginx/sites-available/mushroom-server`** - Nginx reverse proxy config

### Documentation
- **`/root/workspace/DEPLOYMENT_GUIDE.md`** - Complete deployment guide with troubleshooting

## 🏗️ Architecture

```
┌─────────────────────────────────┐
│    Nginx (Port 80/443)          │
│    Reverse Proxy                │
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  Docker Container               │
│  mushroom-server:8080           │
│  (Kotlin/Ktor Application)      │
└──────────┬──────────────────────┘
           │ JDBC
           ▼
┌─────────────────────────────────┐
│  PostgreSQL                     │
│  localhost:5432/mushroom        │
└─────────────────────────────────┘
```

## 📊 Configuration Details

### Database
- **Host:** localhost
- **Port:** 5432
- **Database:** mushroom
- **User:** mushroom
- **Password:** mushroom_secure_password_123 (change in production)

### Application
- **Port:** 8080 (internal)
- **Framework:** Ktor (Kotlin)
- **Runtime:** Java 17
- **Database Driver:** PostgreSQL JDBC

### Nginx
- **Port:** 80 (HTTP)
- **Port:** 443 (HTTPS, optional)
- **Upstream:** 127.0.0.1:8080
- **Features:** Reverse proxy, header forwarding, SSL support

## 🚀 Common Operations

### View Logs

```bash
# Container logs
docker logs mushroom-server -f

# systemd service logs
sudo journalctl -u mushroom-server -f

# Nginx access logs
sudo tail -f /var/log/nginx/mushroom-server-access.log

# Nginx error logs
sudo tail -f /var/log/nginx/mushroom-server-error.log
```

### Service Management

```bash
# Start service
sudo systemctl start mushroom-server

# Stop service
sudo systemctl stop mushroom-server

# Restart service
sudo systemctl restart mushroom-server

# Check service status
sudo systemctl status mushroom-server

# View service is enabled
sudo systemctl is-enabled mushroom-server
```

### Docker Management

```bash
# Build image
cd /root/workspace/mushroom-server && bash docker-build.sh

# Run container manually
sudo bash /usr/local/bin/docker-run.sh

# Stop container
docker stop mushroom-server

# View logs
docker logs mushroom-server -f

# Inspect container
docker inspect mushroom-server
```

### Database Management

```bash
# Connect to database
PGPASSWORD=mushroom_secure_password_123 psql -h localhost -U mushroom -d mushroom

# Backup database
sudo -u postgres pg_dump -Fc mushroom > /backup/mushroom.dump

# Restore database
sudo -u postgres pg_restore -d mushroom /backup/mushroom.dump
```

## ✅ Health Checks

```bash
# Check application health
curl http://localhost/health

# Check through Nginx
curl http://localhost/health -v

# Check database connection
PGPASSWORD=mushroom_secure_password_123 psql -h localhost -U mushroom -d mushroom -c "SELECT 1;"

# Check Nginx status
sudo systemctl status nginx

# Check service status
sudo systemctl status mushroom-server
```

## 🔧 Configuration

### Change Database Password

1. Update the password in application.yaml:
```yaml
postgres:
    password: "new_password"
```

2. Update PostgreSQL user:
```bash
sudo -u postgres psql -c "ALTER USER mushroom WITH PASSWORD 'new_password';"
```

3. Restart the service:
```bash
sudo systemctl restart mushroom-server
```

### Enable HTTPS

1. Uncomment the HTTPS section in `/etc/nginx/sites-available/mushroom-server`
2. Add your SSL certificate paths
3. Test: `sudo nginx -t`
4. Reload: `sudo systemctl reload nginx`

### Change Application Port

1. Edit `/root/workspace/mushroom-server/src/main/resources/application.yaml`:
```yaml
ktor:
    deployment:
        port: 9090  # Change from 8080
```

2. Edit `/usr/local/bin/docker-run.sh`:
```bash
PORT=9090  # Update port variable
```

3. Edit `/etc/nginx/sites-available/mushroom-server`:
```nginx
upstream mushroom_backend {
    server 127.0.0.1:9090;  # Update port
}
```

4. Rebuild and restart:
```bash
cd /root/workspace/mushroom-server && bash docker-build.sh
sudo systemctl restart mushroom-server
sudo systemctl reload nginx
```

## 🐛 Troubleshooting

### PostgreSQL Connection Error
```bash
# Check PostgreSQL is running
sudo systemctl status postgresql

# Check connectivity
pg_isready -h localhost -p 5432

# Verify credentials
PGPASSWORD=mushroom_secure_password_123 psql -h localhost -U mushroom -d mushroom
```

### Docker Build Fails
```bash
# Clean up and rebuild
docker system prune -a
cd /root/workspace/mushroom-server
bash docker-build.sh
```

### Container Won't Start
```bash
# View logs
docker logs mushroom-server

# Run manually to debug
sudo bash /usr/local/bin/docker-run.sh
```

### Nginx Returns 502
```bash
# Check upstream is running
curl http://127.0.0.1:8080/health

# Test Nginx config
sudo nginx -t

# View error logs
sudo tail -f /var/log/nginx/mushroom-server-error.log
```

### Port Already in Use
```bash
# Find process using port 8080
lsof -i :8080

# Kill process
sudo kill -9 <PID>

# Or change port in configuration
```

## 📈 Monitoring

### Real-time Resource Usage
```bash
# Docker stats
docker stats mushroom-server

# System resources
top

# Disk usage
df -h /

# Memory usage
free -h
```

### Database Monitoring
```bash
# Table sizes
PGPASSWORD=mushroom_secure_password_123 psql -h localhost -U mushroom -d mushroom << 'SQL'
SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
FROM pg_tables
WHERE schemaname != 'pg_catalog'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
SQL

# Database size
PGPASSWORD=mushroom_secure_password_123 psql -h localhost -U mushroom -d mushroom -c "SELECT pg_size_pretty(pg_database_size(current_database()));"
```

## 🔒 Security

- Container runs as non-root user (uid: 1000)
- systemd service has security restrictions enabled
- PostgreSQL credentials stored in application.yaml (add to .gitignore)
- Nginx configured to block access to sensitive files
- All connections over localhost (can be exposed via Nginx with auth)

## 📝 File Locations Summary

| Component | File |
|-----------|------|
| Application Config | `/root/workspace/mushroom-server/src/main/resources/application.yaml` |
| Dockerfile | `/root/workspace/mushroom-server/Dockerfile` |
| Build Script | `/root/workspace/mushroom-server/docker-build.sh` |
| systemd Service | `/etc/systemd/system/mushroom-server.service` |
| Nginx Config | `/etc/nginx/sites-available/mushroom-server` |
| Startup Script | `/usr/local/bin/docker-run.sh` |
| DB Setup Script | `/root/scripts/setup-database.sh` |
| Verification Script | `/root/scripts/verify-deployment.sh` |
| Deployment Guide | `/root/workspace/DEPLOYMENT_GUIDE.md` |

## 🆘 Getting Help

1. Check logs: `docker logs mushroom-server -f`
2. Run verification: `bash /root/scripts/verify-deployment.sh`
3. Read guide: `cat /root/workspace/DEPLOYMENT_GUIDE.md`
4. Check service status: `sudo systemctl status mushroom-server`

## 📞 Support Resources

- Application Logs: `docker logs mushroom-server -f`
- Service Logs: `sudo journalctl -u mushroom-server -f`
- Nginx Logs: `/var/log/nginx/`
- PostgreSQL Logs: `/var/log/postgresql/`
- Deployment Guide: `/root/workspace/DEPLOYMENT_GUIDE.md`

---

**Deployment Version:** 1.0.0
**Last Updated:** 2026-03-11
**Status:** ✅ Ready for Deployment
