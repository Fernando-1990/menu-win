# Imagem da API para produção (Render). Build: docker build -t menu-win-api .
# Os testes não rodam aqui (Testcontainers precisa de Docker): rodar `mvn test` antes do push.

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/menu-win-api-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 menuwin
COPY --from=build /app/app.jar app.jar
USER menuwin
# prod: sem seed, sem Pix simulado, segredos obrigatórios.
# Memória: o plano do Render tem 512 MB, sem swap, e mata o processo que passa disso (medido: com heap
# de 256 MB e sem limite de arenas do malloc, a JVM chegou a ~570 MB sob carga). Objetos vivos ~45 MB,
# então heap em 35% (~180 MB) sobra; metaspace ~90 MB, code cache ~40 MB. MALLOC_ARENA_MAX corta ~100 MB.
ENV SPRING_PROFILES_ACTIVE=prod \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=35 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
