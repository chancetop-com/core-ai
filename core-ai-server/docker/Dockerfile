FROM eclipse-temurin:25-jre

# Install basic utilities required for runtime vendor auto-installation (e.g., tar for extracting archives)
RUN apt-get update && apt-get install -y --no-install-recommends tar gzip && rm -rf /var/lib/apt/lists/*

# Create app user with home directory to store runtime vendors (~/.core-ai/vendors)
RUN groupadd --gid 1001 app && useradd --uid 1001 --gid app -m app

COPY package/dependency/lib /opt/app/lib
COPY package/app /opt/app

RUN chmod +x /opt/app/bin/core-ai-server && chown -R 1001:1001 /opt/app

USER 1001

EXPOSE 8080

ENTRYPOINT ["/opt/app/bin/core-ai-server"]
