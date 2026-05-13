# Vector API (`jdk.incubator.vector`)

**Categoria**: SIMD em Java
**Versão usada na Rinha**: incubator do Java 21
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.6

---

## O que é

Vector API é um módulo **incubator** (preview) da JVM que permite escrever código que **opera em vetores SIMD** (Single Instruction, Multiple Data) de forma **portátil**. O compilador (HotSpot C2 ou GraalVM) gera intrínsecos nativos: AVX2, AVX-512, NEON (ARM), SVE.

Antes do Vector API, fazer SIMD em Java exigia **JNI/C** ou esperar o vectorizer automático do C2 acertar (raramente acerta loops complexos). Vector API dá controle explícito.

## Objetivo geral

Aproveitar **paralelismo intra-CPU**: registradores grandes (256-bit em AVX2 = 32 bytes) que fazem a mesma operação em múltiplos lanes simultaneamente. Em loops embaraçosamente paralelos (distance kernel, dot product, image processing), SIMD entrega 4-32× speedup **sem custo de threads**.

Para conceito profundo de SIMD, ver `../CONCEITOS.md` §5.

## Pra que vamos usar no projeto

**Distance kernel** em `knn/DistanceFunctions.java#euclideanInt8`. Cada query do HNSW executa ~1500 chamadas de distância. Com SIMD em int8 (32 lanes em AVX2), uma chamada custa ~5-10 ns vs ~50-100 ns escalar.

Resultado em latência: ~10 µs SIMD vs ~150 µs escalar para o conjunto de distâncias de uma query. **Crítico** para fechar p99 ≤ 1 ms.

## Como funciona (em profundidade)

### Espécies vetoriais (`VectorSpecies`)

```java
import jdk.incubator.vector.*;

static final VectorSpecies<Byte>  SP_B   = ByteVector.SPECIES_256;     // 32 lanes int8
static final VectorSpecies<Short> SP_S   = ShortVector.SPECIES_256;    // 16 lanes int16
static final VectorSpecies<Integer> SP_I = IntVector.SPECIES_256;      //  8 lanes int32
static final VectorSpecies<Float> SP_F   = FloatVector.SPECIES_256;    //  8 lanes float32
```

`SPECIES_256` força AVX2 (256-bit). `SPECIES_PREFERRED` deixa o JVM escolher o maior disponível.

### Operações típicas

```java
// Carregar do array
ByteVector a = ByteVector.fromArray(SP_B, vec1, 0);
ByteVector b = ByteVector.fromArray(SP_B, vec2, 0);

// Aritmética
ByteVector sum  = a.add(b);
ByteVector diff = a.sub(b);
ByteVector prod = a.mul(b);

// Conversão entre tipos
ShortVector wide = a.convertShape(VectorOperators.B2S, SP_S, 0).reinterpretAsShorts();
IntVector   wider = wide.convertShape(VectorOperators.S2I, SP_I, 0).reinterpretAsInts();

// Reduções
int sumOfSquares = wide.mul(wide).convertShape(VectorOperators.S2I, SP_I, 0)
                       .reinterpretAsInts()
                       .reduceLanes(VectorOperators.ADD);

// Comparação (gera máscara)
VectorMask<Byte> mask = a.compare(VectorOperators.GT, b);
```

### Distance kernel da Rinha

```java
public static int euclideanInt8(byte[] a, byte[] b) {
    ByteVector va = ByteVector.fromArray(SP_B, a, 0);
    ByteVector vb = ByteVector.fromArray(SP_B, b, 0);
    
    // Promover para int16 antes de subtrair (overflow signed)
    ShortVector aWide = (ShortVector) va.convertShape(VectorOperators.B2S, SP_S, 0);
    ShortVector bWide = (ShortVector) vb.convertShape(VectorOperators.B2S, SP_S, 0);
    
    ShortVector diff = aWide.sub(bWide);
    
    // (diff)² em int32 (somatório de 14 quadrados estoura int16)
    IntVector sq = (IntVector) diff.mul(diff)
                                   .convertShape(VectorOperators.S2I, SP_I, 0);
    
    return sq.reduceLanes(VectorOperators.ADD);
}
```

Em 14 dimensões, **cabe num único registrador AVX2** com lanes não-usados ignorados via mask.

### Validação de intrínsecos

```bash
# HotSpot
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -jar fraud-api.jar 9999 2>&1 | grep euclideanInt8

# GraalVM
java -Dgraal.PrintCompilation=true -jar fraud-api.jar 9999 2>&1 | grep -i vector
```

Procurar instruções `vpsubb`, `vpmullw`, `vphaddd` no assembly — confirma AVX2.

### `--enable-preview` e `--add-modules`

Vector API ainda é incubator. Compilação:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>21</release>
    <compilerArgs>
      <arg>--enable-preview</arg>
      <arg>--add-modules</arg><arg>jdk.incubator.vector</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

## Exemplo de uso

```java
import jdk.incubator.vector.*;

public class Sum {
    static final VectorSpecies<Float> SP = FloatVector.SPECIES_PREFERRED;
    
    public static float sum(float[] a) {
        FloatVector acc = FloatVector.zero(SP);
        int i = 0;
        for (; i < SP.loopBound(a.length); i += SP.length()) {
            FloatVector va = FloatVector.fromArray(SP, a, i);
            acc = acc.add(va);
        }
        // Tail (resto que não cabe num vetor cheio)
        float tail = 0;
        for (; i < a.length; i++) tail += a[i];
        return acc.reduceLanes(VectorOperators.ADD) + tail;
    }
}
```

```bash
javac --enable-preview --release 21 \
      --add-modules jdk.incubator.vector Sum.java
java  --enable-preview --add-modules jdk.incubator.vector Sum
```

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **JNI + C com AVX2 intrinsics** | Controle absoluto, sem incubator | JNI overhead (50-200 ns/call), build complexo, não-portátil | Quando profile prova que JNI overhead é amortizado |
| **`Unsafe`** | Memória off-heap, perf alta | API privada, removida em versões futuras | Já está em código legacy |
| **Project Panama Foreign Function** | Substituto do JNI moderno (Java 22+) | Em maturação | Quando 22+ tiver Vector API estável |
| **C2 auto-vectorizer** | Sem código novo, "magia" | Frágil — falha em loops complexos sem aviso | Loops simples (sum, copy) |
| **escalar puro** | Mais simples | 4-32× mais lento | Prototipação, baseline para JMH comparar |

Na Rinha, Vector API ganha por dar controle SIMD com perf de C **e** ser portátil entre AVX2/AVX-512/NEON.

## Pegadinhas conhecidas

1. **Overflow signed em int8**: `(byte)127 - (byte)(-128) = ?`. Em escalar Java, é `255` mas cabe em `int`. Em SIMD com `ByteVector.sub()`, se você não promover para `int16` primeiro, a subtração satura. **Sempre promover para `int16`**.
2. **Soma de quadrados estoura int16**: 14 lanes × `(127-(-128))² = 65025` = `910k`, não cabe em int16 (max 32767). **Promover para int32 na soma.** (§12.12 do `../RINHA_PLAN.md`.)
3. **Fallback escalar silencioso**: se o JIT/AOT não consegue gerar SIMD, gera código escalar — sem aviso. Latência piora 4-10× sem mensagem. Detectar com profiler/logs de compilação.
4. **Native Image regressões em Mandrel 22/23**: SIMD pode regredir. Usar Mandrel 21 LTS.
5. **Tail handling**: se `array.length` não é múltiplo de `SP.length()`, precisa do loop residual (escalar) para os últimos elementos. Em 14 dims com `SPECIES_256` (32 lanes), tudo cabe num só vetor com mask.
6. **`reinterpretAsShorts()` vs `convertShape()`**: o primeiro reinterpreta bits (sem mudar layout), o segundo converte (estende ou trunca). Confundir gera bugs sutis.

## Referências

- **JEP 448 (Vector API 6)**: https://openjdk.org/jeps/448
- **Vector API javadoc**: https://docs.oracle.com/en/java/javase/21/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html
- **Tutorial Oracle**: https://docs.oracle.com/en/java/javase/21/core/vector-api.html
- **Richard Startin's blog** (autor de muitos benchmarks): https://richardstartin.github.io/posts/
- **Awesome Java SIMD**: https://github.com/awesome-java-simd
- **Performance comparison Vector API vs JNI**: https://blog.openjdk.org/topics/vectorapi
- **AVX2 reference**: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/index.html

## Veredito final na Rinha

Vector API é o coração do hot path. Sem ele, brute force int8 ainda demoraria milissegundos. **Onda 2** introduz, **Onda 5** valida que Native Image não regrediu.
