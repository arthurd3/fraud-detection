# Tutorial — JSON parser + dataset + KNN (fecha a Onda 1)

> De `curl /ready` → `200 OK` até `curl POST /fraud-score` retornando `{approved, fraud_score}`
> **correto vs `example-references.json`**, em Java by-hand, codando junto.
> **Tempo estimado**: 2-3 dias se nunca fez parser/KNN; 6-10h se já fez algo similar.
> **Pré-requisito absoluto**: `docs/TUTORIAL_SERVER_NIO.md` fechado (`/ready` confirmado 200 OK).

---

## §0. Pré-requisitos, limpeza e visão geral em 30s

### Limpeza antes de começar (BLOQUEADORES — não pule)

A Onda 1 parcial deixou 4 pendências que **quebram** o `/fraud-score` se não forem resolvidas:

| # | Problema | Onde | Fix |
|---|---|---|---|
| 1 | **Bug no `matchMethod`**: `buf.get(start+2) == 'T'` (testa `start+2` duas vezes) | `server/HttpParser.java` ~linha 110 | Trocar por `buf.get(start+3) == 'T'`. **Sem isso `METHOD_POST` NUNCA é detectado** → todo POST vira `PARSE_ERROR` → conexão fechada. |
| 2 | Typo `HttpResponserWriter` (arquivo + classe) | `server/HttpResponserWriter.java` | Renomear classe+arquivo → `HttpResponseWriter` (IntelliJ: Shift+F6 na classe). Ajustar a chamada em `HealthController`. O `writeFraudScore` mora aqui. |
| 3 | `HealthController` está em `server/` | `server/HealthController.java` | Mover para `controllers/` (ajustar `package org.fraudDetection.controllers;` + a chamada em `NioServer.dispatch()` → `org.fraudDetection.controllers.HealthController`). `FraudController` vai pra `controllers/` também — ficam juntos. |
| 4 | `Main.java:10-13` tem prints de test point temporários | `Main.java` | Apagar as 3 linhas `System.out.println(...)` + a linha `ConnectionState s = new ConnectionState();`. |

Bônus de correção (faça junto com o #1, mesmo arquivo): `ConnectionState.reset()` não reseta `headerNameEnd`. Adicione `headerNameEnd = -1;` no `reset()` (§3 detalha).

### Pegar os arquivos de dados

O dataset e as constantes **não estão no projeto** — só em `rinha-de-backend-2026/resources/`. Copie:

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
mkdir -p src/main/resources
cp ../../rinha-de-backend-2026/resources/references.json.gz   src/main/resources/
cp ../../rinha-de-backend-2026/resources/normalization.json   src/main/resources/
cp ../../rinha-de-backend-2026/resources/mcc_risk.json         src/main/resources/
ls -lh src/main/resources/   # references.json.gz ~48M
```

**⚠️ NÃO commite o `references.json.gz` (48 MB).** Adicione ao fim de `api/.gitignore`:

```
### Rinha dataset (local, não versionar) ###
src/main/resources/references.json.gz
```

(`normalization.json` e `mcc_risk.json` são minúsculos — pode commitar, mas neste tutorial os valores são hard-coded como constantes, então nem são lidos em runtime na Onda 1.)

### Pré-leituras

- 📖 `docs/CONCEITOS.md` §1 (Vector Search e k-NN) — distância, brute force, por que top-5
- 📖 `docs/CONCEITOS.md` §8 (mmap e Page Cache) — contexto pro Onda 2 (aqui carregamos em heap, v1)
- 📖 `docs/RINHA_PLAN.md` §1.4 (Vetorização 14 dims), §8.4 (parse JSON + vetorização), §9.1 (Onda 1)
- 📖 `rinha-de-backend-2026/docs/br/REGRAS_DE_DETECCAO.md` — **a fonte da verdade** da fórmula 14D + 2 exemplos numéricos completos (usamos como oráculo nos test points)

### O que vamos construir, em 1 minuto

```
            POST /fraud-score (body JSON na readBuffer)
                       |
   HttpParser ── bodyOffset/contentLength (já feito na Onda 1 parcial)
                       |
   FraudRequestParser.parse(state) ──► state.queryVector[14]   (sem objeto intermediário)
                       |
   HnswIndex.search(state) ──► top-5 menores distâncias (brute force 3M)
                       |        DistanceFunctions.sqDist (euclidiana²)
                       |
   state.fraudCount (0..5) → fraud_score = fraudCount/5 ; approved = score < 0.6
                       |
   HttpResponseWriter.writeFraudScore(state, fraudCount) ──► 1 das 6 respostas canned
                       |
                  OP_WRITE → drena → keep-alive (reset)
```

São **5 arquivos novos** + 3 modificações:

| # | Arquivo | Função |
|---|---|---|
| 1 | `server/ConnectionState.java` *(modificar)* | +`queryVector[14]`, scratch do KNN, `fraudCount`, fix do `reset()` |
| 2 | `dataset/MmapDataset.java` | Carregar `references.json.gz` (3M vetores) em `float[][]` heap |
| 3 | `knn/DistanceFunctions.java` | Euclidiana ao quadrado, escalar, 14 dims |
| 4 | `json/FraudRequestParser.java` | Walker byte-a-byte no body → popula `queryVector[14]` |
| 5 | `knn/HnswIndex.java` | Brute force linear: top-5, conta fraudes |
| 6 | `server/HttpResponseWriter.java` *(modificar)* | Implementar `writeFraudScore` — 6 respostas canned |
| 7 | `controllers/FraudController.java` | Orquestra: parser → KNN → response |
| 8 | `server/NioServer.java` + `Main.java` *(modificar)* | Dispatch `POST /fraud-score` + carregar dataset no boot |

**Critério de saída da Onda 1**: `curl -X POST http://localhost:9999/fraud-score -d @payload.json`
retorna `{approved, fraud_score}` batendo com os exemplos do `REGRAS_DE_DETECCAO.md`.

---

## §1. Mapa mental: fluxo de um `POST /fraud-score`

1. `curl` abre TCP, `NioServer.accept()` atacha um `ConnectionState`, registra `OP_READ`.
2. Bytes chegam: `read(key)` → `channel.read(state.readBuffer)`.
3. `HttpParser.parse(state)` extrai `methodCode=POST`, `pathStart/End` (`/fraud-score`), `contentLength` (do header) e marca `bodyOffset`. **Espera o body inteiro** (`STATE_BODY` conta bytes vs `contentLength`) → só então `PARSE_DONE`.
4. `NioServer.dispatch()`: `methodCode==POST` && path == `/fraud-score` → `FraudController.handle(state, key)`.
5. `FraudRequestParser.parse(state)` percorre `readBuffer` de `bodyOffset` a `bodyOffset+contentLength`, normaliza as 14 dimensões direto em `state.queryVector[]` (zero objeto intermediário).
6. `HnswIndex.search(state)` varre os 3M vetores do `MmapDataset`, mantém os 5 menores `sqDist`, conta quantos são `fraud` → `state.fraudCount`.
7. `fraud_score = fraudCount / 5.0`; `approved = fraud_score < 0.6` (⇔ `fraudCount < 3`).
8. `HttpResponseWriter.writeFraudScore(state, fraudCount)` copia 1 das 6 respostas pré-construídas pro `writeBuffer`; controller troca interest pra `OP_WRITE`.
9. `write(key)` drena → `state.reset()` → volta a `OP_READ` (keep-alive).

O dataset é carregado **uma vez no boot**, antes do `selector` aceitar conexões.

---

## §2. Princípios não-negociáveis (lembrete curto)

Os mesmos do `TUTORIAL_SERVER_NIO.md` §2 + uma exceção pontual:

1. **Zero alocação no hot path** — `parse`/`search` não dão `new`. `queryVector[14]` e os scratch do KNN vivem em `ConnectionState` (alocados 1× por conexão).
2. **Sem `String`/`Map`/regex no parser de request** — comparação byte-a-byte, offsets `int`, números parseados à mão.
3. **EXCEÇÃO — carregar o dataset NÃO é hot path.** É startup, roda 1× no boot. Pode ser "lento" (minutos) e usar parsing simples. Onda 2 troca por binário `mmap` (sem parse). Mesmo assim faremos o parser do dataset **sem alocar `String` por número** — é fácil e evita 42M de garbage.
4. **Single-thread reactor** — 0 lock, 0 sync. O dataset é lido só (RO) depois do boot → seguro sem sync.

---

## §3. Modificar `ConnectionState.java`

**O que estamos fazendo**: adicionar o vetor da query e os scratch do KNN (pré-alocados, zero-alloc), o resultado `fraudCount`, e corrigir o `reset()`.

### Mudanças

Adicione os campos (depois de `headerNameEnd`):

```java
    // ----- Onda 1: fraud-score -----
    // Vetor 14D da request atual (preenchido por FraudRequestParser, sobrescrito todo request)
    public final float[] queryVector = new float[14];

    // Scratch do KNN brute force (top-5) — pré-alocado, reusado por request (zero-alloc)
    public final float[]   knnDist  = new float[5];
    public final boolean[] knnFraud = new boolean[5];

    // Resultado: nº de fraudes entre os 5 vizinhos (0..5)
    public int fraudCount = 0;
```

No `reset()`, adicione **duas** linhas (a do `headerNameEnd` é correção de bug pré-existente):

```java
    public void reset(){
        readBuffer.clear();
        writeBuffer.clear();
        parserState = STATE_METHOD;
        parserPosition = 0;
        methodCode = METHOD_UNKNOW;
        pathEnd = -1;
        pathStart = -1;
        contentLength = 0;
        bodyOffset = -1;
        headerNameStart = -1;
        headerNameEnd = -1;   // <-- BUG FIX (faltava): keep-alive resíduo de header
        fraudCount = 0;       // <-- novo
    }
```

> **Por que não limpar `queryVector` no `reset()`?** O `FraudRequestParser` escreve **todas as 14 posições** em todo request (inclusive os sentinelas -1). Limpar seria trabalho redundante no caminho de keep-alive. Os scratch `knnDist/knnFraud` são reinicializados no início de cada `search()`. Documente isso — é uma decisão deliberada de zero-alloc.

### 🔍 Test point 1 — campos existem com tamanhos certos

Temporariamente em `Main.main()` (antes do `new NioServer`):

```java
ConnectionState s = new ConnectionState();
System.out.println("queryVector len: " + s.queryVector.length); // 14
System.out.println("knnDist len:     " + s.knnDist.length);     // 5
```

`./mvnw -q compile` e rode. Confirme `14` e `5`. **Apague depois** (não deixe lixo no `Main`, vide §0 #4).

---

## §4. `dataset/MmapDataset.java` v1

**O que estamos fazendo**: carregar `references.json.gz` em memória heap como `float[][] vectors` (3M × 14) + `boolean[] isFraud`. Lento e gordo (~200 MB), mas **correto** — é o baseline que a Onda 3 (HNSW real) valida contra. Onda 2 troca por binário `mmap`.

### ⚠️ Pegadinha mor: o `.gz` é UM array JSON numa linha só

Inspecione antes de codar:

```bash
zcat src/main/resources/references.json.gz | head -c 200 ; echo
# [{"vector":[0.01,0.0833,0.05,0.8261,0.1667,-1,-1,0.0432,0.25,0,1,0,0.2,0.0416],"label":"legit"},{"vector":...
```

Os **284 MB descomprimidos são UMA ÚNICA LINHA** (`[{...},{...},...,{...}]`). **`BufferedReader.readLine()` tentaria carregar 284 MB numa `String` → OOM.** Tem que fazer **streaming**: ler byte-a-byte e parsear o array elemento por elemento. (O `example-references.json` é o mesmo formato, só *pretty-printed* com espaços/quebras **e NÃO-gzipado**. Como `load()` sempre faria gunzip, alimentá-lo com o `.json` plano estouraria `ZipException: Not in GZIP format` antes de o parser rodar — por isso `load()` **detecta os 2 magic bytes do gzip** (`0x1f 0x8b`) e só descomprime se for gzip. O parser ignora whitespace, então funciona pros dois: `.json` plano pretty-printed **e** `.json.gz` minificado.)

### Código completo

Crie `api/src/main/java/org/fraudDetection/dataset/MmapDataset.java`:

```java
package org.fraudDetection.dataset;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Onda 1 v1: carrega references.json.gz (3M × {"vector":[14],"label":"fraud|legit"})
 * em float[][] heap + boolean[] labels. Streaming byte-a-byte (o .gz é UM array
 * gigante numa linha — readLine() daria OOM). RO depois do load → sem sync.
 * Onda 2 substitui por binário int8 mmap.
 */
public final class MmapDataset {

    public static float[][] vectors;   // [count][14]
    public static boolean[] isFraud;   // [count] — true = "fraud"
    public static int       count;

    private MmapDataset() {}

    public static void load(String gzPath) throws IOException {
        int cap = 1 << 20;                 // cresce dobrando
        float[][] vs = new float[cap][];
        boolean[] fs = new boolean[cap];
        int n = 0;

        // gzip auto-detect: references.json.gz é gzip; example-references.json (sanity
        // de 100) é JSON plano. Espia os 2 magic bytes (0x1f 0x8b) e só embrulha em
        // GZIPInputStream se for gzip — assim load() funciona pros DOIS formatos.
        InputStream raw = new BufferedInputStream(new FileInputStream(gzPath), 1 << 16);
        raw.mark(2);
        int m0 = raw.read();
        int m1 = raw.read();
        raw.reset();
        boolean gzipped = (m0 == 0x1f && m1 == 0x8b);

        try (InputStream in = gzipped
                ? new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16)
                : raw) {

            int c = skipTo(in, '[');       // entra no array raiz
            if (c < 0) throw new IOException("dataset vazio / sem '['");

            while (true) {
                c = nextNonWs(in);                       // ',' entre objetos ou ']' final
                if (c == ']' || c < 0) break;
                if (c != '{') {
                    if (c == ',') continue;              // vírgula entre elementos
                    continue;
                }
                // dentro de um objeto: pega 14 floats do "vector" e a 1ª letra do "label"
                float[] vec = new float[14];
                skipTo(in, '[');                         // abre o array vector
                for (int k = 0; k < 14; k++) {
                    vec[k] = readFloat(in);              // lê até ',' ou ']'
                }
                // agora procurar o valor de "label": primeiro '"' depois do ']' do vector
                // estrutura: ...],"label":"legit"} → 1ª aspa abre a string do label
                skipTo(in, '"');                         // abre "label"
                skipTo(in, '"');                         // fecha "label"
                skipTo(in, '"');                         // abre o valor
                int first = in.read();                   // 'f' (fraud) ou 'l' (legit)
                boolean fraud = (first == 'f');
                skipTo(in, '"');                         // fecha o valor
                skipTo(in, '}');                         // fecha o objeto

                if (n == cap) {                          // grow
                    cap <<= 1;
                    float[][] nv = new float[cap][];   System.arraycopy(vs, 0, nv, 0, n); vs = nv;
                    boolean[] nf = new boolean[cap];   System.arraycopy(fs, 0, nf, 0, n); fs = nf;
                }
                vs[n] = vec;
                fs[n] = fraud;
                n++;
                if ((n % 500_000) == 0) System.out.println("  loaded " + n + " vectors...");
            }
        }

        vectors = vs;
        isFraud = fs;
        count   = n;
    }

    // ---- helpers de stream (sem String, sem regex) ----

    /** Lê até encontrar o byte alvo (consome-o). Retorna o byte ou -1 (EOF). */
    private static int skipTo(InputStream in, int target) throws IOException {
        int b;
        while ((b = in.read()) != -1) if (b == target) return b;
        return -1;
    }

    /** Próximo byte que não seja espaço/tab/CR/LF. */
    private static int nextNonWs(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return b;
        }
        return -1;
    }

    /**
     * Parser de float by-hand (sem Float.parseFloat → sem String → zero garbage).
     * Cobre: sinal '-', parte inteira, '.', parte fracionária. Sem expoente
     * (o dataset não usa). Para no delimitador ',' ou ']' (que é consumido).
     */
    private static float readFloat(InputStream in) throws IOException {
        int b = nextNonWs(in);
        boolean neg = false;
        if (b == '-') { neg = true; b = in.read(); }
        double val = 0;
        while (b >= '0' && b <= '9') { val = val * 10 + (b - '0'); b = in.read(); }
        if (b == '.') {
            b = in.read();
            double scale = 0.1;
            while (b >= '0' && b <= '9') { val += (b - '0') * scale; scale *= 0.1; b = in.read(); }
        }
        // b agora é ',' ou ']' (ou ws) — já consumido, ok
        return (float) (neg ? -val : val);
    }
}
```

### Detalhes importantes

- **gzip auto-detect** (magic `0x1f 0x8b`) + `BufferedInputStream` (64 KB) — `.gz` (3M) descomprime streaming; `example-references.json` plano (sanity 100) lê direto. RAM constante. Sem o auto-detect, o Test point 2 fase-1 estoura `ZipException`.
- `float[3_000_000][14]` ≈ 168 MB + ~48 MB de headers de sub-array ≈ **~220 MB**. **Rode com `-Xmx768m`** (o default da JVM provavelmente dá `OutOfMemoryError`). Veremos isso no boot (§9).
- Parsing à mão do float: evita 42M de `String` temporárias. Precisão `double` → cast `float` no fim (suficiente; valores têm ≤10 casas).
- 3M objetos: o load leva **~1-3 min** (descompressão + parse). Normal na v1. Onda 2 mata isso com binário mmap.

### 🔍 Test point 2 — sanity rápido com 100 entradas, DEPOIS 3M

Não rode os 3M ainda. Primeiro o `example-references.json` (100 entradas, mesmo formato):

```bash
cp ../../rinha-de-backend-2026/resources/example-references.json src/main/resources/
```

Temporário em `Main.main()`:

```java
MmapDataset.load("src/main/resources/example-references.json"); // 100 entradas
System.out.println("count=" + MmapDataset.count);                 // 100
System.out.print("v0=[");
for (float f : MmapDataset.vectors[0]) System.out.print(f + " ");
System.out.println("] label0_fraud=" + MmapDataset.isFraud[0]);
```

Espere:
```
count=100
v0=[0.01 0.0833 0.05 0.8261 0.1667 -1.0 -1.0 0.0432 0.25 0.0 1.0 0.0 0.2 0.0416 ] label0_fraud=false
```

(Esse é literalmente o 1º elemento do `references.json.gz` — o `example-references.json` são as primeiras 100 entradas do dataset.) Bateu? Agora teste o real (1× só, demora):

```java
MmapDataset.load("src/main/resources/references.json.gz");
System.out.println("count=" + MmapDataset.count);   // 3000000
```

Espere `count=3000000` (e os logs `loaded 500000 vectors...`). Rode com `-Xmx768m`. **Apague o trecho temporário depois.**

---

## §5. `knn/DistanceFunctions.java` v1

**O que estamos fazendo**: distância euclidiana **ao quadrado** (sem `sqrt`), escalar, 14 dimensões fixas.

### Por que ao quadrado (sem `sqrt`)

`sqrt` é monotônica e crescente: se `d²(a) < d²(b)` então `d(a) < d(b)`. Para **ranking top-5** a raiz é irrelevante — só precisamos da ordem. Pular `sqrt` = ~3M `Math.sqrt` a menos por request. Os exemplos do `REGRAS_DE_DETECCAO.md` mostram distância *com* raiz (`0.0340`...), mas o resultado da classificação (quais 5 são os mais próximos) é idêntico. Documentamos isso como otimização deliberada.

### Pegadinha: os sentinelas `-1` entram normal

Quando `last_transaction: null`, índices 5 e 6 são `-1` **tanto na query quanto nas referências** que também tinham `null`. O spec manda **não filtrar/substituir**: `-1` participa do cálculo como qualquer número. Dois vetores "primeira transação" ficam próximos entre si (ambos têm `-1,-1`) — é justamente o que se quer.

### Código completo

Crie `api/src/main/java/org/fraudDetection/knn/DistanceFunctions.java`:

```java
package org.fraudDetection.knn;

public final class DistanceFunctions {

    private DistanceFunctions() {}

    /** Euclidiana AO QUADRADO, 14 dims. Sem sqrt (monotônica → ranking idêntico). */
    public static float sqDist(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < 14; i++) {
            float d = a[i] - b[i];
            s += d * d;
        }
        return s;
    }
}
```

> Onda 2 reescreve isto com `jdk.incubator.vector` (SIMD AVX2, int8) — vide `CONCEITOS.md` §5 e `RINHA_PLAN.md` §5.6. O loop fixo de 14 já é JIT-friendly (desenrola).

### 🔍 Test point 3 — distância conhecida

Temporário em `Main`:

```java
float[] x = {0,0,0,0,0,0,0,0,0,0,0,0,0,0};
float[] y = {1,0,0,0,0,0,0,0,0,0,0,0,0,2};   // diff: dim0=1, dim13=2
System.out.println(DistanceFunctions.sqDist(x, x)); // 0.0
System.out.println(DistanceFunctions.sqDist(x, y)); // 1*1 + 2*2 = 5.0
```

Espere `0.0` e `5.0`. Apague.

---

## §6. `json/FraudRequestParser.java`

**O que estamos fazendo**: walker byte-a-byte no body do POST (em `state.readBuffer`, de `bodyOffset` a `bodyOffset+contentLength`), normalizando as 14 dimensões direto em `state.queryVector[]`. Sem objeto intermediário, sem `String`, sem `Map`, sem regex.

### A fórmula 14D (fonte: `REGRAS_DE_DETECCAO.md`)

Constantes (`normalization.json`, fixas) → hard-coded como `final`. `limitar(x)` = clamp em `[0,1]`.

| idx | dimensão | fórmula | clamp? |
|---|---|---|---|
| 0 | amount | `transaction.amount / 10000` | sim |
| 1 | installments | `transaction.installments / 12` | sim |
| 2 | amount_vs_avg | `(transaction.amount / customer.avg_amount) / 10` | sim |
| 3 | hour_of_day | `hora(requested_at) / 23` | não (0-23 natural) |
| 4 | day_of_week | `dia_semana(requested_at) / 6` (**seg=0 … dom=6**) | não |
| 5 | minutes_since_last_tx | `minutos(requested_at − last.timestamp) / 1440` **ou -1 se null** | sim (exceto -1) |
| 6 | km_from_last_tx | `last_transaction.km_from_current / 1000` **ou -1 se null** | sim (exceto -1) |
| 7 | km_from_home | `terminal.km_from_home / 1000` | sim |
| 8 | tx_count_24h | `customer.tx_count_24h / 20` | sim |
| 9 | is_online | `1` se `terminal.is_online` senão `0` | n/a |
| 10 | card_present | `1` se `terminal.card_present` senão `0` | n/a |
| 11 | unknown_merchant | `1` se `merchant.id` **NÃO** ∈ `customer.known_merchants` senão `0` | n/a |
| 12 | mcc_risk | `mcc_risk[merchant.mcc]` (default **0.5**) | não (já 0-1) |
| 13 | merchant_avg_amount | `merchant.avg_amount / 10000` | sim |

### Estratégia do parser (schema fixo)

O payload tem schema fixo e raso. Em vez de um parser JSON genérico com pilha, fazemos **busca de chave escopada ao objeto pai**:

1. Localiza os ranges dos objetos de 1º nível (`"transaction"`, `"customer"`, `"merchant"`, `"terminal"`) — cada um é `"chave"` → `{` … `}` casado.
2. Dentro de cada range, acha cada chave **pelo token entre aspas exato** (`"amount"` ≠ `"avg_amount"` ≠ `"max_amount"` — match exato evita colisão de substring).
3. `last_transaction`: acha a chave; primeiro caractere não-espaço do valor é `n` (→ `null` → `v[5]=v[6]=-1`) ou `{` (→ parseia `timestamp` e `km_from_current`).
4. Datas ISO-8601 `YYYY-MM-DDTHH:MM:SSZ` (20 chars fixos): hora, dia-da-semana (algoritmo civil-days de Hinnant) e diff de minutos por epoch-seconds — tudo `int/long`, sem `java.time`.

### Pegadinha de nome de campo

O índice 6 ("km_from_last_tx" na fórmula) vem do campo JSON **`last_transaction.km_from_current`** — nome diferente da dimensão. Não procure `km_from_last_tx` no payload; ele não existe.

### Código completo

Crie `api/src/main/java/org/fraudDetection/json/FraudRequestParser.java`:

```java
package org.fraudDetection.json;

import org.fraudDetection.server.ConnectionState;

import java.nio.ByteBuffer;

/**
 * Walker byte-a-byte do body do POST /fraud-score → state.queryVector[14].
 * Sem String/Map/regex/objeto intermediário. Schema fixo (REGRAS_DE_DETECCAO.md).
 */
public final class FraudRequestParser {

    public static final int PARSE_OK  = 0;
    public static final int PARSE_BAD = -1;

    // normalization.json (constantes fixas)
    private static final double MAX_AMOUNT = 10000, MAX_INSTALLMENTS = 12,
            AMOUNT_VS_AVG_RATIO = 10, MAX_MINUTES = 1440, MAX_KM = 1000,
            MAX_TX_24H = 20, MAX_MERCH_AVG = 10000;

    private FraudRequestParser() {}

    public static int parse(ConnectionState s) {
        ByteBuffer b = s.readBuffer;
        int from = s.bodyOffset;
        int to   = s.bodyOffset + s.contentLength;
        float[] v = s.queryVector;
        if (from < 0 || to > b.position() || from >= to) return PARSE_BAD;

        // ---- ranges dos objetos de 1º nível ----
        int trS = objStart(b, from, to, K_TRANSACTION); if (trS < 0) return PARSE_BAD;
        int trE = matchBrace(b, trS, to);
        int cuS = objStart(b, from, to, K_CUSTOMER);    if (cuS < 0) return PARSE_BAD;
        int cuE = matchBrace(b, cuS, to);
        int meS = objStart(b, from, to, K_MERCHANT);    if (meS < 0) return PARSE_BAD;
        int meE = matchBrace(b, meS, to);
        int teS = objStart(b, from, to, K_TERMINAL);    if (teS < 0) return PARSE_BAD;
        int teE = matchBrace(b, teS, to);

        // ---- transaction ----
        double amount       = num(b, valPos(b, trS, trE, K_AMOUNT));
        double installments = num(b, valPos(b, trS, trE, K_INSTALLMENTS));
        int reqAt = strPos(b, trS, trE, K_REQUESTED_AT);          // índice da 1ª aspa do valor
        // ---- customer ----
        double avgAmount    = num(b, valPos(b, cuS, cuE, K_AVG_AMOUNT));
        double txCount24h   = num(b, valPos(b, cuS, cuE, K_TX_COUNT_24H));
        int kmA = valPos(b, cuS, cuE, K_KNOWN_MERCHANTS);          // '[' do array
        int kmB = matchBracket(b, kmA, cuE);
        // ---- merchant ----
        int midA = strPos(b, meS, meE, K_ID) + 1;                  // 1º char do id (após aspa)
        int midB = strEnd(b, midA);
        int mccA = strPos(b, meS, meE, K_MCC) + 1;
        int mccB = strEnd(b, mccA);
        double merchAvg     = num(b, valPos(b, meS, meE, K_AVG_AMOUNT));
        // ---- terminal ----
        boolean isOnline    = bool(b, valPos(b, teS, teE, K_IS_ONLINE));
        boolean cardPresent = bool(b, valPos(b, teS, teE, K_CARD_PRESENT));
        double kmFromHome   = num(b, valPos(b, teS, teE, K_KM_FROM_HOME));

        // ---- datas ----
        long reqEpoch = isoEpochSec(b, reqAt + 1);                 // +1: pula a aspa
        int  hour     = twoDigit(b, reqAt + 1 + 11);               // chars[11..12] = HH
        int  dow      = dowMon0(isoCivilDays(b, reqAt + 1));        // seg=0..dom=6

        // ---- last_transaction (null | objeto) ----
        int ltVal = valPos(b, from, to, K_LAST_TRANSACTION);       // 'n' (null) ou '{'
        double minNorm, kmLastNorm;
        if (ltVal < 0) return PARSE_BAD;
        if (b.get(ltVal) == 'n') {                                 // null
            minNorm = -1; kmLastNorm = -1;
        } else {                                                   // objeto { timestamp, km_from_current }
            int ltE  = matchBrace(b, ltVal, to);
            int tsA  = strPos(b, ltVal, ltE, K_TIMESTAMP);
            long lastEpoch = isoEpochSec(b, tsA + 1);
            double minutes = (reqEpoch - lastEpoch) / 60.0;
            minNorm    = clamp(minutes / MAX_MINUTES);
            double kmc = num(b, valPos(b, ltVal, ltE, K_KM_FROM_CURRENT));
            kmLastNorm = clamp(kmc / MAX_KM);
        }

        // ---- monta o vetor ----
        v[0]  = (float) clamp(amount / MAX_AMOUNT);
        v[1]  = (float) clamp(installments / MAX_INSTALLMENTS);
        v[2]  = (float) clamp((amount / avgAmount) / AMOUNT_VS_AVG_RATIO);
        v[3]  = (float) (hour / 23.0);
        v[4]  = (float) (dow / 6.0);
        v[5]  = (float) minNorm;
        v[6]  = (float) kmLastNorm;
        v[7]  = (float) clamp(kmFromHome / MAX_KM);
        v[8]  = (float) clamp(txCount24h / MAX_TX_24H);
        v[9]  = isOnline    ? 1f : 0f;
        v[10] = cardPresent ? 1f : 0f;
        v[11] = inArray(b, kmA, kmB, midA, midB) ? 0f : 1f;        // invertido
        v[12] = (float) mccRisk(b, mccA, mccB);
        v[13] = (float) clamp(merchAvg / MAX_MERCH_AVG);
        return PARSE_OK;
    }

    // ===================== helpers =====================

    private static double clamp(double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    // ---- chaves (bytes, lowercase exato) ----
    private static final byte[] K_TRANSACTION     = b("transaction");
    private static final byte[] K_CUSTOMER        = b("customer");
    private static final byte[] K_MERCHANT        = b("merchant");
    private static final byte[] K_TERMINAL        = b("terminal");
    private static final byte[] K_LAST_TRANSACTION= b("last_transaction");
    private static final byte[] K_AMOUNT          = b("amount");
    private static final byte[] K_INSTALLMENTS    = b("installments");
    private static final byte[] K_REQUESTED_AT    = b("requested_at");
    private static final byte[] K_AVG_AMOUNT      = b("avg_amount");
    private static final byte[] K_TX_COUNT_24H    = b("tx_count_24h");
    private static final byte[] K_KNOWN_MERCHANTS = b("known_merchants");
    private static final byte[] K_ID              = b("id");
    private static final byte[] K_MCC             = b("mcc");
    private static final byte[] K_IS_ONLINE       = b("is_online");
    private static final byte[] K_CARD_PRESENT    = b("card_present");
    private static final byte[] K_KM_FROM_HOME    = b("km_from_home");
    private static final byte[] K_TIMESTAMP       = b("timestamp");
    private static final byte[] K_KM_FROM_CURRENT = b("km_from_current");
    private static byte[] b(String s) {
        byte[] r = new byte[s.length()];
        for (int i = 0; i < r.length; i++) r[i] = (byte) s.charAt(i);
        return r;
    }

    /** Acha "key" exato (token entre aspas) em [from,to). Retorna idx da 1ª aspa, ou -1. */
    private static int findKeyExact(ByteBuffer b, int from, int to, byte[] key) {
        for (int i = from; i + key.length + 1 < to; i++) {
            if (b.get(i) != '"') continue;
            int j = 0;
            while (j < key.length && b.get(i + 1 + j) == key[j]) j++;
            if (j == key.length && b.get(i + 1 + key.length) == '"') return i;
        }
        return -1;
    }

    /** Posição do valor (1º não-ws depois do ':') da key em [from,to). */
    private static int valPos(ByteBuffer b, int from, int to, byte[] key) {
        int k = findKeyExact(b, from, to, key);
        if (k < 0) return -1;
        int i = k + key.length + 2;            // pula "key"
        while (b.get(i) != ':') i++;
        return nextNonWs(b, i + 1, to);
    }

    /** Idx do '{' do objeto da key (objStart). */
    private static int objStart(ByteBuffer b, int from, int to, byte[] key) {
        int p = valPos(b, from, to, key);
        return (p >= 0 && b.get(p) == '{') ? p : -1;
    }

    /** Idx da 1ª aspa do valor-string da key. */
    private static int strPos(ByteBuffer b, int from, int to, byte[] key) {
        return valPos(b, from, to, key);       // valor de string começa na própria aspa
    }

    private static int strEnd(ByteBuffer b, int afterQuote) {      // idx da aspa de fechamento
        int i = afterQuote;
        while (b.get(i) != '"') i++;
        return i;
    }

    private static int nextNonWs(ByteBuffer b, int i, int to) {
        while (i < to) {
            byte c = b.get(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return i;
            i++;
        }
        return to - 1;
    }

    /** Dado idx de '{', retorna idx do '}' casado. */
    private static int matchBrace(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return to - 1;
    }

    /** Dado idx de '[', retorna idx do ']' casado. */
    private static int matchBracket(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return to - 1;
    }

    /** Número (double) começando em pos: sinal, inteiro, fração. Sem expoente. */
    private static double num(ByteBuffer b, int pos) {
        int i = pos;
        boolean neg = b.get(i) == '-';
        if (neg) i++;
        double val = 0;
        for (byte c; (c = b.get(i)) >= '0' && c <= '9'; i++) val = val * 10 + (c - '0');
        if (b.get(i) == '.') {
            i++;
            double sc = 0.1;
            for (byte c; (c = b.get(i)) >= '0' && c <= '9'; i++) { val += (c - '0') * sc; sc *= 0.1; }
        }
        return neg ? -val : val;
    }

    private static boolean bool(ByteBuffer b, int pos) { return b.get(pos) == 't'; }

    /** id (idA..idB) está entre os elementos do array [arrA..arrB]? (token entre aspas exato) */
    private static boolean inArray(ByteBuffer b, int arrA, int arrB, int idA, int idB) {
        int len = idB - idA;
        for (int i = arrA; i < arrB; i++) {
            if (b.get(i) != '"') continue;
            int s = i + 1, j = 0;
            while (j < len && b.get(s + j) == b.get(idA + j)) j++;
            if (j == len && b.get(s + len) == '"') return true;
            i = strEnd(b, s);                  // pula pro fim dessa string
        }
        return false;
    }

    // mcc_risk.json (10 códigos; default 0.5). Compara os 4 bytes do mcc.
    private static final byte[][] MCC = {
        b("5411"), b("5812"), b("5912"), b("5944"), b("7801"),
        b("7802"), b("7995"), b("4511"), b("5311"), b("5999")
    };
    private static final double[] MCC_R = {0.15,0.30,0.20,0.45,0.80,0.75,0.85,0.35,0.25,0.50};
    private static double mccRisk(ByteBuffer b, int a, int end) {
        int len = end - a;
        for (int m = 0; m < MCC.length; m++) {
            if (MCC[m].length != len) continue;
            int j = 0;
            while (j < len && b.get(a + j) == MCC[m][j]) j++;
            if (j == len) return MCC_R[m];
        }
        return 0.5;
    }

    // ---- datas ISO-8601 "YYYY-MM-DDTHH:MM:SSZ" (pos = 1º char, após a aspa) ----
    private static int dig(ByteBuffer b, int p) { return b.get(p) - '0'; }
    private static int twoDigit(ByteBuffer b, int p) { return dig(b, p) * 10 + dig(b, p + 1); }
    private static int fourDigit(ByteBuffer b, int p) {
        return dig(b,p)*1000 + dig(b,p+1)*100 + dig(b,p+2)*10 + dig(b,p+3);
    }

    /** Dias civis desde 1970-01-01 (algoritmo de Howard Hinnant). */
    private static long isoCivilDays(ByteBuffer b, int p) {
        int y = fourDigit(b, p);          // [0..3]
        int m = twoDigit(b, p + 5);       // [5..6]
        int d = twoDigit(b, p + 8);       // [8..9]
        return civilToDays(y, m, d);
    }
    private static long civilToDays(int y, int m, int d) {
        y -= (m <= 2) ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
    /** 1970-01-01 foi quinta. Converte pra seg=0..dom=6. */
    private static int dowMon0(long days) { return (int) Math.floorMod(days + 3, 7); }

    private static long isoEpochSec(ByteBuffer b, int p) {
        long days = isoCivilDays(b, p);
        int hh = twoDigit(b, p + 11), mm = twoDigit(b, p + 14), ss = twoDigit(b, p + 17);
        return days * 86400L + hh * 3600L + mm * 60L + ss;
    }
}
```

### Por que tanto código

Parser JSON genérico com pilha + `Map` alocaria objeto por request e pesaria 50-200 µs. Este, escopado ao schema fixo, **não aloca nada por request** (as chaves `K_*`/`MCC` são `static final` construídas 1× no class-load) e roda em ~5-30 µs. É exatamente a disciplina do `CONCEITOS.md` §9/§10.

### 🔍 Test point 4 — vetor bate com o oráculo oficial

O `REGRAS_DE_DETECCAO.md` dá o exemplo legítimo (= `example-payloads.json[0]`) com o vetor esperado **oficial**. Use-o como oráculo. Temporário em `Main`:

```java
ConnectionState s = new ConnectionState();
byte[] body = ("{\"id\":\"tx-1329056812\","
  + "\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},"
  + "\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-003\",\"MERC-016\"]},"
  + "\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},"
  + "\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.2331036248},"
  + "\"last_transaction\":null}").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
s.readBuffer.put(body);
s.bodyOffset = 0;
s.contentLength = body.length;

System.out.println("ret=" + FraudRequestParser.parse(s)); // 0 (PARSE_OK)
for (int i = 0; i < 14; i++) System.out.printf("v[%d]=%.4f%n", i, s.queryVector[i]);
```

Esperado (≈, do `REGRAS_DE_DETECCAO.md` §"Visão geral do fluxo"):
```
[0.0041, 0.1667, 0.05, 0.7826, 0.3333, -1, -1, 0.0292, 0.15, 0, 1, 0, 0.15, 0.006]
```
Confira sobretudo: `v[4]=0.3333` (2026-03-11 = **quarta** → seg=0..dom=6 → 2 → 2/6), `v[5]=v[6]=-1` (last_transaction null), `v[11]=0` (MERC-016 ∈ known), `v[12]=0.15` (mcc 5411). Apague depois.

> Quer testar o caminho de data com `last_transaction` objeto? Use o payload do smoke (§10): `requested_at 20:23:35` − `last 14:58:35` = 325 min → `v[5]=325/1440≈0.2257`.

---

## §7. `knn/HnswIndex.java` v1 (brute force)

**O que estamos fazendo**: varrer os 3M vetores do `MmapDataset`, manter os 5 menores `sqDist`, contar quantos desses 5 são `fraud`. Nome "HnswIndex" mesmo sendo brute force — é o **baseline de correctness** que a Onda 3 (HNSW real) vai validar contra. Vai dar p99 ~30 ms; tudo bem, latência é irrelevante na Onda 1.

### Código completo

Crie `api/src/main/java/org/fraudDetection/knn/HnswIndex.java`:

```java
package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

/**
 * Onda 1 v1: brute force linear sobre os 3M vetores. Mantém top-5 por menor
 * sqDist (inserção em array de 5, zero-alloc via scratch do ConnectionState).
 * Resultado: state.fraudCount (0..5). Onda 3 troca por HNSW de verdade.
 */
public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final float[]   q  = s.queryVector;
        final float[][] V  = MmapDataset.vectors;
        final boolean[] F  = MmapDataset.isFraud;
        final int       n  = MmapDataset.count;
        final float[]   bd = s.knnDist;     // best distances (crescente)
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            float d = DistanceFunctions.sqDist(q, V[i]);
            if (d < bd[4]) {                          // entra no top-5?
                int p = 4;
                while (p > 0 && bd[p - 1] > d) {      // shift pra direita
                    bd[p] = bd[p - 1]; bf[p] = bf[p - 1]; p--;
                }
                bd[p] = d; bf[p] = F[i];
            }
        }

        int fraud = 0;
        for (int k = 0; k < 5; k++) if (bf[k]) fraud++;
        s.fraudCount = fraud;
    }
}
```

### Pegadinha — empates

Se duas referências têm exatamente a mesma distância, a ordem entre elas é arbitrária (depende da ordem de varredura). O spec aceita qualquer ordenação consistente — não há regra de desempate. Não tente "estabilizar".

### 🔍 Test point 5 — query idêntica a uma referência

Com o `example-references.json` carregado (100 entradas), uma query == `vectors[1]` deve achar distância 0 nela e o label coerente. Temporário em `Main`:

```java
MmapDataset.load("src/main/resources/example-references.json");
ConnectionState s = new ConnectionState();
System.arraycopy(MmapDataset.vectors[1], 0, s.queryVector, 0, 14);
HnswIndex.search(s);
System.out.println("fraudCount=" + s.fraudCount + " bestDist=" + s.knnDist[0]); // bestDist=0.0
```

Espere `bestDist=0.0` (o próprio vetor está no dataset). Apague.

---

## §8. Modificar `HttpResponseWriter.java` — implementar `writeFraudScore`

**O que estamos fazendo**: trocar o stub que lança `UnsupportedOperationException` por respostas **pré-construídas**.

### Por que 6 respostas, não 12

`approved` é função pura de `fraud_score`: `approved = score < 0.6`. Como `score = fraudCount/5 ∈ {0.0, 0.2, 0.4, 0.6, 0.8, 1.0}`, só há **6 respostas possíveis** (`fraudCount` 0→5). O `RINHA_PLAN.md` §8.8 fala em "12 (2 bool × 6 níveis)" — é redundante: o bool não é independente. Indexamos por `fraudCount`.

### Código completo

Substitua o conteúdo de `api/src/main/java/org/fraudDetection/server/HttpResponseWriter.java`
(o arquivo já renomeado do typo — §0 #2):

```java
package org.fraudDetection.server;

import java.nio.charset.StandardCharsets;

public final class HttpResponseWriter {

    private HttpResponseWriter() {}

    private static final byte[] RESPONSE_READY =
            ("HTTP/1.1 200 OK\r\n" +
             "Connection: keep-alive\r\n" +
             "Content-Length: 0\r\n" +
             "\r\n").getBytes(StandardCharsets.US_ASCII);

    // 6 respostas canned, indexadas por fraudCount (0..5). approved = score < 0.6.
    private static final byte[][] RESPONSE_FRAUD = new byte[6][];
    static {
        String[] body = {
            "{\"approved\":true,\"fraud_score\":0.0}",   // 0/5
            "{\"approved\":true,\"fraud_score\":0.2}",   // 1/5
            "{\"approved\":true,\"fraud_score\":0.4}",   // 2/5
            "{\"approved\":false,\"fraud_score\":0.6}",  // 3/5
            "{\"approved\":false,\"fraud_score\":0.8}",  // 4/5
            "{\"approved\":false,\"fraud_score\":1.0}"   // 5/5
        };
        for (int i = 0; i < 6; i++) {
            byte[] bb = body[i].getBytes(StandardCharsets.US_ASCII);
            String head = "HTTP/1.1 200 OK\r\n" +
                          "Connection: keep-alive\r\n" +
                          "Content-Type: application/json\r\n" +
                          "Content-Length: " + bb.length + "\r\n\r\n";
            byte[] hb = head.getBytes(StandardCharsets.US_ASCII);
            byte[] full = new byte[hb.length + bb.length];
            System.arraycopy(hb, 0, full, 0, hb.length);
            System.arraycopy(bb, 0, full, hb.length, bb.length);
            RESPONSE_FRAUD[i] = full;
        }
    }

    public static void writeReady(ConnectionState state) {
        state.writeBuffer.clear();
        state.writeBuffer.put(RESPONSE_READY);
        state.writeBuffer.flip();
    }

    /** fraudCount 0..5 → 1 das 6 respostas canned (Content-Length já correto). */
    public static void writeFraudScore(ConnectionState state, int fraudCount) {
        state.writeBuffer.clear();
        state.writeBuffer.put(RESPONSE_FRAUD[fraudCount]);
        state.writeBuffer.flip();
    }
}
```

> A assinatura mudou de `(state, boolean, int)` (o stub antigo) para `(state, int fraudCount)` — `approved` é derivado, não precisa ser passado. Ajuste o `HealthController` se ele referenciava o nome antigo (só usa `writeReady`, então provavelmente só o rename de classe do §0 #2).

### Pegadinha — `Content-Length` e `0.0` vs `0`

`writeBuffer` tem 512 bytes (`ByteBuffer.allocateDirect(512)`) — as respostas têm ~150 B, cabe. O `Content-Length` é calculado de `bb.length` (não chute à mão — corpo `false` tem 1 byte a mais que `true`). `fraud_score` **com `.0`** (`"1.0"`, não `"1"`) — o oráculo do spec é `"fraud_score": 1.0`.

### 🔍 Test point 6 — imprimir as 6 respostas

```java
ConnectionState s = new ConnectionState();
for (int fc = 0; fc <= 5; fc++) {
    HttpResponseWriter.writeFraudScore(s, fc);
    byte[] t = new byte[s.writeBuffer.remaining()];
    s.writeBuffer.duplicate().get(t);
    System.out.println("--- fraudCount=" + fc + " ---");
    System.out.println(new String(t, java.nio.charset.StandardCharsets.US_ASCII));
}
```

Confira: `fc 0..2` → `"approved":true`; `fc 3..5` → `"approved":false`; `Content-Length` 35 (true) / 36 (false). Apague.

---

## §9. `controllers/FraudController.java` + wiring + boot do dataset

**O que estamos fazendo**: o handler do `/fraud-score` (espelha o `HealthController`), o dispatch no `NioServer`, e carregar o dataset no boot.

### `controllers/FraudController.java`

Crie `api/src/main/java/org/fraudDetection/controllers/FraudController.java`:

```java
package org.fraudDetection.controllers;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

import java.nio.channels.SelectionKey;

public final class FraudController {

    private FraudController() {}

    public static void handle(ConnectionState state, SelectionKey key) {
        if (FraudRequestParser.parse(state) != FraudRequestParser.PARSE_OK) {
            // Onda 1: payload inválido é raro nos testes; trata como score 0 (approved).
            // Onda 4 endurece com 400 Bad Request dedicado.
            state.fraudCount = 0;
        } else {
            HnswIndex.search(state);                 // preenche state.fraudCount
        }
        HttpResponseWriter.writeFraudScore(state, state.fraudCount);
        key.interestOps(SelectionKey.OP_WRITE);
    }
}
```

### Wiring no `NioServer.dispatch()`

Adicione a constante de path e o branch POST **antes** do `key.cancel()`:

```java
    private static final byte[] PATH_READY = {'/','r','e','a','d','y'};
    private static final byte[] PATH_FRAUD = {'/','f','r','a','u','d','-','s','c','o','r','e'};

    private void dispatch(ConnectionState state, SelectionKey key) {
        if (state.methodCode == ConnectionState.METHOD_GET
                && bytesEqual(state.readBuffer, state.pathStart, state.pathEnd, PATH_READY)) {
            org.fraudDetection.controllers.HealthController.handle(state, key);
            return;
        }
        if (state.methodCode == ConnectionState.METHOD_POST
                && bytesEqual(state.readBuffer, state.pathStart, state.pathEnd, PATH_FRAUD)) {
            org.fraudDetection.controllers.FraudController.handle(state, key);
            return;
        }
        key.cancel();
        try { key.channel().close(); } catch (IOException ignored) {}
    }
```

(Note `org.fraudDetection.controllers.HealthController` — já movido no §0 #3. E o `matchMethod` já corrigido no §0 #1, senão `METHOD_POST` nunca chega aqui.)

### Boot do dataset no `Main.java`

`Main.java` (limpo dos prints do §0 #4):

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.NioServer;

public class Main {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        MmapDataset.load("src/main/resources/references.json.gz");
        System.out.println("dataset loaded: " + MmapDataset.count
                + " vectors (" + (System.currentTimeMillis() - t0) + " ms)");

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}
```

> O dataset carrega **antes** do `selector` aceitar conexões → quando `/ready` responde, o índice já está pronto. (Onda 4 revisita o gating de readiness pro health-check do container; na Onda 1 local isto basta.) Caminho relativo `src/main/resources/...` → **rode a partir de `fraudDetection/api/`**.

### Pegadinhas dessa seção

| Pegadinha | Sintoma | Fix |
|---|---|---|
| `matchMethod` não corrigido (§0 #1) | POST sempre 404 / conexão fecha | `buf.get(start+3) == 'T'` |
| `HealthController` ainda em `server/` | `dispatch` não compila (pacote errado) | mover pra `controllers/` (§0 #3) |
| Rodar de outro diretório | `FileNotFoundException` no `.gz` | `cd fraudDetection/api` antes do `java` |
| Sem `-Xmx` | `OutOfMemoryError: Java heap space` no load | `java -Xmx768m ...` |
| Body > 4096 B | parser do request lê lixo | payloads da Rinha são <1 KB; OK na Onda 1 (Onda 2+ cresce o `readBuffer` se preciso) |

---

## §10. 🔍 Test point grande: `POST /fraud-score` end-to-end

### Comandos

**2 terminais separados.**

**Terminal 1** (build + server — o load dos 3M demora ~1-3 min):
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw clean package
java -Xmx768m --add-modules jdk.incubator.vector -jar target/api.jar 9999
# espere: "dataset loaded: 3000000 vectors (NNNNN ms)"
# depois:  "api: Listening on port 9999"
```

**Terminal 2** (oráculo oficial — o caso **legítimo** do `REGRAS_DE_DETECCAO.md`):
```bash
# /ready ainda funciona
curl -s http://localhost:9999/ready -i | head -1     # HTTP/1.1 200 OK

# caso LEGÍTIMO (REGRAS_DE_DETECCAO.md §"Visão geral"): espera approved=true, score=0.0
curl -s -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# esperado: {"approved":true,"fraud_score":0.0}

# caso FRAUDE (REGRAS_DE_DETECCAO.md §"Exemplo de transação fraudulenta"): approved=false, score=1.0
curl -s -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# esperado: {"approved":false,"fraud_score":1.0}

# keep-alive: 2 requests na mesma conexão
curl -s http://localhost:9999/fraud-score http://localhost:9999/fraud-score -X POST -d @<(echo '{"id":"x","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}')
```

Os dois primeiros vetores estão **explícitos no `REGRAS_DE_DETECCAO.md`** (`[0.0041,0.1667,...]` legítimo; `[0.9506,0.8333,...]` fraude) com os 5 vizinhos e o score — é o oráculo definitivo da Onda 1. Bateu os dois → **Onda 1 fechada.** 🏁

### Validação em lote (opcional, recomendado)

`rinha-de-backend-2026/test/test-data.json` tem 54.100 entradas `{request, expected_approved}`. Um scriptzinho que faz POST de cada `request` e compara `approved` com `expected_approved` mede a acurácia do baseline (FP/FN). Latência é irrelevante na Onda 1 — o que importa é a corretude vs o brute force.

### Troubleshooting

| Sintoma | Causa provável | Vá pra |
|---|---|---|
| POST devolve nada / conexão fecha | `matchMethod` não corrigido → `METHOD_POST` nunca detectado | §0 #1 |
| `curl: (52) Empty reply` | `writeFraudScore` ainda é o stub `UnsupportedOperationException` | §8 |
| `OutOfMemoryError` no boot | sem `-Xmx768m` | §4 / §10 |
| `FileNotFoundException references.json.gz` | rodou fora de `fraudDetection/api/` ou não copiou o `.gz` | §0 / §9 |
| `approved` certo mas `fraud_score` errado | erro na fórmula 14D (cheque `v[4]` dia-da-semana, `v[11]` invertido, `v[12]` default 0.5) | §6 Test point 4 |
| Trava no boot pra sempre | parser do dataset não acha `]` (formato inesperado) | §4 — `zcat | head -c 200` e confira o `[{...}]` |
| `v[5]/v[6]` ≠ -1 com `last_transaction:null` | branch do `null` não detectado | §6 (`b.get(ltVal) == 'n'`) |
| 2ª request na mesma conn mistura dados | `reset()` sem `headerNameEnd`/`fraudCount` | §3 |

---

## §11. Pegadinhas (resumo final)

| ⚠️ Pegadinha | Referência |
|---|---|
| `matchMethod` testa `start+2` 2× → `METHOD_POST` morto | §0 #1 (BLOQUEADOR) |
| `references.json.gz` é UM array numa linha (284 MB) — `readLine()` = OOM | §4 |
| Índice 6 vem de `last_transaction.km_from_current` (nome ≠ da dimensão) | §6 |
| `last_transaction: null` → `v[5]=v[6]=-1`, não clamped, não filtrado | §6 / `REGRAS_DE_DETECCAO.md` |
| Índice 11 invertido (`1` = desconhecido); `known_merchants` pode ter duplicatas | §6 |
| Índice 12 default `0.5`; `mcc` é string `"5912"` | §6 |
| `day_of_week`: seg=0..dom=6 (algoritmo civil-days, não `java.time` no hot path) | §6 |
| Distância ao quadrado (sem `sqrt`) — ranking idêntico, otimização deliberada | §5 |
| 6 respostas canned, não 12 (`approved` deriva de `fraud_score`) | §8 |
| `-Xmx768m` obrigatório (≈220 MB heap p/ `float[3M][14]`) | §4 / §10 |
| Não commitar `references.json.gz` (48 MB) — `.gitignore` | §0 |
| Rodar de `fraudDetection/api/` (caminho relativo do dataset) | §9 |
| Não usar payloads de teste como referência (regra da Rinha) | `REGRAS_DE_DETECCAO.md` |

Mapa de armadilhas indexado por sintoma: `RINHA_PLAN.md` §12.

---

## §12. Próximos passos

**Onda 1 fechada**: `POST /fraud-score` correto vs o oráculo do `REGRAS_DE_DETECCAO.md`. p99 ~30 ms (brute force) — esperado. Próximas ondas (vide `RINHA_PLAN.md` §9 e `COMECE_AQUI.md`):

- **Onda 2 — quantização int8 + SIMD** (`RINHA_PLAN.md` §9.2, §5.6/§5.7; `CONCEITOS.md` §4/§5): `dataset/BinaryFormat` + `MmapDataset` v2 (binário `int8` via `MappedByteBuffer` — mata o parse de 3M e cai pra ~50 MB off-heap), `knn/Quantizer`, `DistanceFunctions` v2 com `jdk.incubator.vector` (AVX2). Cuidado: `byte` signed (`& 0xFF`) e soma de quadrados em `int32` (`RINHA_PLAN.md` §12.11/§12.12).
- **Onda 3 — HNSW hand-rolled** (`RINHA_PLAN.md` §9.3, §5.5; `CONCEITOS.md` §3): substituir o brute force do `HnswIndex` por grafo multi-camada; validar recall ≥95% **contra o baseline desta Onda 1**.
- **Onda 4 — conteinerização + k6 oficial** (`RINHA_PLAN.md` §9.4): HAProxy TCP, docker-compose, gating de `/ready` por readiness real.
- **Onda 5 — GraalVM Native Image + PGO** (`RINHA_PLAN.md` §9.5).

Tutorial seguinte sugerido: `docs/TUTORIAL_INT8_SIMD.md` (a criar depois desta Onda 1 fechar e os 2 oráculos do `REGRAS_DE_DETECCAO.md` confirmarem `{approved, fraud_score}` corretos).

---

**Boa sorte. Cada test point é uma vitória.** 🏁
