FROM eclipse-temurin:25-jre

RUN groupadd --gid 1000 app && useradd --uid 1000 --gid app app

COPY package/dependency/lib /opt/app/lib
COPY package/app /opt/app

RUN chmod +x /opt/app/bin/core-ai-server && chown -R app:app /opt/app

USER app

EXPOSE 8080

ENTRYPOINT ["/opt/app/bin/core-ai-server"]
