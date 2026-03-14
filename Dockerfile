# Runtime stage only (JAR already built locally)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the pre-built jar
COPY build/libs/*.jar app.jar

# Create a non-root user for security
RUN addgroup -g 1000 mushroom && \
    adduser -D -u 1000 -G mushroom mushroom && \
    chown -R mushroom:mushroom /app

# Copy default application.yaml
COPY src/main/resources/application.yaml /app/application.yaml
RUN chown mushroom:mushroom /app/application.yaml

USER mushroom

# Expose the port that Ktor runs on
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
