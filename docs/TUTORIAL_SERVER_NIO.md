# Tutorial — Server NIO hand-rolled (Onda 1)

> Da `./mvnw compile` verde até `curl /ready` retornando `200 OK` em Java NIO puro, codando junto.
> **Tempo estimado**: 1-2 dias se nunca mexeu com NIO; 4-6h se já fez algo similar.

---

## §0. Pré-requisitos e visão geral em 30s

**Antes de começar, garanta:**

- ✅ Onda 0 fechada: `cd api && ./mvnw clean compile` retorna `BUILD SUCCESS` (`COMECE_AQUI.md` §4).
- ✅ Leituras feitas:
  - 📖 `docs/CONCEITOS.md` §6 (NIO Selector vs blocking I/O) — multiplexing conceitual
  - 📖 `docs/CONCEITOS.md` §9 (Zero-allocation hot path) — regras de allocation
  - 📖 `docs/CONCEITOS.md` §10 (HTTP/1.1 by-hand) — state machine HTTP
- ✅ Esqueleto vazio de `api/src/main/java/org/fraudDetection/server/NioServer.java` compila.

**O que vamos construir, em 1 minuto:**

```
                  +-----------+
   socket bytes-->| selector  |--accept-->[ConnectionState 1]
                  |  (single  |--read --->[ConnectionState 2]
                  |  thread)  |--write--->[ConnectionState 3]
                  +-----------+              ...
                       ^                     |
                       | OP_READ/OP_WRITE    |
                       +---------------------+
                       (loop volta após I/O)

Para cada conexão:
  bytes → readBuffer → HttpParser.parse(state) → handler → HttpResponseWriter.writeReady(state) → writeBuffer → bytes
```

São **5 arquivos novos** no total:

| # | Arquivo | Função |
|---|---|---|
| 1 | `server/NioServer.java` | Loop do `Selector`, dispatch para parser/handler |
| 2 | `server/ConnectionState.java` | Buffers e estado reutilizáveis por conexão |
| 3 | `server/HttpParser.java` | State machine HTTP/1.1 byte-a-byte |
| 4 | `server/HttpResponseWriter.java` | Respostas pré-construídas |
| 5 | `controllers/HealthController.java` | Handler do `/ready` |

**Critério de saída do tutorial**: `curl -v http://localhost:9999/ready` retorna `HTTP/1.1 200 OK` com keep-alive funcionando.

---

## §1. Mapa mental: fluxo de uma request `/ready`

Quando você roda `curl http://localhost:9999/ready`, isto acontece:

1. TCP handshake → kernel cria socket → server-side `ServerSocketChannel.accept()` retorna um `SocketChannel`.
2. `NioServer.accept()` atacha um `ConnectionState` ao channel e registra interesse `OP_READ`.
3. Kernel entrega bytes do `GET /ready HTTP/1.1\r\n...\r\n\r\n` → `Selector.select()` desbloqueia, `key.isReadable()` vira true.
4. `NioServer.read(key)` chama `channel.read(state.readBuffer)`.
5. `HttpParser.parse(state)` percorre `readBuffer`, popula `state.methodCode=GET`, `state.pathStart/pathEnd`, retorna `PARSE_DONE`.
6. `NioServer.dispatch()` confere: path bytes batem com `/ready` → chama `HealthController.handle(state, key)`.
7. `HealthController` chama `HttpResponseWriter.writeReady(state)`, que copia bytes prontos pra `state.writeBuffer`, e troca o interest da key para `OP_WRITE`.
8. Próximo loop do selector: `key.isWritable()` true → `NioServer.write(key)` faz `channel.write(state.writeBuffer)` → drena o buffer.
9. `writeBuffer` esvazia → `state.reset()` → interest volta a `OP_READ` (keep-alive).
10. Conexão pronta para a próxima request **sem nova handshake TCP**.

Cada um desses passos é construído em uma seção abaixo.

---

## §2. Princípios não-negociáveis (lembrete curto)

Esses 4 vêm do `CONCEITOS.md` §9. **Não revise aqui — só lembre**:

1. **Zero alocação no hot path** — nada de `new` por request. Buffers/arrays alocados 1× no `accept()`, reutilizados.
2. **Buffers diretos** — `ByteBuffer.allocateDirect()` para I/O (off-heap, fora do GC).
3. **Sem `String`, sem `Map`, sem regex** — comparações byte-a-byte, offsets em `int`.
4. **Single-thread reactor** — 1 thread, 0 lock, 0 sync. Sem `synchronized`, sem `ConcurrentHashMap`.

Cada vez que pensar em `String.format`, `BufferedReader`, `HashMap` — **pare**. Pergunte: "consigo fazer com `byte[]` + offsets?" Sempre sim.

---

## §3. `NioServer.java` parte 1: estrutura + accept loop

**O que estamos fazendo**: criar a estrutura do reactor — `Selector` + `ServerSocketChannel` + o loop que aceita conexões. `read()` e `write()` são stubs nesta etapa.

### Refresher rápido

- `Selector` — wrapper Java do `epoll` (Linux). Multiplexa N channels em 1 thread (📖 `CONCEITOS.md` §6).
- `ServerSocketChannel` — listening socket. Gera novos `SocketChannel` no `accept()`.
- `SelectionKey` — handle que conecta um channel ao selector + interest ops + attachment.

### Código completo

Substitua o conteúdo de `api/src/main/java/org/fraudDetection/server/NioServer.java` por:

```java
package org.fraudDetection.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class NioServer {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    public NioServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        // 1. Selector — quem bloqueia esperando eventos de I/O
        selector = Selector.open();

        // 2. Listening socket
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));   // bind antes de configurar non-blocking
        serverChannel.configureBlocking(false);            // obrigatório pra registrar no selector
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("api: listening on port " + port);

        // 3. Loop infinito do reactor
        while (true) {
            
            selector.select();   // bloqueia até pelo menos 1 key ter evento pronto

            // PEGADINHA: se não remover a key do iterator, o próximo select retorna ela de novo
            // → loop infinito de CPU 100%
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) continue;   // pode ter sido cancelada em iteração anterior

                try {
                    if (key.isAcceptable())       accept(key);
                    else if (key.isReadable())    read(key);
                    else if (key.isWritable())    write(key);
                } catch (IOException ex) {
                    // qualquer falha de I/O numa conexão = fechar essa conexão, servidor continua
                    key.cancel();
                    try { key.channel().close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    // Stubs — preenchidos no §5
    private void accept(SelectionKey serverKey) throws IOException {
        // TODO §5
    }
    private void read(SelectionKey key) throws IOException {
        // TODO §5
    }
    private void write(SelectionKey key) throws IOException {
        // TODO §5
    }
}
```

### Pegadinhas dessa seção

| # | O que | Por que |
|---|---|---|
| 1 | `it.remove()` é obrigatório | Sem remover, próximo `select()` retorna a mesma key — CPU 100% |
| 2 | `configureBlocking(false)` antes de `register` | `register()` falha em modo blocking |
| 3 | `selector.select()` (sem timeout) bloqueia indefinidamente | Pra polling use `selectNow()` (cuidado: 100% CPU se loopar) |
| 4 | Fechar key/channel dentro de try | Falhas de I/O são esperadas e não devem matar o servidor |

### 🔍 Test point 1 — server inicia e aceita conexão TCP

Substitua `api/src/main/java/org/fraudDetection/Main.java` por:

```java
package org.fraudDetection;

import org.fraudDetection.server.NioServer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}
```

Compile e rode em **2 terminais separados** (não copie tudo num só — o `#` é comentário no shell e quebra a sequência).

**Terminal 1** (compila e roda o servidor em foreground):
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw clean package
java --add-modules jdk.incubator.vector -jar target/api.jar 9999
# Saída esperada: "api: listening on port 9999"
# Deixa rodando. Para parar depois: Ctrl+C
```

**Terminal 2** (cliente — aborta a conexão depois de ver o "Connected"):
```bash
telnet localhost 9999
# → "Trying 127.0.0.1..."
# → "Connected to localhost." (vai pendurar — esperado, ainda não respondemos)
# Pra sair: Ctrl+] depois digite "quit" + Enter
```

Se o Terminal 2 mostra `Connected to localhost.`, o reactor está escutando. Vamos dar memória pra cada conexão.

> Alternativa em 1 terminal só: rode com `&` no fim (`java ... 9999 &`) pra background; depois `kill %1` no final. Mas iniciantes preferem 2 terminais — log do server fica separado e visível.

---

## §4. `ConnectionState.java`

**O que estamos fazendo**: criar 1 objeto por conexão TCP, com buffers pré-alocados reutilizáveis. Em vez de alocar `byte[]` a cada request, alocamos uma vez no `accept()` e reutilizamos pra todas as requests subsequentes da mesma conexão (keep-alive).

### Layout para Onda 1

Layout completo (incluindo as próximas ondas) está em `RINHA_PLAN.md` §6.3. Para Onda 1 precisamos só de:

- `readBuffer`: `ByteBuffer.allocateDirect(4096)` — bytes que chegam do socket. 4 KB cobre `/ready` (sem body) e `/fraud-score` (body ~500 B).
- `writeBuffer`: `ByteBuffer.allocateDirect(512)` — bytes da resposta. Respostas máximas ~150 B.
- Estado do parser que sobrevive entre reads (`parserState`, `parserPosition`, `methodCode`, `pathStart/End`, `contentLength`, `bodyOffset`, `headerNameStart/End`).

Campos como `queryVector[14]`, `queryQuant[14]`, `hnswCandidates[1024]` da `RINHA_PLAN.md` §6.3 entram em Ondas 1.5+/2.

### Código completo

Crie `api/src/main/java/org/fraudDetection/server/ConnectionState.java`:

```java
package org.fraudDetection.server;

import java.nio.ByteBuffer;

public class ConnectionState {
    // Estados do parser HTTP (usados no §6)
    public static final int STATE_METHOD       = 0;
    public static final int STATE_PATH         = 1;
    public static final int STATE_VERSION      = 2;
    public static final int STATE_HEADER_LINE  = 3;
    public static final int STATE_HEADER_NAME  = 4;
    public static final int STATE_HEADER_VALUE = 5;
    public static final int STATE_BODY         = 6;
    public static final int STATE_DONE         = 7;

    // Method codes (sem String — int é cheap)
    public static final int METHOD_UNKNOWN = 0;
    public static final int METHOD_GET     = 1;
    public static final int METHOD_POST    = 2;

    // Buffers off-heap pra I/O — alocados 1× e nunca mais
    public final ByteBuffer readBuffer  = ByteBuffer.allocateDirect(4096);
    public final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(512);

    // Estado do parser que persiste entre reads (TCP fragmentation)
    public int parserState    = STATE_METHOD;
    public int parserPosition = 0;   // próximo byte a ler em readBuffer

    // Dados extraídos da request atual
    public int methodCode      = METHOD_UNKNOWN;
    public int pathStart       = -1;
    public int pathEnd         = -1;
    public int contentLength   = 0;
    public int bodyOffset      = -1;
    public int headerNameStart = -1;
    public int headerNameEnd   = -1;

    /**
     * Prepara o state pra próxima request (keep-alive).
     * NÃO realoca buffers — só rewind/clear nos índices.
     */
    public void reset() {
        readBuffer.clear();
        writeBuffer.clear();
        parserState     = STATE_METHOD;
        parserPosition  = 0;
        methodCode      = METHOD_UNKNOWN;
        pathStart       = -1;
        pathEnd         = -1;
        contentLength   = 0;
        bodyOffset      = -1;
        headerNameStart = -1;
        headerNameEnd   = -1;
    }
}
```

### Por que `allocateDirect`?

Buffers diretos vivem fora do heap Java (são `mmap` anônimos do SO). Vantagens:

- Zero cópia entre user-space e kernel-space no `read()`/`write()` (kernel pode DMA direto pra page).
- Imune ao GC — a page nunca é movida ou pausada.
- `position()`/`limit()` são atributos baratos (sem locks).

Trade-off: alocar é caro (syscall `mmap`). Mas alocamos **uma vez por conexão** — TCP keep-alive paga só uma vez, depois reusa por todas as requests.

### 🔍 Test point 2 — buffers existem com capacidades corretas

Adicione em `Main.main()` (temporário):

```java
ConnectionState s = new ConnectionState();
System.out.println("readBuffer cap:    " + s.readBuffer.capacity());   // 4096
System.out.println("writeBuffer cap:   " + s.writeBuffer.capacity());  //  512
System.out.println("readBuffer direct: " + s.readBuffer.isDirect());   // true
```

Rode `./mvnw exec:java` ou só compile + main. Confirme 4096/512/true. Apague esse trecho depois.

---

## §5. `NioServer.java` parte 2: integrar `accept`, `read`, `write`

**O que estamos fazendo**: preencher os 3 stubs do §3 com a lógica real.

### Substitua os 3 stubs por:

```java
private void accept(SelectionKey serverKey) throws IOException {
    SocketChannel socketChannel = serverChannel.accept();
    if (socketChannel == null) return;   // defesa: spurious wakeup ou key reentrante (ver pegadinha #5 abaixo)
    socketChannel.configureBlocking(false);

    // 1 ConnectionState por conexão TCP — alocado aqui, reutilizado pra todas as requests da conn
    ConnectionState state = new ConnectionState();

    // Registra interesse em OP_READ — kernel notifica quando bytes chegarem.
    // O state é attachment — recuperado nos próximos events via key.attachment()
    socketChannel.register(selector, SelectionKey.OP_READ, state);
}

private void read(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    ConnectionState state = (ConnectionState) key.attachment();

    int bytesRead = channel.read(state.readBuffer);
    if (bytesRead == -1) {
        // peer fechou (FIN). Fechamos do nosso lado.
        key.cancel();
        channel.close();
        return;
    }
    if (bytesRead == 0) {
        return;   // nada novo, próximo select()
    }

    // Tenta parsear o que tem (resumível — para em STATE_X se faltar bytes)
    int result = HttpParser.parse(state);

    if (result == HttpParser.PARSE_INCOMPLETE) {
        // TCP fragmentou — continua escutando, parser retoma do parserPosition
        return;
    }
    if (result == HttpParser.PARSE_ERROR) {
        key.cancel();
        channel.close();
        return;
    }

    // PARSE_DONE — dispatch
    dispatch(state, key);
}

private void write(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    ConnectionState state = (ConnectionState) key.attachment();

    channel.write(state.writeBuffer);

    if (!state.writeBuffer.hasRemaining()) {
        // Drenamos tudo — preparar pra próxima request (keep-alive)
        state.reset();
        key.interestOps(SelectionKey.OP_READ);
    }
    // Se ainda tem bytes (partial write), key fica em OP_WRITE pro próximo select()
}

// Path bytes pra match byte-a-byte sem alocar String
private static final byte[] PATH_READY = { '/', 'r', 'e', 'a', 'd', 'y' };

private void dispatch(ConnectionState state, SelectionKey key) {
    if (state.methodCode == ConnectionState.METHOD_GET
            && bytesEqual(state.readBuffer, state.pathStart, state.pathEnd, PATH_READY)) {
        org.fraudDetection.controllers.HealthController.handle(state, key);
        return;
    }
    // Onda 1.5 vai tratar POST /fraud-score aqui.
    // Por enquanto: fecha (404 minimalista)
    key.cancel();
    try { key.channel().close(); } catch (IOException ignored) {}
}

private static boolean bytesEqual(java.nio.ByteBuffer buf, int start, int end, byte[] expected) {
    if (end - start != expected.length) return false;
    for (int i = 0; i < expected.length; i++) {
        if (buf.get(start + i) != expected[i]) return false;
    }
    return true;
}
```

### Pegadinhas dessa seção

| Pegadinha | Sintoma | Fix |
|---|---|---|
| TCP fragmenta request em 2 packets | "request fica pendurada" | Parser resumível — volta a `OP_READ` se `PARSE_INCOMPLETE` |
| Esquecer interest pra `OP_WRITE` no handler | "response nunca sai" | `key.interestOps(OP_WRITE)` no controller (§8) |
| Fechar channel após write | "segunda req da mesma conn falha" | Voltar a `OP_READ`, NÃO fechar |
| `channel.write()` partial | "response cortada" | `hasRemaining()` decide se key fica em `OP_WRITE` |
| Esquecer `state.reset()` antes do próximo OP_READ | "segunda request mistura com a primeira" | `reset()` chamado quando `writeBuffer` drena |
| `serverChannel.accept()` retorna `null` | `NullPointerException` em `socketChannel.configureBlocking(false)` | Sempre `if (socketChannel == null) return;` antes de usar. Causas: esqueceu `it.remove()` no loop do `start()`, spurious wakeup do kernel, ou key reentrante. |

### 🔍 Test point 3 — request HTTP chega no read()

Em **2 terminais separados**:

**Terminal 1** (recompila e roda o server — sempre que você muda código Java, refaz o `./mvnw clean package`):
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw clean package
java --add-modules jdk.incubator.vector -jar target/api.jar 9999
```

**Terminal 2** (manda 1 request HTTP cru pelo netcat):
```bash
printf 'GET /ready HTTP/1.1\r\nHost: localhost\r\n\r\n' | nc localhost 9999
```

O `nc` (netcat) manda os bytes e espera resposta. Como o parser ainda não foi escrito, vai retornar `PARSE_INCOMPLETE` ou rebentar. Adicione `System.out.println("read " + bytesRead + " bytes")` temporariamente no `read()` pra confirmar que bytes chegaram no Terminal 1. Vamos preencher o parser agora.

---

## §6. `HttpParser.java`

**O que estamos fazendo**: state machine byte-a-byte que percorre `readBuffer` e popula `state.methodCode`, `state.pathStart/End`, `state.contentLength`, `state.bodyOffset`.

Sem `String`, sem `BufferedReader`, sem regex. Tudo em `int` e comparação byte-a-byte.

### Princípio: state machine resumível

Estados (definidos em `ConnectionState`):

```
STATE_METHOD        — lendo bytes do method, esperando espaço
       ↓
STATE_PATH          — lendo bytes do path, esperando espaço
       ↓
STATE_VERSION       — lendo "HTTP/1.1", esperando \r\n
       ↓
STATE_HEADER_LINE   — começo de uma linha de header (ou \r\n final)
   ↓ (não é \r\n)         ↓ (é \r\n — fim dos headers)
STATE_HEADER_NAME    STATE_BODY (ou DONE se Content-Length=0)
       ↓ (':' encontrado)
STATE_HEADER_VALUE
       ↓ (\r\n)
[volta a STATE_HEADER_LINE]
```

A cada chamada, `parse(state)` lê de `state.parserPosition` até o último byte disponível em `readBuffer`. Se faltarem bytes pra avançar, retorna `PARSE_INCOMPLETE` mantendo `parserState` — próxima chamada continua de onde parou. Por isso `headerNameStart`/`End` ficam em `state`, não locais.

### Código completo

Crie `api/src/main/java/org/fraudDetection/server/HttpParser.java`:

```java
package org.fraudDetection.server;

import java.nio.ByteBuffer;

public class HttpParser {
    public static final int PARSE_INCOMPLETE = 0;
    public static final int PARSE_DONE       = 1;
    public static final int PARSE_ERROR      = 2;

    private static final byte CR    = (byte) '\r';
    private static final byte LF    = (byte) '\n';
    private static final byte SPACE = (byte) ' ';
    private static final byte COLON = (byte) ':';

    // "content-length" em lowercase pra comparação case-insensitive
    private static final byte[] HDR_CONTENT_LENGTH = {
        'c','o','n','t','e','n','t','-','l','e','n','g','t','h'
    };

    public static int parse(ConnectionState state) {
        ByteBuffer buf = state.readBuffer;
        // O socket escreveu até buf.position(). Lemos de parserPosition até position.
        int limit = buf.position();
        int i = state.parserPosition;

        while (i < limit) {
            byte b = buf.get(i);

            switch (state.parserState) {
                case ConnectionState.STATE_METHOD -> {
                    if (b == SPACE) {
                        // method ocupou [0..i) — comparar contra "GET" ou "POST"
                        state.methodCode = matchMethod(buf, 0, i);
                        if (state.methodCode == ConnectionState.METHOD_UNKNOWN) return PARSE_ERROR;
                        state.parserState = ConnectionState.STATE_PATH;
                        state.pathStart   = i + 1;
                    }
                }
                case ConnectionState.STATE_PATH -> {
                    if (b == SPACE) {
                        state.pathEnd     = i;
                        state.parserState = ConnectionState.STATE_VERSION;
                    }
                }
                case ConnectionState.STATE_VERSION -> {
                    // só nos importa o LF que fecha "HTTP/1.1\r\n"
                    if (b == LF) state.parserState = ConnectionState.STATE_HEADER_LINE;
                }
                case ConnectionState.STATE_HEADER_LINE -> {
                    if (b == CR) {
                        // potencialmente \r\n final dos headers — esperamos o LF
                    } else if (b == LF) {
                        // chegamos no fim dos headers
                        state.bodyOffset = i + 1;
                        if (state.contentLength == 0) {
                            state.parserState    = ConnectionState.STATE_DONE;
                            state.parserPosition = i + 1;
                            return PARSE_DONE;
                        }
                        state.parserState = ConnectionState.STATE_BODY;
                    } else {
                        // primeiro byte de um header name
                        state.headerNameStart = i;
                        state.parserState     = ConnectionState.STATE_HEADER_NAME;
                    }
                }
                case ConnectionState.STATE_HEADER_NAME -> {
                    if (b == COLON) {
                        state.headerNameEnd = i;
                        state.parserState   = ConnectionState.STATE_HEADER_VALUE;
                    }
                }
                case ConnectionState.STATE_HEADER_VALUE -> {
                    if (b == LF) {
                        // Header completo. Se for Content-Length, parsear o valor.
                        if (headerEquals(buf, state.headerNameStart, state.headerNameEnd, HDR_CONTENT_LENGTH)) {
                            // valor está entre o COLON+1 e o byte antes do LF (que é CR)
                            int parsed = parseDecimal(buf, state.headerNameEnd + 1, i - 1);
                            if (parsed < 0) return PARSE_ERROR;
                            state.contentLength = parsed;
                        }
                        state.parserState = ConnectionState.STATE_HEADER_LINE;
                    }
                }
                case ConnectionState.STATE_BODY -> {
                    int bodyBytesAvailable = limit - state.bodyOffset;
                    if (bodyBytesAvailable >= state.contentLength) {
                        state.parserState    = ConnectionState.STATE_DONE;
                        state.parserPosition = state.bodyOffset + state.contentLength;
                        return PARSE_DONE;
                    }
                    state.parserPosition = limit;
                    return PARSE_INCOMPLETE;
                }
                case ConnectionState.STATE_DONE -> {
                    return PARSE_DONE;
                }
            }
            i++;
        }

        // Saiu do loop sem PARSE_DONE — faltam bytes, retoma na próxima chamada
        state.parserPosition = i;
        return PARSE_INCOMPLETE;
    }

    /** Match exato "GET" ou "POST" — sem alocar String. */
    private static int matchMethod(ByteBuffer buf, int start, int end) {
        int len = end - start;
        if (len == 3
                && buf.get(start)   == 'G'
                && buf.get(start+1) == 'E'
                && buf.get(start+2) == 'T') {
            return ConnectionState.METHOD_GET;
        }
        if (len == 4
                && buf.get(start)   == 'P'
                && buf.get(start+1) == 'O'
                && buf.get(start+2) == 'S'
                && buf.get(start+3) == 'T') {
            return ConnectionState.METHOD_POST;
        }
        return ConnectionState.METHOD_UNKNOWN;
    }

    /** Compara header name case-insensitive contra um expected em lowercase. */
    private static boolean headerEquals(ByteBuffer buf, int start, int end, byte[] expectedLower) {
        if (end - start != expectedLower.length) return false;
        for (int j = 0; j < expectedLower.length; j++) {
            byte b = buf.get(start + j);
            // converte A-Z em a-z em-flight (bit 0x20 é o bit de case em ASCII)
            if (b >= 'A' && b <= 'Z') b |= 0x20;
            if (b != expectedLower[j]) return false;
        }
        return true;
    }

    /** Parse decimal int de um range do buffer — pula spaces e CR. Sem Integer.parseInt(String). */
    private static int parseDecimal(ByteBuffer buf, int start, int end) {
        int value = 0;
        boolean anyDigit = false;
        for (int j = start; j < end; j++) {
            byte b = buf.get(j);
            if (b == ' ' || b == CR) continue;     // pula leading space e trailing CR
            if (b < '0' || b > '9') return -1;
            value = value * 10 + (b - '0');
            anyDigit = true;
        }
        return anyDigit ? value : -1;
    }
}
```

### Por que tanta dor?

Tudo isso (state machine, comparação byte-a-byte, sem `String`) garante zero alocação. Cada request faz `parse()` que **não cria nenhum objeto** — só lê do `readBuffer` e atualiza ints no `state`. Em ~5-20 µs.

Compare com `BufferedReader.readLine()` + `split(" ")` + `Integer.parseInt(String)`: 50-200 µs e várias alocações por request → pressão de GC → pause → p99 tail explode (📖 `CONCEITOS.md` §10).

### 🔍 Test point 4 — parser pega method e path corretos

Adicione em `Main.main()` (temporário):

```java
ConnectionState s = new ConnectionState();
byte[] req = "GET /ready HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
s.readBuffer.put(req);

int result = HttpParser.parse(s);
System.out.println("result=" + result);          // 1 (PARSE_DONE)
System.out.println("method=" + s.methodCode);    // 1 (GET)
System.out.println("pathStart=" + s.pathStart);  // 4
System.out.println("pathEnd=" + s.pathEnd);      // 10
System.out.print("path: ");
for (int i = s.pathStart; i < s.pathEnd; i++) {
    System.out.print((char) s.readBuffer.get(i));   // imprime /ready
}
System.out.println();
```

Esperado: `result=1`, `method=1`, `pathStart=4`, `pathEnd=10`, `path: /ready`.

Apague depois.

---

## §7. `HttpResponseWriter.java`

**O que estamos fazendo**: respostas pré-construídas como `byte[]` constante. Para `/ready` é só 1 string fixa que copiamos pro `writeBuffer`.

### Código completo

Crie `api/src/main/java/org/fraudDetection/server/HttpResponseWriter.java`:

```java
package org.fraudDetection.server;

import java.nio.charset.StandardCharsets;

public class HttpResponseWriter {

    // Pré-construído 1× na JVM — bytes vão direto pro writeBuffer
    private static final byte[] RESPONSE_READY =
        ("HTTP/1.1 200 OK\r\n" +
         "Connection: keep-alive\r\n" +
         "Content-Length: 0\r\n" +
         "\r\n").getBytes(StandardCharsets.US_ASCII);

    public static void writeReady(ConnectionState state) {
        state.writeBuffer.clear();             // posição volta a 0, limit = capacity
        state.writeBuffer.put(RESPONSE_READY); // escreve os bytes
        state.writeBuffer.flip();              // prepara pra leitura (channel.write lê)
    }

    // Stub pra Onda 1.5
    public static void writeFraudScore(ConnectionState state, boolean approved, int fraudLevel) {
        // TODO Onda 1.5: 12 respostas canned (2 valores × 6 níveis)
        throw new UnsupportedOperationException("Onda 1.5");
    }
}
```

### Detalhes importantes

- **`getBytes(US_ASCII)`** — explícito. Default seria UTF-8 (pra ASCII puro dá no mesmo).
- **`writeBuffer.clear()` antes**, **`writeBuffer.flip()` depois** — `clear()` zera pra escrita, `flip()` o prepara pra leitura pelo `channel.write`.
- **`Connection: keep-alive` explícito** — HTTP/1.1 default já é keep-alive, mas declarar evita ambiguidade.

### 🔍 Test point 5 — bytes da resposta corretos

Em `Main.main()` (temporário):

```java
ConnectionState s = new ConnectionState();
HttpResponseWriter.writeReady(s);

byte[] tmp = new byte[s.writeBuffer.remaining()];
s.writeBuffer.duplicate().get(tmp);
System.out.println(new String(tmp, java.nio.charset.StandardCharsets.US_ASCII));
```

Esperado:
```
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 0

```

---

## §8. `controllers/HealthController.java`

**O que estamos fazendo**: handler do `/ready`. Chama o writer e troca interest pra `OP_WRITE`.

### Código completo

Crie `api/src/main/java/org/fraudDetection/controllers/HealthController.java`:

```java
package org.fraudDetection.controllers;

import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

import java.nio.channels.SelectionKey;

public class HealthController {
    public static void handle(ConnectionState state, SelectionKey key) {
        HttpResponseWriter.writeReady(state);
        key.interestOps(SelectionKey.OP_WRITE);   // sinaliza ao selector: temos resposta pronta
    }
}
```

5 linhas de lógica.

### Como está conectado

Já está conectado no `dispatch()` do `NioServer.java` (§5). Quando o parser termina (`PARSE_DONE`) e o path bate com `/ready`, `dispatch` chama `HealthController.handle()` — que chama o writer e troca interest. Próximo `select()` notifica `OP_WRITE`, `NioServer.write()` drena o `writeBuffer`, e o keep-alive volta a `OP_READ`.

---

## §9. Test point grande: `curl /ready` end-to-end

### Comandos

Use **2 terminais separados** — server em um, cliente no outro.

**Terminal 1** (server):
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw clean package
java --add-modules jdk.incubator.vector -jar target/api.jar 9999
# Espera ver "api: listening on port 9999"
```

**Terminal 2** (cliente):
```bash
# 1. Primeira request
curl -v http://localhost:9999/ready
# Esperado nos headers da resposta:
# < HTTP/1.1 200 OK
# < Connection: keep-alive
# < Content-Length: 0

# 2. Keep-alive: 2 requests, mesma conexão TCP
curl -v http://localhost:9999/ready http://localhost:9999/ready
# curl deve reusar a conexão — 2 respostas "200 OK" sem novo "Connected to"
```

**Cleanup**: Ctrl+C no Terminal 1 pra parar o servidor.

### Troubleshooting

| Sintoma | Causa provável | Vá pra |
|---|---|---|
| `curl: (7) Failed to connect` | server não iniciou ou porta ocupada | §3 — confirme o `System.out.println` do `bind` |
| `curl` pendura indefinidamente | parser não detectou fim da request | §6 — adicione `System.out.println(state.parserState)` no `read()` antes do dispatch |
| `Empty reply from server` | handler não chamou writer ou interest errado | §8 — verifique `key.interestOps()` depois de `writeReady` |
| Primeira req OK, segunda pendura | keep-alive não voltou a `OP_READ` | §5 — `write()` precisa fazer `state.reset()` + `interestOps(OP_READ)` |
| Response sai com `\n` errado | bytes do response sem `\r` | §7 — use `\r\n` exato |
| Multiple keys looping em CPU 100% | esqueceu `it.remove()` | §3 |

---

## §10. Pegadinhas (resumo final)

| ⚠️ Pegadinha | Referência |
|---|---|
| TCP fragmenta request em múltiplos packets — parser deve ser resumível | `CONCEITOS.md` §10 |
| Múltiplas requests num único read (pipelining) — drenar até buffer vazio | `CONCEITOS.md` §10 — Onda 1 ignora; faz só 1 request por iteração |
| `selectedKeys()` precisa de `iterator.remove()` | §3 deste tutorial |
| Keep-alive: NÃO fechar channel após write — voltar a `OP_READ` | §5 deste tutorial + `RINHA_PLAN.md` §8.2 |
| Partial write — `channel.write` pode escrever menos que pediu | §5 deste tutorial (`hasRemaining()`) |
| `last_transaction: null` no body — sentinela `-1` no `queryVector[5]` e `[6]` | Próximo tutorial |
| `com.sun.net.httpserver` — NÃO usar pra "validar rapidinho" | `RINHA_PLAN.md` §12.2 |

Mapa completo de armadilhas indexadas por sintoma: `RINHA_PLAN.md` §12.

---

## §11. Próximos passos

Você tem `curl /ready` retornando 200 OK. Próximas etapas da **Onda 1** (em ordem do `COMECE_AQUI.md` §6.2):

1. `json/FraudRequestParser.java` — walker no body do `POST /fraud-score`, popular `queryVector[14]` direto sem objeto intermediário. Sentinela `-1` para `last_transaction: null`.
2. `dataset/MmapDataset.java` v1 — carregar `references.json.gz` em `float[][]` heap (~168 MB, lento, mas correto pra baseline).
3. `knn/DistanceFunctions.java` v1 — euclidiana escalar (`for (i=0..13) sum += (a-b)*(a-b)`).
4. `knn/HnswIndex.java` v1 — brute force linear: varre 3M vetores, retorna top-5 por label. Vai dar p99 ~30 ms. Tudo bem — é o **baseline de correctness** que a Onda 3 (HNSW de verdade) vai validar contra.
5. `controllers/FraudController.java` — orquestra: parser → KNN → response template.
6. Adicionar dispatch de `POST /fraud-score` em `NioServer.dispatch()` (apontando pra `FraudController`).

**Critério de saída da Onda 1**: `curl POST /fraud-score -d @example-payloads.json` retorna `{approved, fraud_score}` corretos vs `example-references.json`.

Tutorial seguinte: **`docs/TUTORIAL_JSON_KNN.md`** — **já disponível**. Cobre o resto da Onda 1 (limpeza dos bloqueadores → `FraudRequestParser` → `MmapDataset` → `DistanceFunctions` → `HnswIndex` brute force → `FraudController` → dispatch `POST /fraud-score`), fechando a Onda 1 contra os oráculos do `REGRAS_DE_DETECCAO.md`.

---

**Boa sorte. Cada test point é uma vitória.** 🏁
