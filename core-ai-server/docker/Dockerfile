FROM eclipse-temurin:25-jre

RUN groupadd --gid 1001 app && useradd --uid 1001 --gid app --no-create-home app

COPY package/dependency/lib /opt/app/lib
COPY package/app /opt/app

RUN chmod +x /opt/app/bin/core-ai-server && chown -R 1001:1001 /opt/app

USER 1001

EXPOSE 8080

ENTRYPOINT ["/opt/app/bin/core-ai-server"]
