# NIO Selector (`java.nio.channels`)

**Categoria**: I/O multiplexing
**Versão usada na Rinha**: built-in do Java 21
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.3

---

## O que é

`java.nio.channels.Selector` é uma API do JDK que permite **monitorar múltiplos sockets simultaneamente em uma única thread**. É um **wrapper portátil** sobre primitivas nativas do kernel: `epoll` (Linux), `kqueue` (BSD/macOS), `IOCP` (Windows).

Faz parte da New I/O API (NIO) introduzida em Java 1.4 (2002). NIO substituiu o I/O blocking (java.io) tradicional onde 1 socket = 1 thread.

## Objetivo geral

Resolver o problema **C10K** ("como atender 10 mil conexões simultâneas?"). Modelo blocking thread-per-connection cria 1 thread por socket — para 10k conexões = 10k threads = ~80 GB de stacks + scheduling caótico.

NIO Selector inverte: **1 thread monitora N sockets**. Ela bloqueia em `Selector.select()` esperando o kernel avisar qual socket tem dados prontos. Quando algum tem, processa em série.

Para conceito profundo, ver `../CONCEITOS.md` §6.

## Pra que vamos usar no projeto

`server/NioServer.java` é o coração do hot path:

```java
while (running) {
    selector.select();
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) acceptNewClient();
        if (key.isReadable())   handleRead(key);   // parse HTTP, JSON, KNN search, write response
        if (key.isWritable())   handleWrite(key);
    }
}
```

Tudo em **1 thread**: I/O + parsing + KNN search + write. Em 1 CPU compartilhada (limite Rinha), isso vence multi-thread porque elimina context switch (~2-5 µs por troca).

Substitui o `com.sun.net.httpserver` que está atualmente em `ServerHTTP.java` (que aloca 50-200 µs/request — mata p99=1ms).

## Como funciona (em profundidade)

### 4 abstrações principais

1. **`Channel`**: representação de um endpoint de I/O (socket, arquivo). `SocketChannel` é TCP, `ServerSocketChannel` é o listening.
2. **`Selector`**: o multiplexador. Registra channels com interesse em eventos.
3. **`SelectionKey`**: token retornado quando você registra um channel. Carrega o estado da conexão (`attachment()`).
4. **`Buffer`** (`ByteBuffer`): container de bytes para read/write.

### Fluxo de uma request

```
[1] Client conecta
    Kernel: nova conexão TCP
    Selector.select() retorna com OP_ACCEPT
    Server: ServerSocketChannel.accept() → novo SocketChannel
    Server: socketChannel.register(selector, OP_READ, ConnectionState)
    
[2] Client envia bytes
    Kernel: dados no buffer do socket
    Selector.select() retorna com OP_READ
    Server: socketChannel.read(state.readBuffer)
    Server: parse HTTP, parse JSON, KNN, gera response em state.writeBuffer
    Server: socketChannel.register(selector, OP_WRITE)
    
[3] Kernel libera espaço no socket de saída
    Selector.select() retorna com OP_WRITE
    Server: socketChannel.write(state.writeBuffer)
    Server: socketChannel.register(selector, OP_READ)   ← keep-alive!
```

### Non-blocking mode

```java
SocketChannel ch = ...;
ch.configureBlocking(false);
```

Sem isso, `read()`/`write()` bloqueiam. Em modo non-blocking, retornam imediatamente com bytes lidos/escritos (ou 0 se nada disponível). Isso é o que permite multiplexing.

### `OP_ACCEPT` / `OP_READ` / `OP_WRITE` / `OP_CONNECT`

Bitmask de eventos de interesse. Você muda o que monitora:

```java
key.interestOps(SelectionKey.OP_WRITE);     // não me avise mais sobre read
key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
```

### `select()` vs `selectNow()` vs `select(timeout)`

- `select()`: bloqueia até algum evento (semelhante a epoll_wait sem timeout).
- `select(ms)`: bloqueia com timeout.
- `selectNow()`: retorna imediatamente, mesmo com 0 eventos (busy poll — caro).

### `wakeup()` thread-safe

Outras threads precisam interromper o `select()` (ex: graceful shutdown):

```java
selector.wakeup();   // força select() a retornar
```

Sem `wakeup()`, registrar channels de outra thread é deadlock-prone.

### `attachment()` para state per-connection

```java
ConnectionState state = new ConnectionState();
state.readBuffer = ByteBuffer.allocateDirect(4096);
key.attach(state);

// depois:
ConnectionState s = (ConnectionState) key.attachment();
s.readBuffer.flip();
```

Estado da conexão (parser cursor, query vector, candidates) fica em `attachment()` — single-thread, sem locks.

## Exemplo de uso

```java
import java.nio.*;
import java.nio.channels.*;
import java.net.InetSocketAddress;
import java.util.Iterator;

public class EchoServer {
    public static void main(String[] args) throws Exception {
        Selector sel = Selector.open();
        ServerSocketChannel srv = ServerSocketChannel.open();
        srv.configureBlocking(false);
        srv.bind(new InetSocketAddress(9999));
        srv.register(sel, SelectionKey.OP_ACCEPT);
        
        while (true) {
            sel.select();
            Iterator<SelectionKey> it = sel.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next();
                it.remove();   // sem isso, mesmo evento processa de novo
                
                if (k.isAcceptable()) {
                    SocketChannel client = srv.accept();
                    client.configureBlocking(false);
                    client.register(sel, SelectionKey.OP_READ, ByteBuffer.allocate(1024));
                }
                if (k.isReadable()) {
                    SocketChannel ch = (SocketChannel) k.channel();
                    ByteBuffer buf = (ByteBuffer) k.attachment();
                    int n = ch.read(buf);
                    if (n == -1) ch.close();
                    else { buf.flip(); ch.write(buf); buf.compact(); }
                }
            }
        }
    }
}
```

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **`com.sun.net.httpserver`** | Built-in, fácil ("HelloHttpServer" em 5 linhas) | Aloca 50-200 µs/request, viscoso, vicia layout | Demos, scripts, prototipagem |
| **Netty** | Framework completo, comunidade gigante, codec ready | Aprendizado, viola "by-hand" | Servidores HTTP/2 complexos, gRPC |
| **Vert.x** | Event-loop maduro, polyglot | Event bus overhead, framework | Aplicações reactivas, Kotlin |
| **Helidon Nima (Oracle)** | Virtual threads + simple API | Fork-join scheduling em CPU-bound | Apps modernos com I/O dominante |
| **virtual threads + blocking I/O** | Código simples (não-async) | ~5 µs por park/unpark | I/O dominante (DB, HTTP client) |
| **async I/O (`AsynchronousChannelGroup`)** | Future-based | API esquisita, performance variável | Quando você prefere callback/CompletableFuture |
| **Project Panama Foreign** | Acesso direto a epoll | Em maturação | Bleeding edge |

Na Rinha, NIO Selector raw vence:
- 1 CPU = não-paralelizável beneficamente.
- Workload CPU-bound (KNN domina) — virtual threads não ajudam.
- Hand-roll garante zero allocation.

## Pegadinhas conhecidas

1. **Esquecer `it.remove()`**: depois de processar key, remover do iterator. Sem isso, mesmo evento processa de novo no próximo `select()`.
2. **Buffer state**: `flip()` antes de ler do buffer, `compact()` para reusar. Esquecer = bytes corrompidos.
3. **Spurious wakeups**: raro mas possível, `select()` retorna sem evento real. Sempre testar `key.isReadable()` antes de ler.
4. **Allocation por conexão**: alocar buffers a cada accept = pressão GC. Pool de `ConnectionState`.
5. **`register()` de outra thread**: causa deadlock se selector bloqueado em `select()`. Sempre usar `wakeup()`.
6. **`SocketChannel.read()` retornou 0**: não é erro, só "nada disponível agora" (modo non-blocking). Não fechar a conexão por isso.
7. **Keep-alive**: depois de write, registrar OP_READ de novo, NÃO fechar o canal. HTTP/1.1 reusa conexão.
8. **`TCP_NODELAY`**: por default Nagle algorithm atrasa pequenos writes. `socket.setOption(StandardSocketOptions.TCP_NODELAY, true)` para responses curtas.

## Referências

- **`Selector` javadoc**: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Selector.html
- **`SocketChannel`**: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/SocketChannel.html
- **`ByteBuffer` cheatsheet**: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html
- **Oracle NIO tutorial**: https://docs.oracle.com/javase/tutorial/essential/io/index.html (datado mas válido)
- **Reactor pattern (paper)**: Schmidt 1995 — https://www.dre.vanderbilt.edu/~schmidt/PDF/Reactor.pdf
- **C10K Problem** (Dan Kegel): http://www.kegel.com/c10k.html
- **Java NIO and the Reactor Pattern** (blog): https://medium.com/coderscorner/java-nio-and-the-reactor-pattern-9e2cbab5a945
- **picohttpparser** (referência hand-roll C): https://github.com/h2o/picohttpparser
- **Helidon Nima** (referência Java moderna): https://github.com/helidon-io/helidon

## Veredito final na Rinha

NIO Selector raw é a base do servidor HTTP da fraudAPI. Onda 1 cria `NioServer.java` substituindo o `com.sun.net.httpserver` atual.
