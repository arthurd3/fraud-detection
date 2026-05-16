# Tutorial — Onda 2b: distância int8 vetorizada (SIMD / Vector API)

> De **Onda 2a fechada** (`POST /fraud-score` correto, int8 off-heap RB1, distância **escalar**)
> → **mesma resposta** com a distância calculada em **SIMD** (`jdk.incubator.vector`), reduzindo p99.
> **Tempo estimado**: 4-7h. **Pré-requisito absoluto**: Onda 2a fechada (HEAD `27bcab1`, Gate 1+2
> verdes, `./mvnw clean package` OK). Spec: `docs/superpowers/specs/2026-05-16-onda2b-simd-design.md`.

---

## §0. Visão geral, o que muda, critério de saída

A Onda 2a funciona mas a distância é **escalar**: `DistanceFunctions.sqDistI8` faz 14 iterações
e é chamada **3.000.000× por request** no brute-force de `HnswIndex.search`. É o gargalo de
latência. A Onda 2b troca essa distância por **SIMD** (Vector API, AVX2), mantendo o
**mesmo `{approved, fraud_score}`** — matemática inteira é exata, então o resultado é
**bit-idêntico** ao da 2a.

> **Onda 2b é só latência.** HNSW = Onda 3; container/k6 = Onda 4; Native Image = Onda 5.

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `dataset/MmapDataset.java` | **RB2** — formato padded-16 (14 reais + 2 zero) + auto-migração |
| 2 | `server/ConnectionState.java` | `queryQ`→`byte[16]` + novo `vScratch` `byte[16]` |
| 3 | `knn/DistanceFunctions.java` | **reescrito** — `sqDistI8` SIMD + `sqDistI8Scalar` (ref) |
| 4 | `knn/HnswIndex.java` | itera com `V.get(...,16)` + `sqDistI8(q,vScratch)` |
| 5 | `controllers/FraudController.java` | **inalterado** |
| 6 | `src/test/.../DistEquivI8.java` | **novo** — Gate A (SIMD == escalar) |
| 7 | `src/test/.../BenchSearch.java` | **novo** — Gate 3 (p50/p99 escalar vs SIMD) |
| 8 | `docs/baselines/onda1-approved-2000.txt` | **inalterado** (oráculo congelado) |

### Critério de saída da Onda 2b

- **Gate A (bloqueia):** `sqDistI8` (SIMD) `==` `sqDistI8Scalar` em **todos** os 3M registros,
  p/ os 2 oráculos. Zero divergências.
- **Gate 1 (bloqueia):** §10 e2e idêntico — `/ready` 200; `tx-1329056812`→
  `{"approved":true,"fraud_score":0.0}`; `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`.
- **Gate 2 (bloqueia):** `Gate2Int8 2000` = **exatamente `1995/2000 = 99.75% (FP=2 FN=3)
  PASS`** (idêntico à 2a — RB2 = RB1 + pad zero).
- **Gate 3 (medição):** `BenchSearch` reporta p50/p99 **escalar vs SIMD** + speedup.
- 1º boot 2b regenera `references.bin` RB2 = **51.000.012** bytes; roda em `-Xmx256m`.

---

## §1. Mapa mental: o que muda no fluxo

```
BOOT (1ª vez 2b): references.bin RB1 antigo -> magic != RB2 -> REGENERA RB2 (padded-16)
BOOT (2ª vez+):   references.bin RB2 --mmap--> pronto

POST /fraud-score:
  FraudRequestParser.parse  -> state.queryVector[14] (float)     (igual 2a)
  Quantizer.quantize        -> state.queryQ[16] (int8, [14..15]=0) (igual 2a; array agora 16)
  HnswIndex.search          -> p/ cada vetor: V.get(...,vScratch,16) + sqDistI8 SIMD
  state.fraudCount -> 1 das 6 respostas canned                   (igual 2a)
```

Só **o cálculo da distância** e o **stride do dataset** mudam. Parser, fórmula 14-D,
respostas canned, servidor NIO, quantização — **intactos**.

---

## §2. Princípios (lembrete + Vector API)

Os mesmos da 2a, mais:

1. **`byte` é signed.** `q-v ∈ [-254,254]`; `(q-v)² ≤ 254² = 64.516` → **estoura `short`**
   (máx 32.767). Regra de ouro: **alargue `byte`→`int` ANTES de subtrair**; subtraia, eleve
   ao quadrado e acumule **em `int`**.
2. **Vector API é incubator, não preview.** O `pom.xml` já tem
   `--add-modules jdk.incubator.vector` (compiler-plugin + run). **Não** mexa no `pom`,
   **não** use `--enable-preview` nem `java.lang.foreign` (preview no Java 21).
3. **Padded-16 evita máscara.** 14 dims não casam com espécies SIMD (8/16/32 lanes). Em vez
   de mascarar, o dataset e a query ganham **2 bytes zero** → 16 lanes limpas; pad
   `(0-0)²=0` não muda a soma.
4. **Espécies fixas e conservadoras.** `ByteVector.SPECIES_64` (8) + `IntVector.SPECIES_256`
   (8) rodam em qualquer CPU com AVX2. **Não** use `SPECIES_PREFERRED` aqui (portabilidade
   e determinismo do Gate A).
5. **Harness de teste pode usar `String`/JDK à vontade** — não é a aplicação.

---

## §3. Formato `references.bin` v2 (RB2)

```
[ header 12B ]  magic 'R','B','2',0 (4B) · int32 count (big-endian) · int32 dims (=14)
[ vetores    ]  count × 16  int8   (14 reais + 2 zero, row-major, contíguo)
[ labels     ]  count × 1   byte   (0 = legit, 1 = fraud)
```

- `STRIDE = 16` · `recBase(i) = 12 + i*16` · `lblBase = 12 + count*16`.
- `dims` no header continua **14** (dimensão lógica; o pad é detalhe de storage).
- Tamanho p/ 3M: `12 + 3_000_000*16 + 3_000_000 = 51_000_012` bytes (RB1 era 45.000.012).
- **Auto-migração**: o `.bin` da 2a é RB1 (magic `R B 1`). `load()` detecta magic ≠ RB2 e
  **regenera** sozinho no 1º boot da 2b. Sem comando manual, sem apagar nada.
- `.bin` segue gitignored/regenerável; o baseline float **não muda**.

---

## §4. `dataset/MmapDataset.java` (RB2)

**Substitua todo o conteúdo.** Mudou: `STRIDE=16`, magic `RB2`, `isRB2()` (auto-migração),
`build()` grava 16 bytes/registro, `recBase/lblBase` usam `STRIDE`.

```java
package org.fraudDetection.dataset;

import org.fraudDetection.knn.Quantizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

/**
 * Onda 2b: dataset int8 RB2 (padded-16) off-heap via MappedByteBuffer.
 * Cada vetor = 16 bytes: 14 reais + 2 zero (pad p/ SIMD sem máscara).
 */
public final class MmapDataset {

    public static final int DIMS = 14;
    private static final int STRIDE = 16;                 // 14 reais + 2 pad
    private static final int HEADER = 12;                 // magic(4)+count(4)+dims(4)
    private static final byte[] MAGIC = {'R', 'B', '2', 0};

    public static MappedByteBuffer data;
    public static int count;
    public static int lblBase;                            // HEADER + count*STRIDE

    private MmapDataset() {}

    public static void load(String gzPath, String binPath) throws IOException {
        File bin = new File(binPath);
        if (!bin.exists() || !isRB2(bin)) {
            System.out.println("references.bin ausente/incompativel — gerando RB2 do .gz (1x)...");
            build(gzPath, bin);
        }
        mmap(bin);
        System.out.println("dataset int8 RB2 mmap: " + count + " vetores ("
                + bin.length() + " bytes off-heap)");
    }

    public static int recBase(int i) { return HEADER + i * STRIDE; }
    public static boolean fraud(int i) { return data.get(lblBase + i) != 0; }

    // troca RB1->RB2 sozinho: se magic/dims não baterem, regenera
    private static boolean isRB2(File bin) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < HEADER) return false;
            byte[] m = new byte[4];
            r.readFully(m);
            return m[0] == MAGIC[0] && m[1] == MAGIC[1]
                    && m[2] == MAGIC[2] && m[3] == MAGIC[3]
                    && r.readInt() >= 0 && r.readInt() == DIMS;
        } catch (IOException e) {
            return false;
        }
    }

    private static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            for (int i = 0; i < 4; i++)
                if (m.get(i) != MAGIC[i]) throw new IOException("magic invalido (esperado RB2)");
            int c = m.getInt(4);
            int dims = m.getInt(8);
            if (dims != DIMS) throw new IOException("dims=" + dims + " (esperado 14)");
            count = c;
            lblBase = HEADER + count * STRIDE;
            data = m;
        }
    }

    private static void build(String gzPath, File bin) throws IOException {
        byte[] labels = new byte[1 << 20];
        int n = 0;
        byte[] rec = new byte[STRIDE];                    // [14],[15] ficam 0 sempre
        float[] f = new float[DIMS];

        try (RandomAccessFile raf = new RandomAccessFile(bin, "rw")) {
            raf.setLength(0);
            raf.write(MAGIC);                             // [0..3]
            raf.writeInt(0);                              // [4..7] count placeholder
            raf.writeInt(DIMS);                           // [8..11] dims = 14

            try (InputStream in = new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(gzPath), 1 << 16), 1 << 16)) {

                int c = skipTo(in, '[');
                if (c < 0) throw new IOException("dataset vazio / sem '['");

                while (true) {
                    c = nextNonWs(in);
                    if (c == ']' || c < 0) break;
                    if (c != '{') continue;

                    skipTo(in, '[');
                    for (int k = 0; k < DIMS; k++) f[k] = readFloat(in);
                    for (int k = 0; k < DIMS; k++) rec[k] = Quantizer.q(f[k]);
                    raf.write(rec);                       // 16 bytes (14 + 2 zero)

                    skipTo(in, '"'); skipTo(in, '"'); skipTo(in, '"');
                    int first = in.read();
                    skipTo(in, '"');
                    skipTo(in, '}');

                    if (n == labels.length) {
                        byte[] nl = new byte[labels.length << 1];
                        System.arraycopy(labels, 0, nl, 0, n);
                        labels = nl;
                    }
                    labels[n++] = (byte) (first == 'f' ? 1 : 0);
                    if ((n % 500_000) == 0) System.out.println("  quantizados " + n + "...");
                }
            }

            raf.write(labels, 0, n);
            raf.seek(4);
            raf.writeInt(n);
            raf.getFD().sync();
        }
    }

    private static int skipTo(InputStream in, int target) throws IOException {
        int b;
        while ((b = in.read()) != -1) if (b == target) return b;
        return -1;
    }
    private static int nextNonWs(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1)
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return b;
        return -1;
    }
    private static float readFloat(InputStream in) throws IOException {
        int b = nextNonWs(in);
        boolean neg = false;
        if (b == '-') { neg = true; b = in.read(); }
        double val = 0;
        while (b >= '0' && b <= '9') { val = val * 10 + (b - '0'); b = in.read(); }
        if (b == '.') {
            b = in.read();
            double sc = 0.1;
            while (b >= '0' && b <= '9') { val += (b - '0') * sc; sc *= 0.1; b = in.read(); }
        }
        return (float) (neg ? -val : val);
    }
}
```

> `rec` é reusado entre iterações; só `rec[0..13]` é escrito → `rec[14]`/`rec[15]` ficam 0
> para sempre. É exatamente o pad-zero que o SIMD precisa.

🔍 **Test point 1 — RB2 build**. Apague o `references.bin` antigo (RB1) **ou** deixe que a
auto-migração faça (1º boot 2b). Suba o servidor (§9). Espere no log:
`references.bin ausente/incompativel — gerando RB2…` → `quantizados 500000…3000000` →
`dataset int8 RB2 mmap: 3000000 vetores (51000012 bytes off-heap)`.
`ls -l src/main/resources/references.bin` → **51000012**.

🔍 **Test point 2 — 2º boot mmapeia**. Rode de novo: **sem** "gerando", direto
`dataset int8 RB2 mmap …`. `-Xmx128m` sem OOM.

---

## §5. `server/ConnectionState.java` — query de 16 + scratch

`queryQ` agora tem **16** (era 14) e ganha um irmão `vScratch` (buffer reusável p/ o vetor
do dataset, zero-alloc). Localize:

```java
    public final byte[] queryQ = new byte[14];
```

Troque por:

```java
    // Onda 2b: query e scratch quantizados padded-16 (14 reais + 2 zero p/ SIMD)
    public final byte[] queryQ   = new byte[16];
    public final byte[] vScratch = new byte[16];
```

`Quantizer.quantize` continua escrevendo só `[0..13]` → `[14]`/`[15]` ficam 0 (zero-init do
array, nunca tocados). **Não** limpe nenhum dos dois no `reset()` (totalmente sobrescritos
por request/candidato — mesma decisão do `queryVector`).

🔍 **Test point 3 — pad-zero**. Após `Quantizer.quantize(state.queryVector, state.queryQ)`,
`state.queryQ[14] == 0 && state.queryQ[15] == 0`.

---

## §6. `knn/DistanceFunctions.java` — SIMD (reescrito)

**Substitua todo o conteúdo.** Sai o `sqDistI8(byte[],MappedByteBuffer,int)` escalar da 2a
(não é mais usado — o `HnswIndex` passa a chamar a versão `(byte[],byte[])`). Entra o SIMD +
o `sqDistI8Scalar` de referência. `sqDist` float fica (regenerar baseline).

```java
package org.fraudDetection.knn;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class DistanceFunctions {

    private DistanceFunctions() {}

    /** Onda 1 float — mantido só p/ regenerar o baseline (não roda no Gate 2). */
    public static float sqDist(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < 14; i++) { float d = a[i] - b[i]; s += d * d; }
        return s;
    }

    private static final VectorSpecies<Byte>    B64  = ByteVector.SPECIES_64;   // 8 lanes
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;   // 8 lanes

    /**
     * Onda 2b SIMD: dist² int8 sobre 16 bytes (14 reais + 2 zero).
     * Alarga byte->int ANTES de subtrair (senão estoura): 2 chunks de 8 lanes.
     */
    public static int sqDistI8(byte[] q, byte[] v) {
        IntVector acc = IntVector.zero(I256);
        for (int off = 0; off < 16; off += 8) {                 // chunk 0..7 e 8..15
            ByteVector qb = ByteVector.fromArray(B64, q, off);
            ByteVector vb = ByteVector.fromArray(B64, v, off);
            IntVector qi = (IntVector) qb.convertShape(VectorOperators.B2I, I256, 0);
            IntVector vi = (IntVector) vb.convertShape(VectorOperators.B2I, I256, 0);
            IntVector d  = qi.sub(vi);
            acc = acc.add(d.mul(d));
        }
        return acc.reduceLanes(VectorOperators.ADD);
    }

    /** Referência escalar (oráculo do Gate A). 0..15 (pads 0 → mesma soma). */
    public static int sqDistI8Scalar(byte[] q, byte[] v) {
        int acc = 0;
        for (int k = 0; k < 16; k++) { int d = q[k] - v[k]; acc += d * d; }
        return acc;
    }
}
```

**Por que `convertShape(B2I, I256, 0)`?** `B64` tem 8 lanes de `byte` (64 bits); `I256` tem 8
lanes de `int` (256 bits). O `B2I` alarga cada `byte` (signed) para `int` **mantendo as 8
lanes** — `part = 0` cobre todas (contagens de lane iguais). Subtrair/quadrar em `int`
nunca estoura (`acc` máx ≈ 903k). As 2 lanes de pad são `(0-0)²=0`.

> **Pegadinha:** `qb.sub(vb)` em domínio `byte` está **ERRADO** (`-127-127` não cabe em
> `byte`). Alargue **primeiro**.

🔍 **Test point 4 — sintético**. `q = {10}×16`, `v = {-3}×16` (todos): `(10-(-3))² = 169`,
×16 lanes (pads também 13² aqui pois não são 0 neste teste sintético) → some à mão e
confira `sqDistI8 == sqDistI8Scalar`. Para o caso real os pads são 0 (Test point 3).

---

## §7. `knn/HnswIndex.java` — itera RB2 com bulk get + SIMD

**Substitua o `search`**. Mesma estrutura top-5; agora copia 16 bytes do mmap p/ `vScratch`
(get **absoluto**, não move position) e chama o SIMD.

```java
package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

import java.nio.MappedByteBuffer;

public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final byte[] q  = s.queryQ;
        final byte[] vs = s.vScratch;
        final int n = MmapDataset.count;
        final MappedByteBuffer V = MmapDataset.data;
        final float[]   bd = s.knnDist;
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            V.get(MmapDataset.recBase(i), vs, 0, 16);     // bulk get absoluto
            int d = DistanceFunctions.sqDistI8(q, vs);
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p - 1] > d) { bd[p] = bd[p - 1]; bf[p] = bf[p - 1]; p--; }
                bd[p] = d;
                bf[p] = MmapDataset.fraud(i);
            }
        }

        int fraud = 0;
        for (int k = 0; k < 5; k++) if (bf[k]) fraud++;
        s.fraudCount = fraud;
    }
}
```

> Build só fecha depois de §4–§7 aplicados juntos (DistanceFunctions e HnswIndex mudam de
> assinatura em par). Rode **`./mvnw clean package`** (limpo) — incremental mascara erro.

---

## §8. Gate A — SIMD == escalar (bit-exato)

Crie `api/src/test/java/org/fraudDetection/DistEquivI8.java`. Para os 2 oráculos quantizados,
compara `sqDistI8` (SIMD) com `sqDistI8Scalar` em **todos** os 3M registros.

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.DistanceFunctions;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DistEquivI8 {

    static final String LEGIT = "{\"id\":\"tx-1329056812\",\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-003\",\"MERC-016\"]},\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.23},\"last_transaction\":null}";
    static final String FRAUD = "{\"id\":\"tx-3330991687\",\"transaction\":{\"amount\":9505.97,\"installments\":10,\"requested_at\":\"2026-03-14T05:15:12Z\"},\"customer\":{\"avg_amount\":81.28,\"tx_count_24h\":20,\"known_merchants\":[\"MERC-008\",\"MERC-007\",\"MERC-005\"]},\"merchant\":{\"id\":\"MERC-068\",\"mcc\":\"7802\",\"avg_amount\":54.86},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":952.27},\"last_transaction\":null}";

    public static void main(String[] args) throws IOException {
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        boolean ok = check("legit", LEGIT) & check("fraud", FRAUD);
        System.out.println(ok ? "Gate A: SIMD == escalar -> PASS"
                              : "Gate A: DIVERGENCIA -> FAIL");
        if (!ok) System.exit(1);
    }

    static boolean check(String tag, String body) {
        ConnectionState s = new ConnectionState();
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        s.readBuffer.put(b);
        s.bodyOffset = 0;
        s.contentLength = b.length;
        if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK)
            throw new IllegalStateException("parse falhou: " + tag);
        Quantizer.quantize(s.queryVector, s.queryQ);

        int n = MmapDataset.count, diffs = 0;
        for (int i = 0; i < n; i++) {
            MmapDataset.data.get(MmapDataset.recBase(i), s.vScratch, 0, 16);
            int simd = DistanceFunctions.sqDistI8(s.queryQ, s.vScratch);
            int scal = DistanceFunctions.sqDistI8Scalar(s.queryQ, s.vScratch);
            if (simd != scal) {
                if (diffs < 5)
                    System.out.printf("  DIFF %s i=%d simd=%d scal=%d%n", tag, i, simd, scal);
                diffs++;
            }
        }
        System.out.printf("%s: %d divergencias em %d%n", tag, diffs, n);
        return diffs == 0;
    }
}
```

Rodar (de `fraudDetection/api/`):

```bash
./mvnw -q test-compile
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.DistEquivI8
# esperado: legit: 0 divergencias em 3000000 / fraud: 0 ... / Gate A: ... PASS
```

🔍 **Gate A verde** = `0 divergencias` nos 2. Qualquer `DIFF` = bug de shape/part no SIMD.

---

## §9. Gate 1 — §10 e2e (idêntico à 2a)

`./mvnw clean package` (exit 0). Suba (1º boot 2b regenera o `.bin` RB2):

```bash
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999
# "references.bin ausente/incompativel — gerando RB2..." -> "Listening on port 9999"

curl -s http://localhost:9999/ready -i | head -1     # HTTP/1.1 200 OK

curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# esperado: {"approved":true,"fraud_score":0.0}

curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# esperado: {"approved":false,"fraud_score":1.0}
```

`ls -l src/main/resources/references.bin` → **51000012**. Bateu os 2 + `/ready` 200 +
`-Xmx256m` sem OOM → **Gate 1 verde**.

---

## §10. Gate 2 — regressão-exata vs baseline congelado

Reusa o `Gate2Int8.java` da 2a (inalterado — passa por `HnswIndex.search`). O baseline
`docs/baselines/onda1-approved-2000.txt` **não muda**.

```bash
./mvnw -q test-compile
java -Xmx256m -cp target/classes:target/test-classes org.fraudDetection.Gate2Int8 2000
# esperado: Gate 2: 1995/2000 agreement = 99.75% (FP=2 FN=3) -> PASS
```

🔍 **Gate 2 verde** = **exatamente** `1995/2000 = 99.75% (FP=2 FN=3)` — o **mesmo** número
da Onda 2a. RB2 = RB1 + pad zero ⇒ mesmos int8 ⇒ mesmas decisões. **Qualquer** outro número
= bug no SIMD/RB2 (não é "≥99%", é igualdade exata com a 2a).

---

## §11. Gate 3 — p99 escalar vs SIMD (medição)

Crie `api/src/test/java/org/fraudDetection/BenchSearch.java`. Hand-rolled (sem JMH):
pré-quantiza N queries, faz warmup, mede `nanoTime` por busca completa em **escalar** e
**SIMD** no mesmo JVM.

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.DistanceFunctions;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BenchSearch {

    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");

        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");
        byte[][] qs = new byte[N][];
        int m = 0;
        TestDataReader.Entry e;
        while (m < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b); s.bodyOffset = 0; s.contentLength = b.length;
            if (FraudRequestParser.parse(s) == FraudRequestParser.PARSE_OK) {
                Quantizer.quantize(s.queryVector, s.queryQ);
                qs[m] = Arrays.copyOf(s.queryQ, 16);
            }
            m++;
        }

        warmup(qs, m);
        long[] scal = measure(qs, m, false);
        long[] simd = measure(qs, m, true);
        report("ESCALAR", scal);
        report("SIMD   ", simd);
        System.out.printf("speedup p50 (escalar/SIMD) = %.2fx%n",
                pctl(scal, 50) / (double) pctl(simd, 50));
    }

    static int searchFraud(byte[] q, byte[] vs, boolean simd) {
        int n = MmapDataset.count;
        float[] bd = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                      Float.MAX_VALUE, Float.MAX_VALUE};
        boolean[] bf = new boolean[5];
        for (int i = 0; i < n; i++) {
            MmapDataset.data.get(MmapDataset.recBase(i), vs, 0, 16);
            int d = simd ? DistanceFunctions.sqDistI8(q, vs)
                         : DistanceFunctions.sqDistI8Scalar(q, vs);
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p - 1] > d) { bd[p] = bd[p-1]; bf[p] = bf[p-1]; p--; }
                bd[p] = d; bf[p] = MmapDataset.fraud(i);
            }
        }
        int f = 0; for (int k = 0; k < 5; k++) if (bf[k]) f++; return f;
    }

    static void warmup(byte[][] qs, int m) {
        byte[] vs = new byte[16];
        for (int r = 0; r < 3; r++)
            for (int i = 0; i < Math.min(m, 150); i++)
                if (qs[i] != null) { searchFraud(qs[i], vs, true); searchFraud(qs[i], vs, false); }
    }
    static long[] measure(byte[][] qs, int m, boolean simd) {
        byte[] vs = new byte[16];
        long[] t = new long[m]; int c = 0;
        for (int i = 0; i < m; i++) {
            if (qs[i] == null) continue;
            long a = System.nanoTime();
            searchFraud(qs[i], vs, simd);
            t[c++] = System.nanoTime() - a;
        }
        return Arrays.copyOf(t, c);
    }
    static long pctl(long[] v, int p) {
        long[] s = v.clone(); Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(p / 100.0 * s.length) - 1)];
    }
    static void report(String tag, long[] t) {
        long sum = 0; for (long x : t) sum += x;
        System.out.printf("%s  n=%d  p50=%.3fms  p99=%.3fms  media=%.3fms%n",
                tag, t.length, pctl(t,50)/1e6, pctl(t,99)/1e6, sum/(double)t.length/1e6);
    }
}
```

Rodar:

```bash
./mvnw -q test-compile
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.BenchSearch 2000
# ESCALAR  n=.. p50=.. p99=.. media=..
# SIMD     n=.. p50=.. p99=.. media=..
# speedup p50 (escalar/SIMD) = X.XXx
```

> **Gate 3 é medição, não pass/fail.** Não há alvo absoluto aqui — p99 < 1ms é Onda 4/5
> (Native Image); brute-force 3M não fica sub-ms. Anote o **speedup**; o aprendizado é
> "SIMD mais rápido **com decisões idênticas**" (Gate A/2 garantem o "idêntico").

---

## §12. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| `byte` signed | Alargue B2I **antes** de subtrair; `acc` em `int` | §2/§6 |
| `sub` em byte | `qb.sub(vb)` em `ByteVector` estoura — sempre widen primeiro | §6 |
| Pad-zero | `queryQ`/`rec` `[14..15]=0`; Quantizer só escreve `[0..13]` | §4/§5 |
| `convertShape` part | `B64`(8)→`I256`(8): mesmas lanes, `part=0` | §6 |
| Espécie fixa | `SPECIES_64`/`SPECIES_256` (AVX2); **não** `SPECIES_PREFERRED` | §2 |
| Auto-migração | `load()` regenera se magic≠RB2 — não apague o .bin à mão | §3/§4 |
| `get` absoluto | `V.get(idx,dst,0,16)` **não** move `position` (Java 13+) | §7 |
| Build limpo | §4–§7 mudam em par → `./mvnw clean package` (não incremental) | §7 |
| Gate 2 exato | Tem de ser **1995/2000**, não "≥99%" (RB2≡RB1+pad) | §10 |
| Sem preview | **Não** use `java.lang.foreign`/`--enable-preview`; pom intacto | §2 |
| Native Image | Vector API pode cair p/ escalar silencioso na Onda 5 — validar com `-Dgraal.PrintCompilation` | §13 |

---

## §13. Próximos passos

**Onda 2b fechada** = Gate A (SIMD==escalar) + Gate 1 (2 oráculos) + Gate 2 (=1995/2000) +
Gate 3 (p99 medido) verdes, dataset RB2 off-heap, heap em `-Xmx256m`.

- **Onda 3 — HNSW** hand-rolled (`docs/TUTORIAL_HNSW.md` ✅ criado): grafo navegável, recall ≥95%
  vs o baseline brute-force. Deixa de varrer os 3M por request.
- **Onda 4** — conteinerização + k6 oficial (limite 350 MB; o jar hoje empacota o `.gz` —
  resolver lá). **Onda 5** — GraalVM Native Image + PGO; **revalidar Gate A** (regressão
  silenciosa do Vector API p/ escalar em Native Image — `-Dgraal.PrintCompilation`).

---

**Cada Gate é uma vitória.** 🏁
