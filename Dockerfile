FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
# Public source pinned independently of Maven Central publication.
RUN mkdir /tmp/javadriveless \
    && curl --fail --location --silent --show-error --connect-timeout 5 --max-time 30 --retry 2 \
        https://codeload.github.com/vitorcamillo/javaDriveless/tar.gz/1521539b26c7c60c44988ba04919dfab7898f5c4 \
        -o /tmp/javadriveless.tar.gz \
    && echo "91afa02207fbe89d114e3ecdfafe0e2042899b5f81b26787c4deba3165dbd0c3  /tmp/javadriveless.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/javadriveless.tar.gz --strip-components=1 -C /tmp/javadriveless \
    && mvn -q -B -f /tmp/javadriveless/pom.xml -DskipTests install \
    && rm -rf /tmp/javadriveless /tmp/javadriveless.tar.gz
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src src
COPY scripts/check-file-size.sh scripts/check-file-size.sh
RUN mvn -q -B -DskipTests -Dfile.size.guard.skip=true package

FROM eclipse-temurin:21-jre AS java-runtime

FROM node:22-bookworm-slim

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl fonts-liberation python3 python3-pip xauth xvfb wget gnupg xdotool \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

RUN pip3 install --break-system-packages --no-cache-dir curl_cffi==0.15.0

RUN npm install --global @openai/codex@0.139.0

RUN useradd --create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/data /var/lib/codex \
    && chown -R app:app /app /var/lib/codex

WORKDIR /app
COPY --from=build /workspace/target/freelas-atende-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --chown=app:app scripts/freelas_http.py /app/scripts/freelas_http.py
COPY --chown=app:app docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 755 /app/docker-entrypoint.sh

ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}" \
    CHROME_BIN=/usr/bin/google-chrome-stable \
    CHROMIUM_BIN=/usr/bin/google-chrome-stable \
    FREELANCERBR_BROWSER_USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36" \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=mysql \
    CODEX_HOME=/var/lib/codex

VOLUME ["/app/data", "/var/lib/codex"]
EXPOSE 8080

# Sem sonda de saude na imagem: o boot sob disputa de I/O (ddl-auto update)
# passou do teto de ~300s do worker do Coolify e o deploy caia em rollback
# mesmo com o app subindo em seguida. Nao reintroduzir verificacao aqui.

ENTRYPOINT ["/app/docker-entrypoint.sh"]
# Heap limitado explicitamente: sem -Xmx, o Java assume ate 25% da RAM do HOST
# (nao do cgroup do container em todo runtime), o que competia por memoria com
# Chrome+Xvfb no container de 1GB e causava OOM kill sem log (o processo
# simplesmente reiniciava). -Xmx384m deixa margem para 2 Chromes serializados
# (~150-200MB cada, via ChromeSessaoLock) + SO + Xvfb dentro do 1GB total.
# [2026-09-08] O limite do container subiu de 1024m para 2048m porque o Chrome
# sufocado era o gargalo real do login: com 1GB, cada executeScript via CDP
# levava dezenas de segundos (medido: 3min procurando o campo de senha, 5min
# entre credenciais e URL final) e nenhum prazo razoavel bastava. O heap do
# Java vai a 640m e sobra ~1,3GB para Chrome + Xvfb + SO.
# [2026-09-12] O `exec java` fazia a JVM virar PID 1, e a JVM nao faz reaping:
# cada Chrome encerrado era reparentado para o PID 1 e ficava <defunct> para
# sempre. Medido no servidor: 68 processos chrome no container, 66 deles
# zumbis, com ate 43h de idade, ocupando slots da tabela de processos do HOST
# (que e compartilhada com os outros apps do Coolify).
# A JVM agora roda em segundo plano e o shell, como PID 1, colhe os filhos
# entre uma volta e outra do wait. O trap repassa o TERM para encerramento
# limpo, senao o container so morreria no timeout do Docker.
CMD ["sh", "-c", "Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp >/tmp/xvfb.log 2>&1 & export DISPLAY=:99; java -Xms192m -Xmx640m -XX:MaxMetaspaceSize=160m -jar /app/app.jar & APP_PID=$!; trap 'kill -TERM $APP_PID 2>/dev/null' TERM INT; while kill -0 $APP_PID 2>/dev/null; do wait $APP_PID; CODIGO=$?; done; exit ${CODIGO:-0}"]
