# ---- builder: binário nativo AOT (Oracle GraalVM 21 — tem PGO, free GFTC) ----
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /src
COPY api/ ./api/
# api/default.iprof versionado (PGO offline — ver docs/TUTORIAL_NATIVE.md §6);
# o profile `native` consome via --pgo=default.iprof.
RUN cd api && ./mvnw -q -Pnative -DskipTests package      # => target/api (ELF)

# ---- runtime: distroless glibc + binário + binários RBH2 baked (sem JVM) ----
FROM gcr.io/distroless/base-debian12 AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api /app/api
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["/app/api","9999"]
