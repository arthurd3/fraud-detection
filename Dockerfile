# ---- builder: binário nativo AOT (Oracle GraalVM 21 — tem PGO, free GFTC)
#      + motor de busca Rust (staticlib libfdsearch.a) linkado via C-interface ----
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /src
# Toolchain Rust (p/ libfdsearch.a). Imagem Oracle Linux → microdnf.
RUN microdnf install -y gcc tar gzip \
 && (command -v curl >/dev/null 2>&1 || microdnf install -y curl) \
 && microdnf clean all \
 && curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal \
 && /root/.cargo/bin/rustc --version
COPY rust-engine/ ./rust-engine/
RUN cd rust-engine && RUSTFLAGS="-C target-cpu=x86-64-v3" /root/.cargo/bin/cargo build --release   # => libfdsearch.a (AVX2/v3, casa o -march do native-image)
COPY api/ ./api/
# Stage do .a+.h p/ a C-interface (pom: -H:CLibraryPath / -H:CCompilerOption=/src/api/clib).
# api/default.iprof versionado (PGO offline — docs/TUTORIAL_NATIVE.md §6).
RUN mkdir -p /src/api/clib \
 && cp rust-engine/target/release/libfdsearch.a rust-engine/include/fdsearch.h /src/api/clib/ \
 && cd api && ./mvnw -q -Pnative -DskipTests package      # => target/api (ELF)

# ---- runtime: distroless glibc + binário + KD-tree RKD3 baked (sem JVM) ----
# Onda 7 v2: produção carrega só references.kdt (KD-tree exato). Binários
# legados int8/HNSW (references.bin/hnsw.bin) saíram do runtime — imagem e
# cgroup menores (~166 MB vs ~365 MB).
FROM gcr.io/distroless/base-debian12 AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api /app/api
COPY api/src/main/resources/references.kdt /data/references.kdt
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["/app/api","9999"]
