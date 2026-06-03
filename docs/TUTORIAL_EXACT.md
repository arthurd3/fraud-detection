# Tutorial — Onda 7: acurácia exata (int16 lossless + rerank exato + escalonamento) → mirar ~6000

> De **Onda 6** (`final_score` ~4393; p99 já no teto = `score_p99` 3000;
> `score_det` 1393) → **fechar o gap de detecção** p/ ~6000 (líderes têm
> `score_det`≈3000 ⇒ E≈0). **Reabre o projeto** (Onda 5 era "submissão
> válida", não topo do ranking). Spec:
> `docs/superpowers/specs/2026-05-18-onda7-exact-accuracy-design.md`.
> **Tempo estimado**: 1–2 dias (mudança grande no core + rebuild offline +
> tuning). **Pré-req de validação**: daemon Docker + k6 (já temos).

> ⚠️ **Escopo.** 100 % do gap p/ 6000 é **ACURÁCIA** (FP=61, FN=103 ⇒
> E=370). NÃO é p99/memória/alloc. Reproduzimos o **ground truth EXATO** sem
> estourar p99 ≤ 1 ms. Tutorial-driven: você implementa à mão **ou** a
> implementação é feita diretamente (override pontual, como Ondas 5/6).

---

## §0. Visão geral, o que muda, critério de saída

O `data-generator/main.c` rotula `expected_approved` assim: vetor 14-dim
normalizado (constantes **idênticas** às nossas) → **`round4`** →
kNN k=5 squared-euclidean **brute exato** sobre as refs round4 →
`approved = fraud_n/5 < 0.6` (fraude se ≥3 dos 5), tie-break **menor
índice**. Nós divergimos por: **int8 ×127** (perde precisão vs round4) e
**HNSW aproximado**. Solução **B3**: representar tudo em **int16 ×10000**
(lossless vs round4) + rerank **exato** sobre um pool HNSW de ef alto +
**escalonar** só as decisões frágeis.

### Inventário

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/Quantizer.java` | +`q16` (int16 ×10000) |
| 2 | `dataset/MmapDataset.java` | formato **RB3** int16 + build do `.gz` |
| 3 | `knn/DistanceFunctions.java` | +`sqDistI16` (acum. **long**) + `sqDistExactC` (double, igual ao C) |
| 4 | `knn/HnswBuilder.java`/`HnswGraph.java` | distância int16; rebuild offline; RBH2 inalterado |
| 5 | `knn/HnswIndex.java` | pool ef-alto + rerank double id-tiebreak + escalonamento |
| 6 | `json/FraudRequestParser.java` | `round4` na saída |
| 7 | `tools/Prebuild.java` | regenera `references.bin` RB3 + `hnsw.bin` |
| 8 | `src/test/.../ExactAgree.java` | **novo** — G2/G3 |
| 9 | `RecallHnsw`/`Rbh2Equiv`/`AllocCheck` | adaptar ao RB3/int16 |
| — | normalização, resposta, infra container/nativa | **inalterado** |

### Critério de saída (= a métrica do ranking)

- **G1 (bloqueia):** k6 oficial → `final_score` **≥ ~5900** (alvo 6000),
  `http_errors` 0, **p99 ≤ 1 ms** (sem corte).
- **G2 (bloqueia):** harness offline vs `expected_*` nos **54.100** →
  **0 divergências** (ou mínimo documentado).
- **G3 (bloqueia):** prova de exatidão (rerank == brute exato; brute ==
  `expected_*`).
- **G4 (bloqueia):** p99 ≤ 1 ms + cgroup ≤ 350 MB sem OOMKilled (dataset
  51→84 MB — **re-validar**).
- **G5 (regra):** anti-overfit — casar o **algoritmo**, não a amostra de
  54.100 (o teste FINAL oficial usa script diferente/mais pesado).

---

## §1. Princípios

1. **int16 ×10000 é lossless vs round4.** `round4(v)=round(v·10000)/10000`
   ⇒ `R(v)=round(v·10000)` é inteiro em `[-10000,10000] ⊂ int16`. Guardar
   `R(v)` em int16 é **exato**. Distância inteira `Σ(R(a)-R(b))²` em
   **`long`** tem a **mesma ordenação** das distâncias double do C p/
   distâncias distintas. ⚠️ **Acumular em `long`**: `(Δ≤20000)²·14 ≈ 5,6e9
   > 2³¹` — int32 **estoura**.
2. **Empates: rerank final em `double` igual ao C.** Distâncias int16
   empatadas podem divergir do `<` estrito do C (que soma `double` com
   arredondamento). O top-5 **final** sobre o pool recomputa
   `a_i = R(norm_i)/10000.0; d = Σ(a_i-b_i)²` (mesma expressão do
   `euclidean_dist`) e ordena por **(dist, id) ascendente** (menor id vence)
   — byte-idêntico ao `knn_classify`.
3. **Escalonamento, não rescan.** p99 = 99º percentil: >1 % das reqs num
   caminho lento ⇒ p99 = tempo lento ⇒ `score_p99` desaba. A verificação
   exata é HNSW de ef **muito** alto (ms-limitado), **nunca** scan de 3M.
4. **Anti-overfit.** Casar o algoritmo do `main.c`, não os mismatches
   específicos dos 54.100.
5. **Mudança grande.** O hot path int8 (Onda 2a/2b) é superado; int8 vira
   oráculo legado. Gates G2/G3 provam equivalência ao exato.

---

## §2. `knn/Quantizer.java` — `q16` int16 ×10000

Mantém `q` (int8, legado/oráculo) e acrescenta:

```java
/** Onda 7: int16 ×10000 — lossless vs o round4 do ground truth. */
public static short q16(float v) {
    return (short) Math.round(clamp(v) * 10000f);   // clamp(-1,1) já existe
}
public static void quantize16(float[] src, short[] dst) {
    for (int i = 0; i < 14; i++) dst[i] = q16(src[i]);
}
```

🔍 **TP1.** `clamp(-1f)*10000 = -10000`, `clamp(1f)*10000 = 10000` — cabem em `short`.

---

## §3. `dataset/MmapDataset.java` — formato `RB3` int16

**Layout RB3** (big-endian, consistente com RB2/RBH2):
```
| 4 B magic 'R','B','3',0 | 4 B count(int32) | 4 B dims=14(int32) |
| count × (14 × int16 BE)  |  count × 1 B labels (0=legit,1=fraud) |
```
- `STRIDE = 28`; `recBase(i) = HEADER + i*28`; `lblBase = HEADER + count*28`.
- Leitura de record: `short[14]` via `data` (`ByteBuffer` BE) — método
  `getRec(int i, short[] out)` (espelha o `data.get(recBase, byte[], 0,16)`
  do RB2, agora 14 shorts BE).
- `build(gzPath, bin)`: ler os **floats round4** do `references.json.gz`
  (mesmo parser do RB2) e gravar `q16(f)` (2 B BE) por dim; labels igual ao
  RB2 (`first=='f'?1:0`). `isRB3` checa o magic. (Mantém o build RB2 antigo
  só se algum oráculo legado precisar; produção = RB3.)

🔍 **TP2.** `xxd -l 4 references.bin` → `52 42 33 00` (`RB3`); tamanho ≈
`12 + 3_000_000*28 + 3_000_000` ≈ **87 MB**.

---

## §4. `knn/DistanceFunctions.java` — int16 (rápido) + double (exato igual ao C)

```java
/** Hot path HNSW: squared int16, acumulador LONG (int32 estoura). */
public static long sqDistI16(short[] q, short[] v) {
    long acc = 0;
    for (int k = 0; k < 14; k++) { int d = q[k] - v[k]; acc += (long) d * d; }
    return acc;
}
/** Rerank EXATO: replica euclidean_dist do main.c (double sobre round4). */
public static double sqDistExactC(short[] qR, short[] vR) {  // qR/vR = R(v)=round(v*1e4)
    double s = 0;
    for (int k = 0; k < 14; k++) {
        double a = qR[k] / 10000.0, b = vR[k] / 10000.0;     // = round4(v) exato
        double d = a - b; s += d * d;
    }
    return s;
}
```

> `sqDistExactC` reproduz **bit-a-bit** a expressão do C
> (`d=a[i]-b[i]; sum+=d*d` em `double`, sem `sqrt`), inclusive o
> arredondamento que decide empates.

---

## §5. `knn/HnswBuilder.java` / `HnswGraph.java` — distância int16 (rebuild offline)

- Trocar `sqDistI8Scalar(byte[],byte[])` por `sqDistI16(short[],short[])` no
  cálculo de distância do build e da busca; record agora é `short[14]`
  (scratch `short[]` em vez de `byte[16]`).
- **RBH2 (formato do grafo) inalterado** — guarda só ids de vizinhos,
  independe da precisão do vetor. Mas a **topologia muda** (distâncias int16
  ≠ int8) ⇒ **rebuild offline** de `hnsw.bin` (via `tools.Prebuild`).
- Re-tunar `M` / `ef_construction` (grid-search, §12) p/ recall de **pool**
  ~100 % (objetivo: o pool conter os 5 verdadeiros quase sempre).

---

## §6. `knn/HnswIndex.java` — pool ef-alto + rerank exato + escalonamento

```java
public static void search(ConnectionState s) {
    // 1) HNSW int16, ef alto -> POOL de candidatos (resultado + visitados L0)
    int poolN = collectPool(s.queryR /*short[14]*/, EF_POOL);   // EF_POOL alto (tunar)
    // 2) top-5 EXATO sobre o pool, distancia DOUBLE igual ao C, tie-break (dist,id) asc
    rerankExactTop5(s.queryR, pool, poolN, s.knn5);             // menor id vence empate
    int fraud = countFraud(s.knn5);                              // 0..5
    // 3) ESCALONAR so se a decisao e fragil:
    boolean ambiguous = (fraud == 2 || fraud == 3)               // 1 vizinho flipa
                     || margin5to6(pool, poolN) < EPS;           // 5o vs proximos
    if (ambiguous) {
        int poolN2 = collectPool(s.queryR, EF_DEEP);             // ef MUITO alto (ms-limitado)
        rerankExactTop5(s.queryR, pool, poolN2, s.knn5);
        fraud = countFraud(s.knn5);
    }
    s.fraudCount = fraud;   // approved = fraud < 3  (== fraud_score < 0.6)
}
```

- `collectPool`: a busca por camadas usa `sqDistI16` (rápido); junta o
  resultado + os nós visitados de L0 num buffer reusado (zero-alloc — herda
  Onda 6).
- `rerankExactTop5`: insertion-sort de 5 com `sqDistExactC`; **empate →
  menor `id` de referência** (igual ao `i=0..N-1` do `main.c`). Itere o
  pool e, em empate de distância, fique com o menor id.
- `EF_POOL`, `EF_DEEP`, `EPS` = **parâmetros tunados** (§12) p/ maximizar
  `final_score` com p99 ≤ 1 ms. Comece `EF_POOL≈512`, `EF_DEEP≈4096`,
  `EPS≈1e-9` e ajuste pelos Gates.
- `top5Brute` (oráculo) passa a usar `sqDistExactC` sobre **toda** a base
  (G3, offline; NUNCA em produção).

---

## §7. `json/FraudRequestParser.java` — `round4` na saída

A normalização (constantes/algoritmo) **já é idêntica** ao `main.c`. Só
falta o `round4`: onde hoje grava `v[i]=(float)valor`, gravar o inteiro
`R = Math.round(valor*10000)` (clamp a ±10000) — e o `queryR[i]=(short)R`.
O rerank §4 reconstrói `R/10000.0` p/ casar a expressão `double` do C.

> ⚠️ Use `Math.round(double)` (não `(float)`): o C faz `round4` em `double`.
> Diferença de 1 ULP na 4ª decimal vira mismatch sistemático — o **G3(b)**
> pega isso comparando brute exato vs `expected_*` nos 54.100.

---

## §8. `tools/Prebuild.java` — regenerar RB3 + hnsw (offline, 1×)

`Prebuild` passa a: (1) `MmapDataset.build(gz, references.bin)` no formato
**RB3**; (2) `HnswBuilder.build(hnsw.bin)` sobre RB3 (distância int16).
Roda **offline** (`-Xmx2g`), versiona nada (binários gitignored), commit
não inclui `.bin`. Container/nativo só **consome** (Onda 4a/4b/5).

🔍 **TP3.** Após `Prebuild`: `xxd -l4 references.bin`=`RB3`; `hnsw.bin`
regenerado (mtime novo); `Rbh2Equiv` adaptado = 0-div.

---

## §9. `src/test/java/org/fraudDetection/ExactAgree.java` — G2/G3 (novo)

Harness offline (molde `RecallHnsw`/`Rbh2Equiv`; `main`, sem JUnit):

```java
// Carrega RB3 + hnsw. Para cada entrada de ../../rinha-de-backend-2026/test/test-data.json:
//   - parseia o request (FraudRequestParser) -> queryR (short[14], round4)
//   - approvedOurs  = (search() fraudCount < 3)
//   - top5Brute exato (sqDistExactC sobre 3M) -> approvedBrute
//   - le expected_approved / expected_fraud_score do test-data.json
// G3(a): approvedOurs == approvedBrute  (rerank == exato)            -> 0 div
// G3(b): approvedBrute == expected_approved (lossless+round4 ok)     -> 0 div
// G2   : approvedOurs == expected_approved nos 54.100                -> 0 div (alvo)
// imprime: mismatches G2, G3a, G3b + (fp,fn) e o E resultante.
```

> Se `TestDataReader` não expõe `expected_*`, parseie o `test-data.json`
> direto aqui (campos `expected_approved`/`expected_fraud_score`) — o que
> importa é comparar nossa decisão à verdade nos 54.100.

`Rbh2Equiv`/`RecallHnsw`/`AllocCheck`: adaptar leitura de record p/ RB3
(`short[14]`); semântica dos gates preservada.

---

## §10. Gates (rodar nesta ordem)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw -q clean package
# G3 (exatidao) + G2 (vs expected_* nos 54100):
java -Xmx2g -cp target/classes:target/test-classes org.fraudDetection.ExactAgree
#   => G3a 0 div ; G3b 0 div ; G2 mismatches=0 (ou minimo)  -> E -> ~0
java -Xmx512m -cp target/classes:target/test-classes org.fraudDetection.Rbh2Equiv   # sanity grafo
# G1 + G4 (a metrica do ranking):
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection && docker build -t docker.io/arthurd3/rinha-fraud:onda7 . && docker compose up -d
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9999/ready    # 200
cd ../rinha-de-backend-2026 && ./run.sh    # final_score >= ~5900 (alvo 6000), http_errors 0, p99 <= 1ms
# G4: docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' ... | cat ; OOMKilled=false ; sem corte
cd - && docker compose -f docker-compose.yml --project-directory . down
```

**PASS** = G2/G3 0-div (E≈0) **e** G1 `final_score` ≥ ~5900 com p99 ≤ 1 ms
sem cortes **e** G4 cgroup ≤ 350 MB sem OOMKilled.

---

## §11. Tuning (§12 do spec) — só depois dos gates verdes

- **Grid-search offline** `M ∈ {16,24,32}`, `ef_construction ∈ {200,400,800}`:
  rebuild + medir recall de pool (G3a) → escolher o menor que dá pool-recall
  ~100 %.
- **Runtime** `EF_POOL`/`EF_DEEP`/`EPS`: subir até G2=0; descer até G1 p99
  voltar a ≤ 1 ms. O ótimo maximiza `final_score` (medir, não chutar).
- **Anti-overfit (G5):** valide que o ajuste é do **algoritmo** (ε pequeno
  genérico), não dos 164 mismatches específicos.

---

## §12. Pegadinhas

| ⚠️ | Detalhe |
|---|---|
| acumulador int16 | **`long`** — `(Δ≤20000)²·14 ≈ 5,6e9 > 2³¹` estoura int32 |
| empate de distância | rerank final em **`double` igual ao C** + menor `id`; int sozinho diverge |
| `round4` da query | `Math.round(v*10000)` em **double**, não `(float)` (1 ULP vira mismatch) |
| escalonamento | ef-profundo, **nunca** scan de 3M (p99 = 99º pct: >1 % lento mata o 3000) |
| dataset 51→84 MB | re-validar G4 (cgroup 350 MB; mmap reclaimável — arg. mem. Onda 4a) |
| big-endian RB3 | consistente com RB2/RBH2; G3 valida a leitura |
| overfit | teste FINAL oficial difere; casar algoritmo, não os 54.100 |
| snap docker (G1/G4) | `--format \| cat`; build/compose sob `$HOME`; `docker build --progress=plain 2>&1 \| cat` |
| scripts | sem `set -u`; matar server por PID da porta; **commits sob a identidade `arthurd3`** |

---

## §13. Próximos passos

**Onda 7 fechada** = G1 (`final_score` ≥ ~5900, alvo 6000, p99 ≤ 1 ms) +
G2/G3 (E≈0, exatidão provada) + G4 (cgroup/p99) verdes; imagem `:onda7`
pública; `submission` → `:onda7`.

- Você implementa à mão a partir deste tutorial **ou** a implementação é feita
  diretamente (override, como Ondas 5/6) → os gates G1–G4 são validados e o as-built reconciliado
  (ARCHITECTURE/README/RINHA_PLAN; Qdrant + `MEMORY.md`).
- Outward-facing (suas): `docker push docker.io/arthurd3/rinha-fraud:onda7`,
  `git push origin main`/`submission`, atualizar o PR / abrir issue
  `rinha/test arthurd3-java-hnsw` p/ a prévia oficial.

**Onda 7 é a corrida pelo topo do ranking — não há "fim" enquanto E>0 e
houver gap p/ 6000.** 🎯🏆
