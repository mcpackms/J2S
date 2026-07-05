# J2S — Java / Dex / Jar Converter

One-command conversion between Java source, DEX, and JAR formats. Powered by Android D8, Baksmali, and dex2jar.

## Quick Start

```bash
# Java → Smali (default)
java -jar J2S.jar Hello.java

# Java → Dex only
java -jar J2S.jar --keep-dex Hello.java

# Dex → Smali
java -jar J2S.jar classes.dex

# Dex → Jar
java -jar J2S.jar --jar classes.dex

# Jar → Dex
java -jar J2S.jar app.jar

# Full options
java -jar J2S.jar -a android.jar --min-api 24 -o output app.jar
```

## Modes

The mode is auto-detected from input file extensions:

| Mode | Input | Default Output | Pipeline |
|---|---|---|---|
| **Java mode** | `.java` | `smali_out/*.smali` | `javac → d8 → baksmali` |
| `--keep-dex` | `.java` | `dex_out/classes.dex` | `javac → d8` |
| **Dex mode** | `.dex` | `*.smali` | `baksmali` |
| `--jar` | `.dex` | `*.jar` | `dex2jar` |
| **Jar mode** | `.jar` | `*.dex.jar` | `d8 --release` |

## Options

| Option | Description |
|---|---|
| `-o <path>` | Output path (default depends on mode) |
| `-a, --android-jar <jar>` | Android framework jar |
| `-l, --lib <jar>` | Additional library jar (repeatable) |
| `--min-api <N>` | Minimum API level for D8 (default: 21) |
| `--keep-dex` | Java mode: output .dex only, skip smali |
| `--jar` | Dex mode: output .jar instead of .smali |

## Examples

```bash
# Java → Smali
java -jar J2S.jar Hello.java

# Java → Smali with android.jar
java -jar J2S.jar -a android-24.jar Hello.java

# Java → Dex only
java -jar J2S.jar --keep-dex Hello.java

# Java → Dex with min-api
java -jar J2S.jar --keep-dex --min-api 26 Hello.java

# Dex → Smali
java -jar J2S.jar classes.dex

# Dex → Smali with custom output
java -jar J2S.jar -o smali_out classes.dex

# Dex → Jar
java -jar J2S.jar --jar classes.dex

# Merge multiple dex files into one jar
java -jar J2S.jar --jar classes.dex classes2.dex

# Jar → Dex
java -jar J2S.jar app.jar

# Jar → Dex with android.jar and min-api
java -jar J2S.jar -a android-24.jar --min-api 24 app.jar

# Jar → Dex with multiple libs
java -jar J2S.jar -a android.jar -l lib1.jar -l lib2.jar app.jar
```

## Project Structure

```
src/main/java/com/j2s/
├── J2S.java             # Entry point + CLI argument parsing
├── Utils.java           # Utility methods (run, delete, getJarPath)
├── JavaPipeline.java    # Java compilation pipeline (javac → d8 → baksmali)
├── DexSmali.java        # Dex → Smali disassembly
├── DexJar.java          # Dex → Jar (dex2jar)
└── JarDex.java          # Jar → Dex (D8 --release)
```

## Build

```bash
git clone https://github.com/mcpackms/J2S.git
cd J2S
mvn package
```

Output: `target/J2S.jar`

## Dependencies

| Component | Purpose | Version |
|---|---|---|
| D8/R8 | .class/.jar → .dex | 8.3.37 |
| Baksmali | .dex → .smali | 3.0.7 |
| dex2jar | .dex → .jar | 2.4.28 |

> All three are bundled into the fat JAR via `maven-shade-plugin`. No external installation required.
