# Runtime stage only (JAR already built locally)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the pre-built jar
COPY build/libs/*.jar app.jar

# Install curl for health checks
RUN apk add --no-cache curl

# Create a non-root user for security
RUN addgroup -g 1000 mushroom && \
    adduser -D -u 1000 -G mushroom mushroom && \
    chown -R mushroom:mushroom /app

USER mushroom

# Expose the port that Ktor runs on
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
