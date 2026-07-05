# J2S — Java to Smali Converter

Converts Java source files to Smali (or DEX) using the Android toolchain (D8 + Baksmali).

## Usage

```bash
java -jar J2S.jar [options] <source.java...>
```

### Options

| Option | Description |
|---|---|
| `-o <dir>` | Output directory (default: `smali_out`) |
| `-a, --android-jar <jar>` | Android framework jar (required if source uses `android.*` APIs) |
| `-l, --lib <jar>` | Additional library jar (repeatable) |
| `--dex` | Dex-only mode: skip smali disassembly, output `classes.dex` only |

### Examples

```bash
# Basic: compile Java to Smali
java -jar J2S.jar Hello.java

# With android.jar and output dir
java -jar J2S.jar -a android.jar -o output Hello.java

# Multiple source files and library jars
java -jar J2S.jar -a android.jar -l support-lib.jar -o out *.java

# Dex-only mode (no smali)
java -jar J2S.jar --dex -o out Hello.java
```

## Pipeline

```
.java  ──javac──▶  .class  ──d8──▶  .dex  ──baksmali──▶  .smali
                   (temp)         (temp)      │           (output)
                                              │
                                   (--dex)    └──▶ classes.dex (output)
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
