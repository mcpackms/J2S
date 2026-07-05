# J2S — Java / Dex to Smali Converter

Converts Java source files to Smali (or DEX) using the Android toolchain (D8 + Baksmali). Also supports direct `.dex → .smali` disassembly.

## Usage

```bash
java -jar J2S.jar [options] <source.java... | source.dex...>
```

### Modes

| Mode | Command | Pipeline |
|---|---|---|
| **Java → Smali** (default) | `J2S.jar Hello.java` | `.java ──javac──▶ .class ──d8──▶ .dex ──baksmali──▶ .smali` |
| **Java → Dex** | `J2S.jar --dex Hello.java` | `.java ──javac──▶ .class ──d8──▶ .dex` |
| **Dex → Smali** | `J2S.jar classes.dex` | `.dex ──baksmali──▶ .smali` |

### Options

| Option | Description |
|---|---|
| `-o <dir>` | Output directory (default: `smali_out`) |
| `-a, --android-jar <jar>` | Android framework jar (required if source uses `android.*` APIs) |
| `-l, --lib <jar>` | Additional library jar (repeatable, Java mode only) |
| `--dex` | Dex-only output in Java mode (skip smali) |

### Examples

```bash
# Java → Smali
java -jar J2S.jar Hello.java

# Java → Smali with android.jar
java -jar J2S.jar -a android.jar -o output Hello.java

# Java → Smali with multiple sources and libs
java -jar J2S.jar -a android.jar -l support-lib.jar -o out *.java

# Java → Dex only
java -jar J2S.jar --dex Hello.java

# Dex → Smali
java -jar J2S.jar classes.dex

# Dex → Smali with custom output dir
java -jar J2S.jar -o smali_out classes.dex

# Multiple dex files
java -jar J2S.jar classes.dex classes2.dex
```

## Build

```bash
git clone https://github.com/mcpackms/J2S.git
cd J2S
mvn package
```

Output: `target/J2S.jar`

## Requirements

- Java 8+ runtime
- Maven (to build)
- Android SDK (optional, for `android.jar`)
