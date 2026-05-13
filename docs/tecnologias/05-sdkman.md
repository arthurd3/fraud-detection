# SDKMAN!

**Categoria**: Gerenciador de SDK (Java, Maven, Gradle, etc.)
**Versão usada na Rinha**: 5.18+
**Decisão rápida**: ferramenta auxiliar, não tem decisão no `RINHA_PLAN.md`

---

## O que é

SDKMAN! (Software Development Kit Manager) é uma ferramenta CLI para Linux/macOS que gerencia **múltiplas versões** de SDKs JVM-relacionados (Java, Maven, Gradle, Kotlin, Groovy, sbt, etc.) sem conflitar com o sistema. Permite trocar de versão por shell ou por projeto.

Comparável a `nvm` (Node.js), `pyenv` (Python), `rvm` (Ruby), `rustup` (Rust) — mas focado em JVM ecosystem.

## Objetivo geral

Java tem o problema clássico de "qual JDK estou usando?". O `apt` instala uma versão, IDE outra, scripts terceiros outra. SDKMAN resolve:

1. **Múltiplas versões coexistindo** sem mexer em PATH manualmente.
2. **Trocar instantaneamente** entre versões (`sdk use java 21.0.4-graalce`).
3. **Padronizar ambiente** entre dev e CI (arquivo `.sdkmanrc`).
4. **Catálogo curado**: vários distribuidores (Oracle, GraalVM, Mandrel, Temurin, Zulu, Liberica, Corretto, etc.).

## Pra que vamos usar no projeto

Instalar e gerenciar:
- **Java 21 LTS** (GraalVM CE ou Mandrel) — o sistema tem OpenJDK 25 do apt, precisa do 21 sem conflitar.
- **Maven 3.9.9** — não vem com Ubuntu por default.

Local: configurado uma vez no setup (ver `INSTALACAO.md` §1), depois rodar com `java`/`mvn` normais.

## Como funciona (em profundidade)

### Estrutura de diretórios

```
~/.sdkman/
├── bin/                      ← scripts (sdkman-init.sh)
├── candidates/
│   ├── java/
│   │   ├── 21.0.4-graalce/   ← uma instalação
│   │   ├── 17.0.10-tem/      ← outra (Temurin)
│   │   └── current → 21.0.4-graalce/   ← symlink "default"
│   ├── maven/
│   │   ├── 3.9.9/
│   │   └── current → 3.9.9/
│   └── ...
└── etc/
```

`PATH` aponta para `~/.sdkman/candidates/<sdk>/current/bin`. Trocar versão = atualizar symlink.

### Comandos principais

```bash
sdk install java 21.0.4-graalce    # baixa e instala
sdk uninstall java 21.0.4-graalce  # remove
sdk list java                      # lista todas as versões disponíveis (de várias distros)
sdk current                        # mostra versões atuais
sdk default java 21.0.4-graalce    # define como default em toda shell nova
sdk use java 17.0.10-tem           # troca SÓ na shell atual
sdk env install                    # cria .sdkmanrc no projeto
sdk env                            # auto-troca para versão do .sdkmanrc
sdk update                         # atualiza catálogo
sdk selfupdate                     # atualiza o próprio SDKMAN
sdk flush                          # limpa cache de installer
```

### Distribuidores Java disponíveis

SDKMAN lista versões de **vários** vendors:

| Sufixo | Distribuidor | Quando usar |
|---|---|---|
| `-graalce` | GraalVM Community Edition (Oracle Labs) | Native Image free |
| `-graal` | GraalVM Enterprise (Oracle, requer licença) | Suporte enterprise |
| `-mandrel` | Mandrel (Red Hat fork de GraalVM CE) | Native Image LTS-focado |
| `-tem` | Temurin (Eclipse Adoptium) | OpenJDK community padrão |
| `-zulu` | Zulu (Azul) | OpenJDK enterprise |
| `-amzn` | Corretto (Amazon) | OpenJDK Amazon |
| `-librca` | Liberica (BellSoft) | OpenJDK lightweight (small footprint) |
| `-sapmchn` | SapMachine (SAP) | OpenJDK SAP |
| (sem sufixo) | Oracle JDK (proprietary) | Oracle JDK | 

Para a Rinha: **`-graalce`** (Native Image grátis) ou **`-mandrel`** (mais leve).

### `.sdkmanrc` por projeto

Arquivo opcional na raiz do projeto:

```
java=21.0.4-graalce
maven=3.9.9
```

Com `sdkman_auto_env=true` em `~/.sdkman/etc/config`, SDKMAN troca automaticamente ao entrar no diretório.

## Exemplo de uso

```bash
# Setup inicial
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Instalar GraalVM 21
sdk install java 21.0.4-graalce
# (perguntará se quer setar como default, responda y)

# Validar
java -version
# → "GraalVM CE 21..."

# Instalar Maven
sdk install maven 3.9.9

# Trocar de Java rapidamente
sdk use java 17.0.10-tem
# (vale só na shell atual)

# Voltar pro 21
sdk default java 21.0.4-graalce

# Listar instalados
sdk list java | grep installed
```

## Tecnologias parecidas (alternativas)

| Ferramenta | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **jenv** | Mais leve, só Java | Sem catálogo curado, instalação manual de cada JDK | Quem prefere baixar JDK manual |
| **asdf** | Multi-linguagem (Java, Node, Python, ...) | Plugin separado para Java, menos features SDKMAN-específicas | Quem usa muitas linguagens |
| **mise (rtx)** | Sucessor moderno do asdf, mais rápido | Comunidade menor | Idem asdf |
| **Manual download** | Sem ferramenta extra | Configurar PATH/JAVA_HOME na mão, pesadelo de upgrade | One-off, sem necessidade de troca |
| **`update-alternatives`** (Ubuntu) | Built-in apt | Apt-only, sem GraalVM nativo | Quando você só usa apt repos |
| **Coursier** | Specialty para Scala | Foco em Scala | Scala devs |

SDKMAN ganha por: (1) GraalVM disponível direto, (2) integração natural com Maven/Gradle, (3) `.sdkmanrc` per project, (4) instalação trivial.

## Pegadinhas conhecidas

1. **Init não persiste**: depois de instalar, você precisa rodar `source "$HOME/.sdkman/bin/sdkman-init.sh"` em cada nova shell. SDKMAN normalmente adiciona linha no `.bashrc` automaticamente — confirmar.
2. **Shell não-bash**: SDKMAN suporta bash, zsh, fish (com plugins). PowerShell precisa de fork (`PowerShell SDKMAN`).
3. **Antivírus**: alguns antivírus em Windows/macOS bloqueiam download de JARs. Lista de exceções para `~/.sdkman/`.
4. **`sdk use` é por shell**: troca só na shell atual. Para todo o sistema, `sdk default`.
5. **Cache stale**: se nova versão não aparece em `sdk list java`, rodar `sdk update`.
6. **Conflito com `update-alternatives`**: se o apt instalou Java e configurou `update-alternatives`, ele pode aparecer no PATH antes do SDKMAN. Verifique com `which java`.

## Referências

- **Site oficial**: https://sdkman.io/
- **Install guide**: https://sdkman.io/install
- **Catálogo de Java disponíveis**: https://sdkman.io/jdks
- **Usage**: https://sdkman.io/usage
- **GitHub**: https://github.com/sdkman/sdkman-cli
- **`.sdkmanrc` docs**: https://sdkman.io/usage#env

## Veredito final na Rinha

Ferramenta de fundação. Sem ela, gerenciar Java 21 GraalVM ao lado do OpenJDK 25 do apt vira pesadelo. Configurar uma vez, esquecer.
