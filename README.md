# J2S — Java / Kotlin / Dex / Jar / APK Converter

One-command conversion between Java/Kotlin source, DEX, JAR and APK. Powered by Android D8, Baksmali, dex2jar, aapt2 and kotlinc.

It can also download Maven dependencies (transitive, BOM-aware, AAR-aware) and merge them into the output dex / APK.

## Quick Start

```bash
# 1. Configure at least one Maven repository (none is configured by default)
export J2S_REPOS=https://repo.maven.apache.org/maven2

# Java -> Smali (default)
java -jar J2S.jar Hello.java

# Kotlin -> Smali (K2S)
java -jar J2S.jar Hello.kt

# Java + Kotlin together (mixed mode)
java -jar J2S.jar Main.kt Util.java

# Java -> Dex only, with a dependency merged in
java -jar J2S.jar --keep-dex -D junit:junit:4.13.2 Hello.java

# Dex -> Smali
java -jar J2S.jar classes.dex

# Dex -> Jar
java -jar J2S.jar --jar classes.dex

# Jar -> Dex
java -jar J2S.jar app.jar

# Full APK with Maven dependencies + BOM
java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \
    --res res/ -a android.jar \
    -D androidx.compose:compose-bom:2024.02.00 \
    -D androidx.appcompat:appcompat -D androidx.compose.ui:ui \
    Main.kt
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
| `-a, --android-jar <jar>` | Android framework jar (required for `--apk`) |
| `-l, --lib <jar>` | Additional library jar (repeatable, compile classpath only, **not** merged) |
| `--min-api <N>` | Minimum API level for D8 (default: 21) |
| `--keep-dex` | Source modes: output `.dex` only, skip smali |
| `--jar` | Dex mode: output `.jar` instead of `.smali` |
| `--kotlin-home <path>` | Kotlin mode: override kotlinc location (default: PATH) |
| `--compose-plugin <jar>` | Compose compiler plugin jar passed as `-Xplugin` |
| `-v, --verbose` | Print dependency configuration and resolved artifacts |
| `-h, --help` | Show help |

### Maven dependencies

| Option | Description |
|---|---|
| `-D, --dep <coord>` | Maven coordinate (repeatable), see below |
| `--repo <url>` | Maven repository URL (repeatable) |
| `--local-repo <dir>` | Local repository (default `~/.m2/repository`, `--cache-dir` is an alias) |
| `--settings <file>` | Explicit `settings.xml` (mirrors / proxies / servers) |
| `--offline` | Resolve from the local repository only |
| `--no-transitive` | Resolve direct dependencies only |
| `--exclude <g:a>` | Exclude a coordinate from transitive resolution (repeatable) |
| `--compose-plugin-version <v>` | Resolve the Compose compiler plugin from Maven |
| `--dexres <file>` | Where to write AAR resources as a zip |
| `--assets <dir>` | User assets directory for APK builds |
| `--dep-lock <file>` | Write / verify a dependency lock file |
| `--update-dep-lock` | Update an existing lock file instead of verifying it |

### APK build

Requires `--manifest`, `--apk` and `-a/--android-jar`. `aapt2` is located via `--aapt2`, then `PATH`, then `$ANDROID_HOME`/`$ANDROID_SDK_ROOT`/`$PREFIX/opt/android-sdk` build-tools.

| Option | Description |
|---|---|
| `--manifest <file>` | `AndroidManifest.xml` |
| `--res <dir>` | `res/` directory (user resources, highest priority) |
| `--assets <dir>` | `assets/` directory (merged on top of AAR assets) |
| `--apk <file>` | Output APK |
| `--target-sdk <N>` | Target SDK version (default: 34) |
| `--aapt2 <path>` | Explicit aapt2 path |
| `--no-manifest-merge` | Do not merge AAR `AndroidManifest.xml` files |

### Dex cache

`kotlin-stdlib.jar` and resolved dependencies are dexed **once** and reused on subsequent builds. The cache key is a SHA-256 fingerprint over the cache format version, the D8 version, `--min-api`, `android.jar`, every `--lib` and the contents of every mergeable jar — so changing any of them correctly invalidates the entry. Cache writes are atomic, concurrent builds of the same key are deduplicated via a lock file, and stale entries are pruned by LRU (12 entries / 512 MB).

**The cache is a pure accelerator.** If the cache directory is unwritable, the fingerprint cannot be computed, or an entry is corrupt, J2S prints one warning line and continues the build normally.

| Option | Description |
|---|---|
| `--dex-cache <path>` | Cache directory (default: `$J2S_CACHE/dex`, `$XDG_CACHE_HOME/j2s/dex`, `~/.cache/j2s/dex`) |
| `--no-dex-cache` | Disable the cache |
| `--clear-dex-cache` | Clear the cache and exit (works without input files) |

## Maven dependencies

### Repositories are empty by default

J2S never assumes that a machine can reach Maven Central or Google Maven. **No remote repository is configured out of the box.** If you pass `-D` without configuring a repository, J2S prints a reminder and only uses the local repository:

```
Warning: No Maven repository configured.
  Pass --repo <url> or set J2S_REPOS (comma separated), e.g.
    export J2S_REPOS=https://repo.maven.apache.org/maven2
  Only the local repository /root/.m2/repository will be used.
```

Configuration precedence: CLI `--repo` → `J2S_REPOS` → `settings.xml` mirrors (mirrors transform configured repositories) → no built-in remote.

Behind a mirror (e.g. in China):

```bash
export J2S_REPOS="https://maven.aliyun.com/repository/central,https://maven.aliyun.com/repository/google"
java -jar J2S.jar -D androidx.appcompat:appcompat:1.6.1 ...
```

### Coordinates and packaging

```
g:a                      version supplied by a BOM
g:a:v
g:a:v:classifier
g:a:v@jar|aar|pom        force the packaging
```

When no `@ext` is given, J2S reads the POM and uses its `<packaging>`: `pom` → BOM, `aar` → AAR, anything else → jar.

`-l/--lib` and `-D/--dep` are different:

| | compile classpath | merged into output dex | downloaded |
|---|---|---|---|
| `-l/--lib` | yes | no | no (local file) |
| `-D/--dep` | yes | yes | yes |

### Transitive resolution

* scopes `compile` and `runtime` are included; `test`, `provided` and `optional` are filtered
* Maven conflict mediation (nearest wins) and dependencyManagement from POMs are applied
* `--no-transitive` resolves only the coordinates you pass
* `--exclude g:a` removes a coordinate from the transitive graph
* AndroidX/Gradle "strict" versions are published as singleton ranges (`[2.5.1]`). J2S treats these as soft requirements and uses nearest-wins (like Gradle) instead of aborting with `Could not resolve version conflict`; real ranges (e.g. `[1.0,2.0)`) are still enforced

### BOMs

Pass a BOM anywhere in the `-D` list; later coordinates may omit the version:

```bash
java -jar J2S.jar --keep-dex \
  -D androidx.compose:compose-bom:2024.02.00 \
  -D androidx.compose.ui:ui \
  -D androidx.compose.material3:material3 \
  Main.kt
```

Import-scope BOMs inside a BOM (`<scope>import</scope>`) are followed recursively. BOMs are also applied as `dependencyManagement` to the whole transitive graph, so transitive versions are aligned with the BOM.

### AARs

Every resolved AAR is unpacked into a content-addressed cache (`<local-repo>/.j2s-aar/<sha256>/`):

| AAR part | How it is used |
|---|---|
| `classes.jar` | compile classpath + merged into the output dex |
| `libs/*.jar` | same as `classes.jar` |
| `res/` | compiled by `aapt2` as an overlay; the user's `--res` has the highest priority |
| `assets/` | merged with user `--assets` (user wins) and packed into the APK |
| `jni/<abi>/*.so` | packed into the APK as `lib/<abi>/*.so` |
| `AndroidManifest.xml` | conservatively merged into the user manifest (see below) |

Extraction is atomic and safe against zip-slip entries.

### dexres.zip — all AAR resources in one file

When AAR dependencies provide resources/assets, J2S always writes a `dexres.zip` (default: next to `--apk`, otherwise `<out>/dexres.zip`, override with `--dexres <file>`):

```
dexres.zip
├── res/...                                merged resources (later AAR wins on conflict)
├── assets/...                             merged assets
├── axml/
│   ├── res/...                            merged res XML compiled to Android binary XML
│   └── aar/<label>/res/...                per-AAR res XML compiled to Android binary XML
└── aar/
    ├── androidx.appcompat_appcompat_1.6.1/
    │   ├── res/...                        per-AAR resources, nothing lost
    │   ├── assets/...
    │   ├── jni/...
    │   └── AndroidManifest.xml
    └── ...
```

The merged tree is convenient for tooling; the per-AAR tree guarantees that resources with identical relative paths from different AARs are all preserved.

`axml/` contains every `res/**/*.xml` encoded as Android binary XML (AXML, `ResXMLTree` chunk format) by a pure-Java encoder — no `aapt2` or `android.jar` is required. Element/attribute names, namespaces and attribute values are preserved as strings; the optional resource-id map chunk is omitted because mapping `android:*` attributes to framework resource ids requires a framework resource table. Use `aapt2` (via `--apk`) for real APK packaging; `axml/` is for consumers that read AXML directly. Files that cannot be parsed as XML are skipped with a warning.

### AndroidManifest merge (AAR)

`--manifest` is merged conservatively with every AAR manifest (disable with `--no-manifest-merge`):

* `uses-sdk`: min/target SDK raised to the maximum of user + AARs
* `uses-permission` / `uses-permission-sdk-23` / `uses-feature`: union by name
* `<application>` children (`activity`, `service`, `provider`, `receiver`, `meta-data`, `uses-library`, `activity-alias`): union by `android:name`

Not merged: `application` attributes, `package`, `<queries>`, manifest placeholders, `tools:` directives. This is not a full Gradle manifest merger.

### Offline mode and the local repository

```bash
export J2S_OFFLINE=1                 # or --offline
java -jar J2S.jar -D junit:junit:4.13.2 --keep-dex Hello.java
```

The local repository defaults to `~/.m2/repository` and can be changed with `--local-repo`, `J2S_LOCAL_REPO`/`J2S_M2`, or `settings.xml`'s `<localRepository>`.

### settings.xml, mirrors, proxies and credentials

J2S reads `~/.m2/settings.xml` (or `--settings` / `J2S_MAVEN_SETTINGS`) for:

* `<localRepository>`
* `<mirrors>` (`*`, `external:*`, comma lists, `!exclusions`)
* `<proxies>` (active entries, `nonProxyHosts`)
* `<servers>` (username/password for repository ids)

Encrypted passwords (`{...}`) are not supported and are ignored with a warning. A quick proxy alternative is `J2S_PROXY=[http://][user:pass@]host:port` plus `J2S_NO_PROXY=host1|host2`.

### Lock file

```bash
# first run writes the lock file
java -jar J2S.jar -D androidx.appcompat:appcompat:1.6.1 --keep-dex \
    --dep-lock deps.lock Main.java

# later runs verify versions and SHA-256 of every resolved artifact
java -jar J2S.jar -D androidx.appcompat:appcompat:1.6.1 --keep-dex \
    --dep-lock deps.lock Main.java

# accept a changed resolution
java -jar J2S.jar ... --dep-lock deps.lock --update-dep-lock
```

### Compose compiler plugin

```bash
java -jar J2S.jar --keep-dex --compose-plugin-version 1.5.12 Main.kt
```

This resolves `androidx.compose.compiler:compiler:<v>` (Kotlin 1.9-), falling back to `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:<v>` (Kotlin 2.x). Alternatively pass a local jar with `--compose-plugin <jar>`.

### Environment variables

| Variable | Description |
|---|---|
| `J2S_REPOS` | Comma separated Maven repository URLs |
| `J2S_LOCAL_REPO` / `J2S_M2` | Maven local repository directory |
| `J2S_OFFLINE` | `1/true` = offline mode |
| `J2S_MAVEN_SETTINGS` | Path to `settings.xml` |
| `J2S_PROXY` | HTTP proxy: `[http://][user:pass@]host:port` |
| `J2S_NO_PROXY` | Hosts that bypass the proxy |

### Limitations

* AAR manifest merging is conservative (see above), not a full Gradle merger.
* APKs are not signed (use `apksigner` / `zipalign` afterwards).
* JNI libraries are packed compressed; keep `android:extractNativeLibs` at its default (`true`) for such APKs.
* Maven POM metadata is used (Gradle module metadata is ignored), which is fine for AndroidX/Compose but may miss Gradle-specific constraints.
* Encrypted `settings.xml` passwords are unsupported.

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

# ── Dependencies ──
export J2S_REPOS=https://repo.maven.apache.org/maven2
java -jar J2S.jar -D junit:junit:4.13.2 --keep-dex Hello.java
java -jar J2S.jar -D com.fasterxml.jackson:jackson-bom:2.17.0 \
    -D com.fasterxml.jackson.core:jackson-databind --keep-dex Main.java
java -jar J2S.jar --repo https://maven.aliyun.com/repository/google \
    -D androidx.core:core:1.12.0 --keep-dex Main.java     # writes dexres.zip

# ── Compose ──
java -jar J2S.jar --keep-dex -D androidx.compose.ui:ui:1.6.0 \
    --compose-plugin-version 1.5.12 Main.kt

# ── APK ──
java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \
    --res res/ --assets assets/ -a android.jar \
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
├── J2S.java              # Entry point, CLI parsing, mode detection, validation, dispatch
├── BuildContext.java     # Shared options for the Java/Kotlin/mixed pipelines
├── Utils.java            # run(), delete(), error(), getJarPath()
├── JavaPipeline.java     # javac → d8 → baksmali + shared compileClassesToDex() backend
├── KotlinPipeline.java   # kotlinc → d8 → baksmali (K2S) + kotlinc/stdlib resolution
├── MixedPipeline.java    # .java + .kt compiled together
├── DexCache.java         # Content-addressed cache for dexed dependencies
├── DependencyResolver.java # Aether resolution (transitive, BOM, AAR extraction)
├── RepoConfig.java       # CLI/env repository configuration
├── MavenSettings.java    # settings.xml: local repo, mirrors, proxies, servers
├── DepResources.java     # Resolved jars/AAR payloads + dexres.zip writer
├── DepLock.java          # Dependency lock file (write/verify SHA-256)
├── Aapt2Builder.java     # aapt2 compile/link, R.java compilation, APK packaging
├── ManifestMerger.java   # conservative AAR manifest merge
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

Tests (offline — they build a `file://` Maven repository under `target/`):

```bash
mvn test
```

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
