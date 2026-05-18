# Tutorial — Onda 6: `takeTop5` zero-alloc + LICENSE (otimização final, opcional)

> De **Onda 5** (Native + PGO, projeto fechado: `final_score` 4393,85, p99
> 0,59 ms) → fechar a **única "honest exception"** ao hot path zero-alloc e
> adicionar o `LICENSE`. **Tempo estimado**: 30–60 min. **ZERO mudança de
> comportamento** (saída byte-idêntica; sem alvo de score). Spec:
> `docs/superpowers/specs/2026-05-18-onda6-zeroalloc-license-design.md`.

> ⚠️ **Escopo (2026-05-18).** O projeto **já fechou tecnicamente na Onda 5**.
> A Onda 6 é **OPCIONAL** (`RINHA_PLAN.md` §9.6) — nada aqui é necessário p/
> a entrega. São **2 linhas + 2 campos** de Java + 2 arquivos novos
> (`LICENSE`, `AllocCheck`). Rebuild nativo/`submission` = **opcional** (§8).

---

## §0. Visão geral, o que muda, critério de saída

`HnswIndex.takeTop5` (caminho de produção HNSW) aloca **dois `int[n]` por
query** (`n = HnswScratch.rSize ≤ efSearch ≈ 50`) só p/ drenar o max-heap de
resultado em ordem crescente. A saída (`s.knn5`) **já** é reusada;
`top5Brute` (oráculo) **não** usa `takeTop5`. Logo este é o **último**
alloc por-request do hot path. Trocamos os dois `new int[n]` por **buffers
reusados** em `HnswScratch` (mesmo molde de `rN/rD/cN/cD`). Drain idêntico
⇒ **saída byte-idêntica por construção**.

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `api/src/main/java/org/fraudDetection/knn/HnswScratch.java` | **alterado** — +campos `tN`/`tD`; +2 linhas em `init()` |
| 2 | `api/src/main/java/org/fraudDetection/knn/HnswIndex.java` | **alterado** — 1 linha em `takeTop5` |
| 3 | `LICENSE` (raiz `fraudDetection/`) | **novo** — MIT, `Copyright (c) 2026 arthurd3` |
| 4 | `api/src/test/java/org/fraudDetection/AllocCheck.java` | **novo** — harness do Gate 2 |
| — | `hnsw.bin`/`references.bin`/demais Java | **inalterado** |

### Critério de saída da Onda 6

- **Gate 1 (bloqueia):** `RecallHnsw 2000 50` recall@5 **96,89 %** /
  approved-agree **99,90 %** (FP=1 FN=1) **idêntico à Onda 5**; `Rbh2Equiv`
  0/3.000.000; 2 oráculos byte-exatos (jar HotSpot).
- **Gate 2 (bloqueia):** `AllocCheck` → Δ alloc/query ≈ **0 B** no
  `top5Hnsw` (baseline pré-Onda-6 ≈ **400 B/query**).
- **Gate 3 (mede, não bloqueia):** opcional — k6 oficial `final_score`
  ≥ **4393**, p99 ≈ 0,59 ms (remover alloc só ajuda/é neutro).

---

## §1. Princípios

1. **Hot path zero-alloc de verdade.** Depois da Onda 6 o caminho de
   produção (`search` → `top5Hnsw` → `searchLayer`/`takeTop5`) **não aloca
   nada por requisição** — só a alocação **única** de bootstrap (dataset/
   grafo/scratch) resta.
2. **Byte-idêntico.** O laço de drain, `k = min(5,n)`, o fill de `out` e o
   padding `-1` **não mudam**. Só a *fonte* do buffer muda
   (`new int[n]` → scratch reusado). Gate 1 prova.
3. **Padrão existente.** `HnswScratch` já tem `rN/rD/cN/cD` = `int[CAP]`
   alocados 1× em `init()`. `tN/tD` seguem o mesmo molde — nada novo
   conceitualmente.
4. **`td` é write-only.** Hoje `td[i]` é escrito no drain e nunca lido (só
   `tn` alimenta `out`). **Mantemos** `td` como scratch reusado: o diff
   fica mecanicamente byte-idêntico. (Remover `td` é mudança separada —
   fora do escopo; YAGNI.)
5. **Tutorial-driven.** Você implementa à mão; o Claude valida os gates e
   reconcilia docs as-built. Commits em `main`, sem atribuição Claude, sem
   push.

---

## §2. `knn/HnswScratch.java` — buffers reusados `tN`/`tD`

Acrescente os campos ao lado de `rN/rD` e aloque-os em `init()` (mesmo
`CAP = 1<<15`; `rSize ≤ efSearch ≤ CAP` sempre — invariante já assumida por
`rN/rD`; ~256 KB anônimos **1×**):

```java
    // resultado: MAX-heap por dist (raiz = mais distante; evict quando passa de ef)
    public static int[] rN, rD; public static int rSize;
    // drain do top-5 (reusado — zero-alloc por query; Onda 6)
    public static int[] tN, tD;
```

```java
    public static void init(int n) {
        count = n;
        visited = new int[n]; gen = 0;
        cN = new int[CAP]; cD = new int[CAP];
        rN = new int[CAP]; rD = new int[CAP];
        tN = new int[CAP]; tD = new int[CAP];   // Onda 6
        bufA = new byte[16]; bufB = new byte[16];
    }
```

🔍 **Test point 1.** `cd api && ./mvnw -q clean package` → exit 0 (compila).

---

## §3. `knn/HnswIndex.java` — `takeTop5` usa o scratch (1 linha)

Troque **apenas** a linha que aloca:

```java
    private static int takeTop5(int[] out) {
        // drena o max-heap; os 5 menores ficam no fim → reordena
        int n = HnswScratch.rSize;
        int[] tn = HnswScratch.tN, td = HnswScratch.tD;   // Onda 6: scratch reusado (era: new int[n], new int[n])
        for (int i = n-1; i >= 0; i--) { td[i]=HnswScratch.rMaxDist(); tn[i]=HnswScratch.rMaxNode(); HnswScratch.rPopMax(); }
        int k = Math.min(5, n);
        for (int i = 0; i < 5; i++) out[i] = i < k ? tn[i] : -1;
        return k;
    }
```

> **Só a linha `int[] tn = … , td = …;` muda.** Tudo abaixo dela é
> idêntico ao código atual; índices `0..n-1` são usados, o resto do buffer
> `CAP` é ignorado. Comportamento **byte-idêntico** por construção.

🔍 **Test point 2.** `RecallHnsw 2000 50` (§6) → números **idênticos** à
Onda 5.

---

## §4. `LICENSE` (raiz `fraudDetection/`)

Crie `fraudDetection/LICENSE` com o texto **MIT** padrão:

```
MIT License

Copyright (c) 2026 arthurd3

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

> `.dockerignore` já ignora `*.md` mas **não** `LICENSE` (sem extensão) —
> ele entra no contexto, é inofensivo (o `Dockerfile` só `COPY api/`). Não
> precisa ajustar nada.

---

## §5. `api/src/test/java/org/fraudDetection/AllocCheck.java` — Gate 2

Harness auto-contido (molde dos demais `src/test` — `main`, sem JUnit).
Mede `getThreadAllocatedBytes` em volta de N chamadas `top5Hnsw`
**pós-warmup**. Reusa vetores do próprio dataset como queries (a alocação
do `takeTop5` independe da query — depende só do drain), então não precisa
do `test-data.json`:

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;

import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

/** Gate 2 (Onda 6): prova que top5Hnsw é zero-alloc por query (HotSpot). */
public final class AllocCheck {
    public static void main(String[] args) throws Exception {
        // mesmo load dos demais harnesses (RecallHnsw/Rbh2Equiv)
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");

        int Q = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;

        // queries = primeiros Qv records do dataset (byte[16] já quantizado no .bin)
        int Qv = 256;
        byte[][] qs = new byte[Qv][16];
        for (int i = 0; i < Qv; i++)
            MmapDataset.data.get(MmapDataset.recBase(i), qs[i], 0, 16);

        int[] out = new int[5];

        // warmup (JIT + bootstrap do scratch fora da medição)
        for (int i = 0; i < 20_000; i++) HnswIndex.top5Hnsw(qs[i % Qv], out);

        ThreadMXBean tb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long a0 = tb.getThreadAllocatedBytes(tid);
        for (int i = 0; i < Q; i++) HnswIndex.top5Hnsw(qs[i % Qv], out);
        long a1 = tb.getThreadAllocatedBytes(tid);

        double perQ = (a1 - a0) / (double) Q;
        System.out.printf("alloc: %d bytes em %d queries -> %.2f B/query%n",
                          (a1 - a0), Q, perQ);
        // PASS = ~0 B/query (pré-Onda-6: ~2*rSize*4 ≈ 400 B/query)
        boolean pass = perQ < 16.0;            // folga p/ ruído de medição
        System.out.println(pass ? "Gate 2: zero-alloc -> PASS"
                                 : "Gate 2: AINDA ALOCA -> FAIL");
        if (!pass) System.exit(1);
    }
}
```

> Se o `load`/leitura de record divergir das assinaturas reais, **espelhe o
> `RecallHnsw`/`Rbh2Equiv`** (são os harnesses canônicos do projeto) — a
> ideia do Gate é só medir Δ alloc do `top5Hnsw`, não a forma do load.

---

## §6. Gate 1 — comportamento byte-idêntico (bloqueia)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw -q clean package
java -Xmx256m -cp target/classes:target/test-classes org.fraudDetection.RecallHnsw 2000 50
#   => ef_search=50  recall@5=96.89%  approved-agree=99.90% (FP=1 FN=1) -> PASS   (IDÊNTICO à Onda 5)
java -Xmx512m -cp target/classes:target/test-classes org.fraudDetection.Rbh2Equiv
#   => Rbh2Equiv: 0 divergencias / 3000000 nos -> PASS
```

Oráculos (jar HotSpot — subir o server e `curl`, ou via o caminho que você
já usou nas ondas anteriores): `tx-1329056812`→`{"approved":true,
"fraud_score":0.0}`, `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`.

**PASS** = números **idênticos** à Onda 5 (a mudança é byte-idêntica).

---

## §7. Gate 2 — zero-alloc provado (bloqueia)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
java -Xmx256m -cp target/classes:target/test-classes org.fraudDetection.AllocCheck 100000
#   => alloc: ~0 bytes em 100000 queries -> ~0.00 B/query
#   => Gate 2: zero-alloc -> PASS
```

Compare mentalmente com o **baseline**: antes da Onda 6, `2 × rSize × 4 B`
≈ **400 B/query** (`rSize≈50`). Depois: ~0.

---

## §8. (Opcional) Rebuild nativo `:onda6` + `submission`

Onda 6 é behavior-idêntico ⇒ a `:onda5` continua válida. **Se** quiser
publicar a polish:

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
docker build -t docker.io/arthurd3/rinha-fraud:onda6 .
docker compose up -d
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9999/ready   # 200
# 2 oráculos pelo LB (idênticos); k6 oficial p/ Gate 3:
cd ../rinha-de-backend-2026 && ./run.sh   # final_score ≥ 4393 (≈ Onda 5), p99 ≈ 0.59ms
cd -; docker compose -f docker-compose.yml --project-directory . down
```

`submission` (orphan): bump `:onda5`→`:onda6` no `docker-compose.yml` (mesmo
padrão da 5b/5). `docker push`/`git push`/PR upstream/issue `rinha/test` =
**ações suas** (outward-facing).

---

## §9. Reconciliação as-built (Claude, quando validar)

- `docs/ARCHITECTURE.md` §5: a "one honest exception" **deixa de existir** —
  hot path de produção agora **zero-alloc de verdade**; nota datada.
- `docs/RINHA_PLAN.md` §9.6: `takeTop5` → feito; Onda 6 entregou só este
  item + `LICENSE`; resto do §9.6 segue opcional.
- `README.md`: badge/Tech stack `LICENSE: MIT`; remover "LICENSE pending".
- Notes de fechamento Onda 5: "LICENSE MIT" → feito.
- Memória (Qdrant + `MEMORY.md`).

---

## §10. Pegadinhas (resumo)

| ⚠️ | Detalhe |
|---|---|
| `efSearch > CAP` | invariante: `efSearch ≤ CAP=32768` (já assumido por `rN/rD`); não suba `efSearch` além disso sem subir `CAP` |
| medir antes do warmup | mede o JIT/bootstrap, não o steady — sempre warmup antes de `getThreadAllocatedBytes` |
| `getThreadAllocatedBytes` no Native | é API HotSpot; Gate 2 roda **no jar HotSpot** (a mudança é Java puro ⇒ HotSpot prova o zero-alloc; o nativo herda) |
| remover o `td` write-only | **não** fazer aqui — mudança separada, quebraria o "diff mecanicamente idêntico"; documentado, fora de escopo |
| achar que é obrigatório | Onda 6 é **opcional**; o projeto já fechou na Onda 5 |
| `set -u` / matar server por PID / snap docker | se for ao §8: valem as pegadinhas das ondas 4b/5 (`--format \| cat`, build sob `$HOME`, kill por porta) |
| sem atribuição Claude nos commits | regra permanente do projeto |

---

## §11. Próximos passos

**Onda 6 fechada** = Gate 1 (byte-idêntico) + Gate 2 (zero-alloc provado)
verdes [+ Gate 3 opcional]; `LICENSE` MIT presente; docs reconciliados.

- Você implementa à mão (HnswScratch +2 campos/+2 linhas, HnswIndex 1
  linha, `LICENSE`, `AllocCheck`) → Claude valida Gates 1/2 (3 opcional) e
  reconcilia as-built.
- Ações outward-facing (suas): `docker push :onda6` (se §8), `git push
  origin main`/`submission`, PR upstream, prévia oficial via issue
  `rinha/test`.

**Onda 6 é a última onda (opcional) — o projeto técnico já estava fechado
na Onda 5.** 🏁🏆
