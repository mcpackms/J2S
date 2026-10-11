# J2S — Java / Kotlin / Dex / Jar / APK Converter

One-command conversion between Java/Kotlin source, DEX, JAR and APK. Powered by Android D8, Baksmali, dex2jar, aapt2 and kotlinc.

## Quick Start

```bash
# Java → Smali (default)
java -jar J2S.jar Hello.java

# Kotlin → Smali (K2S)
java -jar J2S.jar Hello.kt

# Java + Kotlin together (mixed mode)
java -jar J2S.jar Main.kt Util.java

# Java → Dex only
java -jar J2S.jar --keep-dex Hello.java

# Dex → Smali
java -jar J2S.jar classes.dex

# Dex → Jar
java -jar J2S.jar --jar classes.dex

# Jar → Dex
java -jar J2S.jar app.jar

# Full APK, with Maven deps resolved automatically
java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \
    --res res/ --keep-dex -a android.jar \
    -D androidx.appcompat:appcompat:1.6.1 Main.kt
```

## Modes

The mode is auto-detected from input file extensions. Mixing `.java`/`.kt` with `.dex` or `.jar` in one invocation is rejected.

| Mode | Input | Default Output | Pipeline |
|---|---|---|---|
| Java | `.java` | `smali_out/*.smali` | javac → d8 → baksmali |
| Java | `.java` + `--keep-dex` | `dex_out/classes.dex` | javac → d8 |
| Kotlin (K2S) | `.kt`, `.kts` | `smali_out/*.smali` | kotlinc → d8 → baksmali |
| Kotlin | `.kt`, `.kts` + `--keep-dex` | `dex_out/classes.dex` | kotlinc → d8 |
| Mixed | `.java` + `.kt` | `smali_out/*.smali` | kotlinc (symbol resolution) → javac → d8 → baksmali |
| Dex | `.dex` | `*.smali` | baksmali |
| Dex | `.dex` + `--jar` | `*.jar` | dex2jar |
| Jar | `.jar` | `*.dex.jar` | d8 `--release` |

Multidex output (`classes.dex`, `classes2.dex`, …) is produced automatically when needed.

## Options

### Output & toolchain

| Option | Description |
|---|---|
| `-o <path>` | Output path (default depends on mode) |
| `-a, --android-jar <jar>` | Android framework jar |
| `-l, --lib <jar>` | Additional library jar (repeatable) |
| `--min-api <N>` | Minimum API level for D8 (default: 21) |
| `--keep-dex` | Source modes: output `.dex` only, skip smali |
| `--jar` | Dex mode: output `.jar` instead of `.smali` |
| `--kotlin-home <path>` | Kotlin mode: override kotlinc location (default: PATH) |

### Maven dependency resolution

| Option | Description |
|---|---|
| `-D, --dep <g:a:v>` | Maven coordinate, auto-downloaded and merged into the dex (repeatable) |
| `--repo <url>` | Extra Maven repository URL (repeatable) |
| `--cache-dir <path>` | Maven local repo (default: `~/.m2/repository`) |

AARs are handled automatically: `classes.jar` is extracted and merged, and its `res/` directory is fed to aapt2 with the correct overlay priority (user `res/` always wins). Compose projects can auto-resolve the compiler plugin:

```bash
java -jar J2S.jar --keep-dex -D androidx.compose.ui:ui:1.6.0 \
    --compose-plugin-version 1.5.12 Main.kt
```

### APK build

Requires both `--manifest` and `--apk`. `aapt2` is located via `--aapt2`, then `PATH`, then `$ANDROID_HOME`/`$ANDROID_SDK_ROOT`/`$PREFIX/opt/android-sdk` build-tools.

| Option | Description |
|---|---|
| `--manifest <file>` | `AndroidManifest.xml` |
| `--res <dir>` | `res/` directory (user resources, highest priority) |
| `--apk <file>` | Output APK |
| `--target-sdk <N>` | Target SDK version (default: 34) |
| `--aapt2 <path>` | Explicit aapt2 path |

### Dex cache

`kotlin-stdlib.jar` and Maven dependencies are dexed **once** and reused on subsequent builds. On this machine a cold Kotlin build spends ~32s dexing the stdlib alone; with the cache warm that cost disappears.

| Option | Description |
|---|---|
| `--dex-cache <path>` | Cache directory (default: `$J2S_CACHE/dex`, `$XDG_CACHE_HOME/j2s/dex`, `~/.cache/j2s/dex`) |
| `--no-dex-cache` | Disable the cache |
| `--clear-dex-cache` | Clear the cache and exit (works without input files) |

The cache key is a SHA-256 fingerprint over the cache format version, the D8 version, `--min-api`, `android.jar`, every `--lib` and the contents of every mergeable jar — so changing any of them correctly invalidates the entry. Cache writes are atomic, concurrent builds of the same key are deduplicated via a lock file, and stale entries are pruned by LRU (12 entries / 512 MB).

**The cache is a pure accelerator.** If the cache directory is unwritable, the fingerprint cannot be computed, or an entry is corrupt, J2S prints one warning line and continues the build normally.

## Examples

```bash
# ── Java ──
java -jar J2S.jar Hello.java
java -jar J2S.jar -a android-24.jar Hello.java
java -jar J2S.jar --keep-dex --min-api 26 Hello.java

# ── Kotlin ──
java -jar J2S.jar Hello.kt
java -jar J2S.jar -a android.jar -l kotlin-stdlib.jar *.kt
java -jar J2S.jar --kotlin-home /opt/kotlinc Hello.kt

# ── Mixed ──
java -jar J2S.jar Main.kt Util.java

# ── Compose ──
java -jar J2S.jar --keep-dex -D androidx.compose.ui:ui:1.6.0 \
    --compose-plugin-version 1.5.12 Main.kt

# ── APK ──
java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \
    --res res/ --keep-dex -a android.jar \
    -D androidx.appcompat:appcompat:1.6.1 Main.kt

# ── Dex / Jar ──
java -jar J2S.jar -o smali_out classes.dex
java -jar J2S.jar --jar classes.dex
java -jar J2S.jar --jar classes.dex classes2.dex     # merge into one jar
java -jar J2S.jar -a android-24.jar --min-api 24 app.jar

# ── Cache ──
java -jar J2S.jar --clear-dex-cache
java -jar J2S.jar --no-dex-cache Hello.kt
```

## Kotlin Notes

K2S requires `kotlinc` on `PATH`, or `--kotlin-home <dir>`. J2S looks it up in this order:

1. `--kotlin-home <dir>` → `<dir>/bin/kotlinc[.bat]`
2. System `PATH` → `kotlinc` / `kotlinc.bat`

Verify with `kotlinc -version`.

`kotlin-stdlib.jar` (plus `-jdk7` / `-jdk8` when present) is picked up from `<kotlin-home>/lib` and **merged into the output dex automatically** — you do not need to pass it via `-l`. If your code references stdlib APIs that the bundled stdlib does not provide, supply a newer one with `-l`.

## Project Structure

```
src/main/java/com/j2s/
├── J2S.java              # Entry point, CLI parsing, mode detection, validation
├── Utils.java            # run(), delete(), error(), getJarPath()
├── JavaPipeline.java     # javac → d8 → baksmali + shared compileClassesToDex() backend
├── KotlinPipeline.java   # kotlinc → d8 → baksmali  (K2S) + kotlinc/stdlib resolution
├── MixedPipeline.java    # .java + .kt compiled together
├── DexCache.java         # Content-addressed cache for dexed stdlib + dependencies
├── DependencyResolver.java # Maven (Aether) resolution, AAR unpacking
├── Aapt2Builder.java     # aapt2 compile/link, R.java compilation, APK packaging
├── DexSmali.java         # Dex → Smali (baksmali)
├── DexJar.java           # Dex → Jar (dex2jar)
└── JarDex.java           # Jar → Dex (D8 --release)
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
| D8/R8 | `.class`/`.jar` → `.dex` | 9.1.31 |
| Baksmali | `.dex` → `.smali` | 3.0.7 |
| dex2jar | `.dex` → `.jar` | 2.4.28 |
| Maven Resolver (Aether) | Maven dependency resolution | 1.9.18 |
| kotlinc | `.kt` → `.class` | external (PATH) |
| aapt2 | resources / APK packaging | external (Android build-tools) |

D8, Baksmali, dex2jar and the Maven resolver are bundled into the fat JAR via maven-shade-plugin — no external installation required. `kotlinc` is **not** bundled (install it or point `--kotlin-home` at it); `aapt2` is only needed for `--manifest` / `--apk`.

## License

See [LICENSE](LICENSE).