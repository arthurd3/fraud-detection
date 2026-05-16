# Spec — Onda 2a: quantização int8 + binário mmap (`TUTORIAL_INT8_QUANT.md`)

> Data: 2026-05-16 · Projeto: `fraudDetection` (Rinha de Backend 2026) · Antecessor: Onda 1 (fechada — POST /fraud-score e2e correto vs oráculos REGRAS_DE_DETECCAO.md)

## Contexto

A Onda 1 carrega `references.json.gz` (3M × 14) em `float[3_000_000][14]` no heap (~220 MB, exige `-Xmx768m`) e faz KNN brute-force escalar float. Restrição da Rinha: **1 CPU + 350 MB RAM total** para todos os serviços (≥2 instâncias da API). O heap float não cabe em 2 instâncias. RINHA_PLAN §9.2 prevê Onda 2 = quantização int8 + binário mmap + SIMD.

**Decisão de escopo (aprovada):** dividir a Onda 2 em **2a** (este doc: binário int8 + `MmapDataset` v2 mmap + `Quantizer` + distância int8 **escalar**) e **2b** (futuro: `DistanceFunctions` v2 com `jdk.incubator.vector` SIMD). 2a isola o ganho de memória/parse; 2b isola o risco de SIMD (regressão silenciosa em Native Image).

**Regra do projeto:** tutorial-driven. O entregável desta spec é o doc hands-on `docs/TUTORIAL_INT8_QUANT.md` (mesmo estilo do `TUTORIAL_JSON_KNN.md`), que o usuário implementa à mão. **Não** auto-implementar os `.java`; **não** invocar writing-plans para código.

## Decisões travadas (do brainstorming)

1. **Escopo:** 2a = binário int8 + mmap + Quantizer + distância int8 escalar. SIMD → 2b.
2. **Quantização:** global simétrica `q = round(clamp(v,-1,1) * 127)`, int8 `[-127,127]`. Sem per-dim, sem offset, sem `&0xFF`. Sentinela `-1.0 → -127`.
3. **Aceitação:** Gate 1 = 2 oráculos do §10 exatos; Gate 2 = `approved_int8 == approved_float` ≥ 99% vs baseline float da Onda 1.
4. **Ciclo do `.bin`:** `MmapDataset` v2 self-bootstrapping (boot: existe → mmap; ausente → stream `.gz` → quantiza → grava `.bin` → mmap). `.bin` no `.gitignore`.

## Design

### §1. Formato `references.bin` (1 arquivo, 1 mmap)

```
[ header 12B ]  magic "RB1\0" (4B) · int32 count (big-endian) · int32 dims (=14)
[ vetores    ]  count × 14  int8  (row-major, contíguo)        ~42 MB p/ 3M
[ labels     ]  count × 1   byte  (0 = legit, 1 = fraud)        ~3 MB
```

- Offsets: `vec(i,d) = 12 + i*14 + d` · `label(i) = 12 + count*14 + i`.
- Header escrito com `DataOutputStream.writeInt` / lido com `DataInputStream.readInt` (big-endian, explícito). Corpo são bytes → endianness irrelevante.
- Magic + dims validados na leitura (detecta `.bin` de formato/versão errados).
- Bitset de labels (375 KB) fica como nota de otimização futura — 1 byte/registro é mais didático e byte-endereçável no mmap.

### §2. `knn/Quantizer.java`

```java
public final class Quantizer {
    private Quantizer() {}
    static float clamp(float v){ return v < -1f ? -1f : (v > 1f ? 1f : v); }
    public static byte q(float v){ return (byte) Math.round(clamp(v) * 127f); }
}
```

- Range garantido `[-127,127]` (cabe em `byte` signed sem `&0xFF`).
- `clamp` defende contra drift numérico; dims 0–13 são normalizadas `[0,1]` exceto 5,6 ∈ `{-1} ∪ [0,1]`.

### §3. `dataset/MmapDataset` v2 — self-bootstrapping

Estado estático: `MappedByteBuffer data; int count; int vecBase (=12); int lblBase (=12+count*14);`

**`load()`:**
1. Se `src/main/resources/references.bin` **existe**: `FileChannel.map(READ_ONLY,0,size)` → `MappedByteBuffer`; lê header; valida magic + dims=14; seta `count`, `lblBase`.
2. Se **ausente**: streaming de `references.json.gz` reusando o parser byte-a-byte da Onda 1 (`skipTo/nextNonWs/readFloat`); para cada registro: quantiza os 14 floats (`Quantizer.q`) e os escreve via `RandomAccessFile` após um header placeholder (count=0); labels acumuladas num `byte[]` transitório (≈3 MB, build é startup 1×, aceitável); ao fim: append da região de labels, `seek(4)` reescreve `count`, `getFD().sync()`, fecha; então mmapeia o arquivo recém-escrito (volta ao passo 1).

- `madvise(MADV_RANDOM)` → nota futura (Native Image/Onda 5); na 2a o page cache do HotSpot basta.
- Acesso: `byte v(int i,int d){ return data.get(12 + i*14 + d); }` · `boolean fraud(int i){ return data.get(lblBase + i) != 0; }`.

### §4. Caminho de query + distância int8 escalar

- `server/ConnectionState`: `+ public final byte[] queryQ = new byte[14];` (scratch zero-alloc, sobrescrito por request; **não** limpar no `reset()` — mesma justificativa do `queryVector`).
- `controllers/FraudController`: após `FraudRequestParser.parse`, quantiza `state.queryVector` → `state.queryQ` (14 ops) antes do KNN.
- `knn/DistanceFunctions` v2: `int sqDistI8(byte[] q, MappedByteBuffer V, int base){ int acc=0; for(int k=0;k<14;k++){ int d=q[k]-V.get(base+k); acc+=d*d; } return acc; }` — acumulador **int32** (máx `14·254² ≈ 903_056 << 2^31`; pegadinha do RINHA_PLAN §12).
- `knn/HnswIndex` v2.search: mesma estrutura top-5 brute-force da Onda 1, mas iterando `MmapDataset.count` registros do `MappedByteBuffer`. **`s.knnDist` permanece `float[5]`** (NÃO mudar o tipo — `ConnectionState` é compartilhado): a distância int32 é guardada como `float` (exata p/ inteiros < 2²⁴; máx ~903k). `fraudCount` das labels mmap. Nome `HnswIndex` mantido (continua baseline brute-force; Onda 3 troca por HNSW real).

### §5. Validação e aceitação

- **Gate 1 (bloqueia):** §10 e2e idêntico à Onda 1 — `GET /ready` → 200; `tx-1329056812` → `{"approved":true,"fraud_score":0.0}`; `tx-3330991687` → `{"approved":false,"fraud_score":1.0}`.
- **Baseline float congelado:** rodar a Onda 1 (commit `43c2a65`) **uma vez** sobre as **primeiras N=2.000** entradas de `rinha-de-backend-2026/test/test-data.json`, gravando `{id → approved}` em `docs/baselines/onda1-approved-2000.txt` (commitado — oráculo reproduzível; evita rodar 2 engines 3M ao vivo).
- **Gate 2 (sanity quantitativo):** harness **offline** da 2a (sem HTTP — `FraudRequestParser → Quantizer → HnswIndex v2` por request) roda as **mesmas primeiras N=2.000** e afirma `approved_int8 == approved_baseline` em **≥ 99%**; reporta nº de FP/FN introduzidos pela quantização. N determinístico (primeiras N); configurável; full 54.100 = opcional/offline (regenera o baseline com N=54100).
- Onda 1 valida ondas seguintes (RINHA_PLAN): aqui isso é o **arquivo de baseline congelado**, não 2 engines vivos.

### §6. Escopo / não-objetivos

- **Ganho:** heap ~220 MB → ~0; dataset ~42 MB off-heap (cabe folgado em 350 MB/2 instâncias); 2º boot ~instantâneo (sem parse de 3M).
- **Fora (vai pra 2b):** SIMD / `jdk.incubator.vector` — `DistanceFunctions` continua **escalar** na 2a. Latência p99 **não** é alvo da 2a; correção + memória são. Bitset de labels, `madvise`, per-dim quant: notas futuras.

## Inventário de arquivos

| Arquivo | Ação |
|---|---|
| `knn/Quantizer.java` | **novo** |
| `dataset/MmapDataset.java` | **reescrito** v2 (mmap self-bootstrap); lógica de parse v1 reusada |
| `knn/DistanceFunctions.java` | **+`sqDistI8`** (mantém `sqDist` float — usado só p/ regenerar o baseline congelado, não no Gate 2 runtime) |
| `knn/HnswIndex.java` | **v2** (itera mmap int8; `knnDist` int) |
| `server/ConnectionState.java` | **+`byte[14] queryQ`** |
| `controllers/FraudController.java` | **+quantiza query** antes do KNN |
| `Main.java` | `MmapDataset.load()` (mesma chamada; v2 decide bin vs gz) |
| `api/.gitignore` | **+`src/main/resources/references.bin`** |
| `docs/baselines/onda1-approved-2000.txt` | **novo** — baseline float congelado da Onda 1 (commitado), oráculo do Gate 2 |
| validação Gate 2 | runner temporário (test point) lê o baseline + roda int8 offline; removido ao fim — não vira código de produção |

## Test points do tutorial (espelham o estilo do JSON_KNN)

1. `Quantizer.q`: `q(0)=0`, `q(1.0)=127`, `q(-1.0)=-127`, `q(0.5)≈64`, clamp de `1.5→127`.
2. Build do `.bin`: 1º boot gera `references.bin`; header `count=3000000`, dims=14, tamanho exato `12 + 3_000_000*14 + 3_000_000 = 45_000_012` bytes.
3. 2º boot: `.bin` existe → mmap, sem reparse (log de boot ~instantâneo).
4. `sqDistI8`: vetores conhecidos batem o esperado int.
5. Gate 1: §10 e2e (2 oráculos exatos).
6. Gate 2: amostra 2.000 do test-data.json, agreement int8×float ≥ 99%, relatório FP/FN.

**Critério de saída da Onda 2a:** Gate 1 verde **e** Gate 2 ≥ 99%, com o dataset rodando off-heap via `MappedByteBuffer` (heap sem `float[3M][14]`).

## Riscos / mitigações

- *Quantização muda ranking → muda `fraud_score`*: oráculos do §10 são 0/5 e 5/5 (longe do limiar 0.6) → robustos; Gate 2 quantifica a perda nos casos de borda.
- *`MappedByteBuffer` é `int`-bounded (2^31)*: `12 + 3M*14 + 3M ≈ 45 MB` << 2^31 → ok.
- *Heap no build*: labels `byte[]` ~3 MB transitório (1×, startup) → aceitável; vetores vão direto pro arquivo (sem buffer 42 MB em heap).
- *Native Image (Onda 5)*: `madvise`/Vector API ficam para depois; 2a é HotSpot.

## Próximo passo

Produzir `docs/TUTORIAL_INT8_QUANT.md` (hands-on, PT-BR, test points acima) para o usuário implementar à mão. **Sem** auto-implementar `.java`.
