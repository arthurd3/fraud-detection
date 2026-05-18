# ---- builder: compila o jar (41 KB, sem dataset) ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY api/ ./api/
RUN cd api && ./mvnw -q clean package -DskipTests

# ---- runtime: jre + jar + binários RBH2 da Onda 4a baked ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api.jar /app/api.jar
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["java","-DDATA_PATH=/data", \
  "-Xmx64m","-XX:+UseSerialGC","-XX:MaxMetaspaceSize=64m","-Xss512k", \
  "-XX:ReservedCodeCacheSize=24m","--add-modules","jdk.incubator.vector", \
  "-jar","/app/api.jar","9999"]