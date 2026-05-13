# Maven

**Categoria**: Build tool + dependency manager
**Versão usada na Rinha**: 3.9.9
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.10

---

## O que é

Maven é o build tool dominante do ecossistema Java. Define como **compilar**, **empacotar**, **testar** e **distribuir** projetos Java a partir de um arquivo XML chamado `pom.xml` (Project Object Model). Gerencia **dependências transitivas** (puxa o jar e tudo que ele precisa) buscando em **repositórios remotos** (Maven Central, JCenter).

Convenção sobre configuração: estrutura de diretório padronizada (`src/main/java/`, `src/test/java/`, `target/`), lifecycle padrão (`compile`, `test`, `package`, `install`, `deploy`).

## Objetivo geral

Antes do Maven (~2004), build em Java era Ant — XML imperativo, cada projeto reinventava o wheel. Maven trouxe:

1. **Convenção sobre configuração**: estrutura padrão.
2. **Gestão automática de dependências**: declare, ele baixa.
3. **Lifecycle uniforme**: `mvn package` faz a mesma coisa em qualquer projeto.
4. **Plugins**: extensão para tudo (testes, code coverage, native image).

Hoje Maven roda em ~70% dos projetos Java enterprise.

## Pra que vamos usar no projeto

`fraudAPI/pom.xml` define:
- Java 21 source/target.
- Profile `native` ativando `org.graalvm.buildtools:native-maven-plugin` para build Native Image.
- Plugins: `maven-compiler-plugin` (com `--enable-preview --add-modules jdk.incubator.vector`), `maven-shade-plugin` (jar uberjar para distribuição).
- Maven Wrapper (`mvnw`) para garantir versão fixa em CI.

Comandos do dia-a-dia:
- `./mvnw compile` — compila .java para .class.
- `./mvnw package` — gera jar.
- `./mvnw -Pnative package` — gera Native Image binary.
- `./mvnw test` — roda testes (JUnit + JMH).

## Como funciona (em profundidade)

### Lifecycle padrão

```
validate  →  compile  →  test  →  package  →  verify  →  install  →  deploy
                                                          ↑
                                                       (~/.m2/repository/)
```

Cada fase invoca **goals** de plugins. Ex: `compile` invoca `maven-compiler-plugin:compile`.

### `pom.xml` mínimo

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.fraud-detection</groupId>
    <artifactId>fraudAPI</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>21</release>
                    <compilerArgs>
                        <arg>--enable-preview</arg>
                        <arg>--add-modules</arg><arg>jdk.incubator.vector</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>native</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>0.10.3</version>
                        <extensions>true</extensions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

### Maven Wrapper

`./mvnw` (gerado por `mvn -N io.takari:maven:wrapper`) é um script + JAR pequeno que **baixa o Maven** automaticamente na versão certa. Garante que CI e dev usam a mesma versão. Funciona offline depois do primeiro download.

### Repositório local

`~/.m2/repository/` é cache local de dependências. Primeira build baixa ~200 MB. Próximas usam cache (rápido).

### Resolução de dependências

```xml
<dependencies>
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-core</artifactId>
        <version>1.37</version>
    </dependency>
</dependencies>
```

Maven baixa `jmh-core-1.37.jar` + tudo que ele depende, recursivamente. Resolve conflitos (mesma lib em versões diferentes) com regra "nearest wins".

## Exemplo de uso

```bash
# Compilar
./mvnw compile

# Empacotar (gera target/fraudAPI-1.0-SNAPSHOT.jar)
./mvnw package

# Native Image (com profile)
./mvnw -Pnative -DskipTests package

# Skip tests
./mvnw package -DskipTests

# Limpar e rebuild
./mvnw clean package

# Mostrar árvore de dependências
./mvnw dependency:tree

# Versão (validar Java 21)
./mvnw -version
```

## Tecnologias parecidas (alternativas)

| Build tool | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **Gradle** | Conciso (Groovy/Kotlin DSL), build incremental, custom logic fácil | Curva de aprendizado, debug mais difícil, build lento na primeira vez | Projetos grandes, monorepo, Android |
| **Bazel** | Build paralelo absoluto, hermético, escalável | Configuração complexa, menos plugins Java | Monorepo gigante (Google scale) |
| **Buck (Meta)** | Similar a Bazel | Idem, menos comunidade | Internamente em Meta |
| **sbt** | Functional, expressivo (Scala) | Quase só usado em Scala | Projetos Scala |
| **Ant** (legacy) | Imperativo, controle total | Sem dep manager nativo, XML verboso | Mantê-lo só se você herdou |
| **Mill** | Mais conciso que sbt | Pequeno, ecosistema menor | Scala greenfield |
| **CMake / Make** | Universal | Não-Java | C/C++ |

Na Rinha, Maven ganha por:
1. Já estamos familiarizados.
2. Plugin oficial `native-maven-plugin` da Oracle.
3. Build simples — 1 módulo, sem necessidade de paralelismo Bazel.
4. Wrapper garante versão fixa.

## Pegadinhas conhecidas

1. **Java do Maven ≠ Java do shell**. `mvn -version` mostra qual Maven está usando. Se `JAVA_HOME` aponta para Java errado, build vai falhar com mensagens confusas. Sempre validar.
2. **Snapshots**: versões `1.0-SNAPSHOT` são "instáveis", Maven re-baixa todo dia. Usar em dev, evitar em release.
3. **Phantom dependencies**: às vezes Maven puxa lib transitiva que conflita. `dependency:tree` mostra tudo.
4. **Tests ficam lentos**: `mvn test` roda todos. Use `-Dtest=ClasseEspecifica#metodoEspecifico` para focar.
5. **`clean` é crucial**: bug clássico de "funciona local, falha em CI" geralmente é cache stale. `./mvnw clean install`.
6. **Wrapper precisa ser commitado**: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` no git. Sem isso, novos devs não conseguem rodar.

## Referências

- **Site oficial**: https://maven.apache.org/
- **Maven em 5 minutos**: https://maven.apache.org/guides/getting-started/maven-in-five-minutes.html
- **Lifecycle reference**: https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- **Plugin index**: https://maven.apache.org/plugins/index.html
- **Maven Central** (busca de dependências): https://central.sonatype.com/
- **Maven Wrapper**: https://maven.apache.org/wrapper/
- **GraalVM Native Maven Plugin**: https://graalvm.github.io/native-build-tools/latest/maven-plugin.html
- **Effective Maven** (livro): https://www.amazon.com/Effective-Maven-Thomas-Sundberg/dp/9163700700

## Veredito final na Rinha

Maven é a ferramenta correta para um projeto pequeno como o nosso (1 módulo, ~12-15 arquivos Java). Wrapper garante reprodutibilidade no Docker.
