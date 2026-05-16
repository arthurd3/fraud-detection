# Tutorial — Onda 2a: quantização int8 + binário mmap

> De **Onda 1 fechada** (`POST /fraud-score` correto, `float[3M][14]` ~220 MB no heap)
> → **mesmo resultado** com dataset **int8 off-heap via `MappedByteBuffer`** (~45 MB), heap sem o array gigante.
> **Tempo estimado**: 4-8h. **Pré-requisito absoluto**: Onda 1 fechada (commit `43c2a65`, os 2 oráculos do §10 verdes, `./mvnw clean package` OK).
> Spec: `docs/superpowers/specs/2026-05-16-onda2a-int8-quant-design.md`.

---

## §0. Visão geral, o que muda, critério de saída

A Onda 1 funciona mas não cabe na Rinha: **1 CPU + 350 MB RAM total** p/ ≥2 instâncias. `float[3_000_000][14]` ≈ 220 MB de heap **por instância** → estoura. A Onda 2a troca o dataset float-heap por **int8 num arquivo binário mapeado** (`MappedByteBuffer`, off-heap, ~45 MB, compartilhável pelo page cache), mantendo o **mesmo `{approved, fraud_score}`**.

> **Onda 2a é só correção + memória.** SIMD (Vector API) e latência p99 ficam para a **Onda 2b** (`TUTORIAL_SIMD.md`, futuro). A distância aqui continua **escalar**.

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/Quantizer.java` | **novo** — float→int8 global simétrico |
| 2 | `dataset/MmapDataset.java` | **reescrito v2** — self-bootstrapping mmap (reusa o parser byte-a-byte da Onda 1) |
| 3 | `knn/DistanceFunctions.java` | **+`sqDistI8`** (mantém `sqDist` float) |
| 4 | `knn/HnswIndex.java` | **v2** — itera o `MappedByteBuffer` int8 |
| 5 | `server/ConnectionState.java` | **+`byte[14] queryQ`** (scratch zero-alloc) |
| 6 | `controllers/FraudController.java` | **+quantiza** a query antes do KNN |
| 7 | `Main.java` | `MmapDataset.load(gz, bin)` |
| 8 | `api/.gitignore` | **+`src/main/resources/references.bin`** |
| 9 | `docs/baselines/onda1-approved-2000.txt` | **novo** — baseline float congelado (commitado) |

### Critério de saída da Onda 2a

- **Gate 1 (bloqueia):** §10 e2e idêntico à Onda 1 — `/ready` 200; `tx-1329056812`→`{"approved":true,"fraud_score":0.0}`; `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`.
- **Gate 2 (sanity):** `approved_int8 == approved_baseline` ≥ **99%** nas **primeiras 2.000** entradas do `test-data.json`, contra o **baseline float congelado da Onda 1**; relatório de FP/FN introduzidos.
- Dataset rodando **off-heap** (`MappedByteBuffer`), sem `float[3M][14]` no heap.

---

## §1. Mapa mental: o que muda no fluxo

```
BOOT (1ª vez):  references.json.gz --stream--> quantiza --> grava references.bin --> mmap
BOOT (2ª vez+): references.bin --mmap--> pronto (sem parse, ~instantâneo)

POST /fraud-score:
  FraudRequestParser.parse  -> state.queryVector[14] (float)   (igual Onda 1)
  Quantizer.quantize        -> state.queryQ[14] (int8)         (NOVO)
  HnswIndex.search          -> top-5 sobre o MappedByteBuffer int8 (sqDistI8)
  state.fraudCount -> 1 das 6 respostas canned                 (igual Onda 1)
```

Só a **representação do dataset** e o **cálculo de distância** mudam. Parser de request, fórmula 14D, respostas canned, servidor NIO — **intactos**.

---

## §2. Princípios (lembrete + 1 exceção)

Os mesmos da Onda 1, mais:

1. **`byte` é signed em Java.** Aqui a quantização é **simétrica** (`[-127,127]`), então a subtração `q[k] - V.get(...)` funciona direto **sem `& 0xFF`**. (Onda 2b/quant assimétrica exigiria `& 0xFF` — não é o caso aqui.)
2. **Soma de quadrados em `int`.** 14 termos, cada `≤ 254² = 64.516`; total `≤ 903.224` `<< 2³¹`. `acc` é `int`, nunca `byte`/`short`.
3. **Build do `.bin` NÃO é hot path.** Roda 1× no 1º boot. Pode ser "lento" (segundos). Streaming, sem carregar 284 MB em `String`.
4. **Harness de teste pode usar `String`/JDK à vontade** — não é a aplicação. A regra by-hand vale só pro código de produção (parser de request, KNN).

---

## §3. ⚠️ PRIMEIRO de tudo: congelar o baseline float da Onda 1

> **Faça esta seção ANTES de tocar em qualquer `.java` da Onda 2.** O baseline precisa ser capturado com o código **float da Onda 1 intacto** (commit `43c2a65`). Depois que você reescrever o `MmapDataset` (v2 int8), não dá mais pra rodar o float.

O `test-data.json` (`rinha-de-backend-2026/test/`) é `{"references_checksum_sha256":…,"stats":…,"entries":[{"request":{…},"expected_approved":bool,"expected_fraud_score":n}, …]}` (54.100). O `request` tem o **mesmo schema** do body do POST.

### `TestDataReader` (utilitário de teste — `String` liberado)

Crie `api/src/test/java/org/fraudDetection/TestDataReader.java`:

```java
package org.fraudDetection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lê test-data.json. NÃO é código de produção — String/JDK liberados. */
final class TestDataReader {
    private final String all;
    private int pos;

    TestDataReader(String path) throws IOException {
        all = Files.readString(Path.of(path));      // ~26 MB String, ok p/ teste
        pos = all.indexOf("\"entries\"");
    }

    static final class Entry { String body; boolean expected; }

    /** Próxima entrada ou null. body = JSON do objeto "request" (= body do POST). */
    Entry next() {
        int r = all.indexOf("\"request\"", pos);
        if (r < 0) return null;
        int objStart = all.indexOf('{', r);
        int objEnd   = matchBrace(all, objStart);   // índice do '}' casado
        int ea       = all.indexOf("\"expected_approved\"", objEnd);
        int colon    = all.indexOf(':', ea);
        boolean exp  = all.regionMatches(true, firstNonWs(all, colon + 1), "true", 0, 4);
        Entry e = new Entry();
        e.body     = all.substring(objStart, objEnd + 1);
        e.expected = exp;
        pos = objEnd + 1;
        return e;
    }

    private static int firstNonWs(String s, int i) {
        while (i < s.length()) { char c = s.charAt(i); if (c!=' '&&c!='\t'&&c!='\r'&&c!='\n') return i; i++; }
        return i;
    }
    /** Casa '{' .. '}'. Seguro: os valores-string do payload não têm '{' nem '}'. */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return s.length() - 1;
    }
}
```

### Harness do baseline (usa o código **float** da Onda 1)

Crie `api/src/test/java/org/fraudDetection/BaselineOnda1.java`. Reusa `MmapDataset.load(gz)` **float da Onda 1**, `FraudRequestParser`, `HnswIndex` float:

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Roda Onda 1 FLOAT nas primeiras N entradas; grava docs/baselines/onda1-approved-2000.txt */
public final class BaselineOnda1 {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        MmapDataset.load("src/main/resources/references.json.gz");   // Onda 1 (1 arg, float)

        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");
        StringBuilder out = new StringBuilder();
        int n = 0, agreeExpected = 0;

        TestDataReader.Entry e;
        while (n < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] body = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(body);
            s.bodyOffset = 0;
            s.contentLength = body.length;

            boolean approved;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) {
                approved = true;                       // Onda 1: payload ruim -> score 0
            } else {
                HnswIndex.search(s);
                approved = s.fraudCount < 3;           // score < 0.6
            }
            if (approved == e.expected) agreeExpected++;
            out.append(n).append(' ').append(approved ? 1 : 0).append('\n');
            n++;
            if (n % 500 == 0) System.out.println("  baseline " + n + "...");
        }

        Path p = Path.of("../docs/baselines/onda1-approved-" + N + ".txt");
        Files.createDirectories(p.getParent());
        Files.writeString(p, out.toString());
        System.out.printf("baseline gravado: %s (%d linhas). Concordância vs expected_approved: %.2f%%%n",
                p, n, 100.0 * agreeExpected / n);
    }
}
```

Rodar (de `fraudDetection/api/`, **com o código Onda 1 ainda intacto**):

```bash
./mvnw -q test-compile
java -Xmx768m -cp target/classes:target/test-classes org.fraudDetection.BaselineOnda1 2000
# espere: baseline gravado: ../docs/baselines/onda1-approved-2000.txt (2000 linhas). Concordância vs expected_approved: ~9X%
```

> A linha "Concordância vs `expected_approved`" é um sanity do **próprio brute-force float** vs o ground-truth oficial da Rinha. Anote o número — a Onda 2a int8 deve ficar **bem perto** dele.

Commit o baseline (ele é o oráculo congelado):

```bash
git add ../docs/baselines/onda1-approved-2000.txt && git commit -m "chore: baseline float Onda 1 (2000) p/ Gate 2 da Onda 2a"
```

🔍 **Test point 0 — baseline existe**: `wc -l ../docs/baselines/onda1-approved-2000.txt` → `2000`. **Só agora** prossiga pras mudanças int8.

---

## §4. Formato `references.bin`

```
[ header 12B ]  magic 'R','B','1',0 (4B) · int32 count (big-endian) · int32 dims (=14)
[ vetores    ]  count × 14  int8   (row-major, contíguo)
[ labels     ]  count × 1   byte   (0 = legit, 1 = fraud)
```

- `vec(i,d) = 12 + i*14 + d` · `label(i) = 12 + count*14 + i`.
- Header big-endian: escrito com `RandomAccessFile.writeInt`, lido com `MappedByteBuffer.getInt` (ambos big-endian — default do `ByteBuffer`). **Não misture com `ByteBuffer.order(LITTLE_ENDIAN)`.**
- Tamanho p/ 3M: `12 + 3_000_000*14 + 3_000_000 = 45_000_012` bytes.

---

## §5. `knn/Quantizer.java` (novo)

```java
package org.fraudDetection.knn;

public final class Quantizer {

    private Quantizer() {}

    /** clamp em [-1,1] (defende drift; dims 5,6 podem ser -1 sentinela). */
    static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }

    /** Global simétrica: [-1,1] -> int8 [-127,127]. -1.0 -> -127 ; 0 -> 0 ; 1.0 -> 127. */
    public static byte q(float v) {
        return (byte) Math.round(clamp(v) * 127f);
    }

    /** Quantiza src[14] -> dst[14] (dst pré-alocado: zero-alloc). */
    public static void quantize(float[] src, byte[] dst) {
        for (int i = 0; i < 14; i++) dst[i] = q(src[i]);
    }
}
```

`Math.round(float)` → `int`; o cast `(byte)` é seguro porque o valor está em `[-127,127]`.

🔍 **Test point 1 — Quantizer**. Temporário num `main`:

```java
System.out.println(Quantizer.q(0f));     // 0
System.out.println(Quantizer.q(1f));     // 127
System.out.println(Quantizer.q(-1f));    // -127
System.out.println(Quantizer.q(0.5f));   // 64   (round(63.5))
System.out.println(Quantizer.q(1.5f));   // 127  (clamp)
System.out.println(Quantizer.q(-1.5f));  // -127 (clamp)
```

Apague depois.

---

## §6. `dataset/MmapDataset.java` v2 (reescrito — self-bootstrapping)

**Substitua todo o conteúdo** do `MmapDataset.java`. Reusa os helpers de stream da Onda 1 (`skipTo/nextNonWs/readFloat`), agora pra **quantizar e gravar** em vez de guardar em `float[][]`.

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
 * Onda 2a: dataset int8 num arquivo binário mapeado (off-heap).
 * 1º boot: stream do .gz -> quantiza -> grava references.bin -> mmap.
 * 2º boot+: mmap direto (sem parse). RO depois do load -> sem sync.
 */
public final class MmapDataset {

    public static final int DIMS = 14;
    private static final int HEADER = 12;                 // magic(4)+count(4)+dims(4)
    private static final byte[] MAGIC = {'R', 'B', '1', 0};

    public static MappedByteBuffer data;                  // o .bin inteiro (off-heap)
    public static int count;
    public static int lblBase;                            // HEADER + count*DIMS

    private MmapDataset() {}

    public static void load(String gzPath, String binPath) throws IOException {
        File bin = new File(binPath);
        if (!bin.exists()) {
            System.out.println("references.bin ausente — gerando do .gz (1x, ~segundos)...");
            build(gzPath, bin);
        }
        mmap(bin);
        System.out.println("dataset int8 mmap: " + count + " vetores ("
                + bin.length() + " bytes off-heap)");
    }

    public static int recBase(int i) { return HEADER + i * DIMS; }
    public static boolean fraud(int i) { return data.get(lblBase + i) != 0; }

    // ---- mmap do .bin existente ----
    private static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            for (int i = 0; i < 4; i++)
                if (m.get(i) != MAGIC[i]) throw new IOException("magic invalido em " + bin);
            int c = m.getInt(4);                          // big-endian (default)
            int dims = m.getInt(8);
            if (dims != DIMS) throw new IOException("dims=" + dims + " (esperado 14)");
            count = c;
            lblBase = HEADER + count * DIMS;
            data = m;
        }
        // FileChannel fechado: o mapeamento sobrevive.
    }

    // ---- build: stream .gz -> quantiza -> grava .bin ----
    private static void build(String gzPath, File bin) throws IOException {
        byte[] labels = new byte[1 << 20];                // cresce dobrando (transitório)
        int n = 0;
        byte[] vec = new byte[DIMS];
        float[] f = new float[DIMS];

        try (RandomAccessFile raf = new RandomAccessFile(bin, "rw")) {
            raf.setLength(0);
            raf.write(MAGIC);                             // [0..3]
            raf.writeInt(0);                              // [4..7] count placeholder
            raf.writeInt(DIMS);                           // [8..11]

            try (InputStream in = new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(gzPath), 1 << 16), 1 << 16)) {

                int c = skipTo(in, '[');
                if (c < 0) throw new IOException("dataset vazio / sem '['");

                while (true) {
                    c = nextNonWs(in);
                    if (c == ']' || c < 0) break;
                    if (c != '{') continue;               // ',' ou ws entre objetos

                    skipTo(in, '[');                      // abre "vector"
                    for (int k = 0; k < DIMS; k++) f[k] = readFloat(in);
                    for (int k = 0; k < DIMS; k++) vec[k] = Quantizer.q(f[k]);
                    raf.write(vec);                       // 14 int8 contíguos

                    skipTo(in, '"'); skipTo(in, '"'); skipTo(in, '"'); // ..,"label":"
                    int first = in.read();                // 'f' (fraud) | 'l' (legit)
                    skipTo(in, '"');                      // fecha valor
                    skipTo(in, '}');                      // fecha objeto

                    if (n == labels.length) {
                        byte[] nl = new byte[labels.length << 1];
                        System.arraycopy(labels, 0, nl, 0, n);
                        labels = nl;
                    }
                    labels[n++] = (byte) (first == 'f' ? 1 : 0);
                    if ((n % 500_000) == 0) System.out.println("  quantizados " + n + "...");
                }
            }

            raf.write(labels, 0, n);                      // região de labels
            raf.seek(4);
            raf.writeInt(n);                              // corrige count (big-endian)
            raf.getFD().sync();
        }
    }

    // ---- helpers de stream (reusados da Onda 1, sem String/regex) ----
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

### Detalhes / pegadinhas

- **Assinatura mudou**: `load(String gz, String bin)` (era `load(String gz)`). Ajuste o `Main` (§7).
- O `GZIPInputStream` aqui é incondicional (o build sempre lê o `.gz` real). O auto-detect da Onda 1 não é necessário no build (o `example-references.json` plano era só pros test points da Onda 1).
- `raf.seek(4); raf.writeInt(n)` corrige o `count` (não sabíamos antes de parsear) — zero buffer de 42 MB em heap; só os labels (`~3 MB`) ficam transitórios.
- `m.getInt(4)` usa **big-endian** (default do `ByteBuffer`), casando com `RandomAccessFile.writeInt`.
- `count * DIMS` p/ 3M = 42M `<` 2³¹ → `MappedByteBuffer` (limite `int`) ok.

🔍 **Test point 2 — 1º boot gera o .bin**. Apague qualquer `references.bin` e rode o servidor (§7/§8). Espere no log: `references.bin ausente — gerando…` → `quantizados 500000…3000000…` → `dataset int8 mmap: 3000000 vetores (45000012 bytes off-heap)`. `ls -l src/main/resources/references.bin` → **45000012**.

🔍 **Test point 3 — 2º boot mmapeia**. Rode de novo. Log: **sem** "ausente/quantizados", direto `dataset int8 mmap: 3000000 …` (instantâneo). Heap: rode com `-Xmx128m` — **não** dá OOM (sem `float[3M][14]`).

---

## §7. `DistanceFunctions` + `HnswIndex` v2 + `ConnectionState` + `FraudController` + `Main`

### `knn/DistanceFunctions.java` — adicione `sqDistI8` (mantenha `sqDist`)

```java
import java.nio.MappedByteBuffer;

/** Euclidiana² int8, 14 dims. q simétrico [-127,127] -> SEM &0xFF. acc int32. */
public static int sqDistI8(byte[] q, MappedByteBuffer V, int base) {
    int acc = 0;
    for (int k = 0; k < 14; k++) {
        int d = q[k] - V.get(base + k);
        acc += d * d;
    }
    return acc;
}
```

> Mantenha o `sqDist(float[],float[])` da Onda 1 — ele só é usado se você regenerar o baseline (§3); não roda no Gate 2.

### `knn/HnswIndex.java` v2 — itera o mmap int8

**Substitua o `search`** (mesma estrutura top-5, fonte agora é o `MappedByteBuffer`):

```java
package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

import java.nio.MappedByteBuffer;

public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final byte[] q = s.queryQ;
        final int n = MmapDataset.count;
        final MappedByteBuffer V = MmapDataset.data;
        final float[]   bd = s.knnDist;      // float[5] da Onda 1 (reusado; dist int cabe exata)
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            int d = DistanceFunctions.sqDistI8(q, V, MmapDataset.recBase(i));
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p - 1] > d) { bd[p] = bd[p - 1]; bf[p] = bf[p - 1]; p--; }
                bd[p] = d;                    // int -> float (exato p/ < 2^24)
                bf[p] = MmapDataset.fraud(i);
            }
        }

        int fraud = 0;
        for (int k = 0; k < 5; k++) if (bf[k]) fraud++;
        s.fraudCount = fraud;
    }
}
```

### `server/ConnectionState.java` — adicione o scratch quantizado

Depois de `public final float[] queryVector = new float[14];`:

```java
    // Onda 2a: query quantizada int8 (preenchida por Quantizer todo request, zero-alloc)
    public final byte[] queryQ = new byte[14];
```

**Não** limpe `queryQ` no `reset()` — é totalmente sobrescrito por request (mesma decisão do `queryVector`).

### `controllers/FraudController.java` — quantize antes do KNN

```java
import org.fraudDetection.knn.Quantizer;
...
        if (FraudRequestParser.parse(state) != FraudRequestParser.PARSE_OK) {
            state.fraudCount = 0;
        } else {
            Quantizer.quantize(state.queryVector, state.queryQ);   // NOVO
            HnswIndex.search(state);
        }
```

### `Main.java` — passe os 2 caminhos

```java
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
```

### `api/.gitignore` — não versionar o .bin

```
### Rinha dataset int8 (local, regenerável) ###
src/main/resources/references.bin
```

---

## §8. Gate 1 — §10 e2e (idêntico à Onda 1)

`./mvnw clean package` (deve dar exit 0). Suba o servidor (1º boot gera o `.bin`):

```bash
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999
# 1º boot: "references.bin ausente — gerando..." depois "Listening on port 9999"
```

Outro terminal — os **2 oráculos do `REGRAS_DE_DETECCAO.md`** (mesmos da Onda 1 §10):

```bash
curl -s http://localhost:9999/ready -i | head -1     # HTTP/1.1 200 OK

curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# esperado: {"approved":true,"fraud_score":0.0}

curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# esperado: {"approved":false,"fraud_score":1.0}
```

Os 2 são 0/5 e 5/5 (longe do limiar 0.6) → robustos a quantização. Bateu os 2 + `/ready` 200 → **Gate 1 verde**. (Rode com `-Xmx256m` pra provar que o heap não tem mais o array gigante.)

> **Gate 1 ≠ Gate 2 data.** Os payloads acima são do `REGRAS_DE_DETECCAO.md` (oráculo oficial da fórmula). O `test-data.json` reusa alguns `id` com valores diferentes — não confunda.

---

## §9. Gate 2 — agreement int8 vs baseline float congelado

Crie `api/src/test/java/org/fraudDetection/Gate2Int8.java`. Roda as **mesmas N=2.000** pelo pipeline **int8** e compara com `docs/baselines/onda1-approved-2000.txt`:

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Gate2Int8 {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");        // v2 int8

        List<String> base = Files.readAllLines(
                Path.of("../docs/baselines/onda1-approved-" + N + ".txt"));
        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");

        int n = 0, agree = 0, fp = 0, fn = 0;
        TestDataReader.Entry e;
        while (n < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] body = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(body);
            s.bodyOffset = 0;
            s.contentLength = body.length;

            boolean approved;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) {
                approved = true;
            } else {
                Quantizer.quantize(s.queryVector, s.queryQ);
                HnswIndex.search(s);
                approved = s.fraudCount < 3;
            }
            boolean baseApproved = base.get(n).endsWith(" 1");
            if (approved == baseApproved) agree++;
            else if (approved && !baseApproved) fp++;     // int8 aprovou; float negou
            else fn++;                                     // int8 negou; float aprovou
            n++;
        }
        double pct = 100.0 * agree / n;
        System.out.printf("Gate 2: %d/%d agreement = %.2f%% (FP=%d FN=%d) -> %s%n",
                agree, n, pct, fp, fn, pct >= 99.0 ? "PASS" : "FAIL");
        if (pct < 99.0) System.exit(1);
    }
}
```

Rodar (de `fraudDetection/api/`):

```bash
./mvnw -q test-compile
java -Xmx256m -cp target/classes:target/test-classes org.fraudDetection.Gate2Int8 2000
# esperado: Gate 2: ~19XX/2000 agreement = 9X.XX% (FP=.. FN=..) -> PASS
```

**Gate 2 verde** = `≥ 99.00%`. Apague `BaselineOnda1.java`, `Gate2Int8.java`, `TestDataReader.java` depois (ou deixe em `src/test/` — não vão pro `target/api.jar`). O baseline `.txt` **fica commitado**.

> Full 54.100: regenere o baseline com `BaselineOnda1 54100` (commit `43c2a65` checkout se já mexeu no float) e rode `Gate2Int8 54100`. Demora (3M brute-force × 54.100) — opcional/offline.

---

## §10. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| Ordem | Capture o baseline float **ANTES** de reescrever o `MmapDataset` | §3 |
| `byte` signed | Quant simétrica `[-127,127]` → subtração direta, **sem `&0xFF`** | §2/§7 |
| Overflow | `acc` da distância é `int` (máx ~903k); nunca `byte`/`short` | §2/§7 |
| Endian | Header `writeInt`/`getInt` ambos big-endian; não setar `LITTLE_ENDIAN` | §4/§6 |
| Assinatura | `MmapDataset.load(gz, bin)` — ajuste o `Main` | §6/§7 |
| `count` | `seek(4); writeInt(n)` corrige o placeholder pós-parse | §6 |
| `knnDist` | Continua `float[5]` (NÃO mude — `ConnectionState` é compartilhado) | §7 |
| `.bin` | Vai no `.gitignore` (regenerável, ~45 MB) | §7 |
| Gate1≠Gate2 | Oráculos §10 (REGRAS) ≠ entradas do `test-data.json` | §8 |
| v1 float | Não delete `sqDist` float — regenera baseline | §3/§7 |
| Escopo | 2a é **escalar**; SIMD só na 2b | §0 |

---

## §11. Próximos passos

**Onda 2a fechada** = Gate 1 (2 oráculos) + Gate 2 (≥99%) verdes, dataset int8 off-heap, heap roda em `-Xmx256m`.

- **Onda 2b — SIMD** (`TUTORIAL_SIMD.md`, a criar): `DistanceFunctions.sqDistI8` v2 com `jdk.incubator.vector` (`ByteVector`/`ShortVector`, AVX2). Validar **mesmos** `{approved, fraud_score}` da 2a + medir p99. Pegadinha: regressão silenciosa pra escalar em Native Image (Onda 5) — validar com `-Dgraal.PrintCompilation`.
- **Onda 3 — HNSW** hand-rolled, recall ≥95% vs o baseline brute-force.
- **Onda 4** — conteinerização + k6 oficial. **Onda 5** — GraalVM Native Image + PGO.

---

**Cada Gate é uma vitória.** 🏁
