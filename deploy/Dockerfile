FROM eclipse-temurin:25-jre

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /data/uploads /data/contracts \
    && chown -R app:app /app /data

WORKDIR /app

COPY --chown=app:app app.jar /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
