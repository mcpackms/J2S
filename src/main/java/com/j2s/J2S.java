package com.j2s;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * J2S — Convert between Java/Kotlin source, DEX, and JAR formats.
 *
 * <p>Modes (auto-detected by input file extension):
 * <ul>
 *   <li>{@code .java}                → Java compilation pipeline</li>
 *   <li>{@code .kt} / {@code .kts}   → Kotlin compilation pipeline (K2S)</li>
 *   <li>{@code .java} + {@code .kt}  → Mixed Java + Kotlin pipeline</li>
 *   <li>{@code .dex}                 → Dex disassembly or dex2jar</li>
 *   <li>{@code .jar}                 → D8 compression to dex</li>
 * </ul>
 *
 * <p>Source modes support:
 * <ul>
 *   <li>{@code -D/--dep} Maven dependency resolution (transitive, BOM + AAR aware)</li>
 *   <li>{@code --manifest} + {@code --res} + {@code --apk} full APK build with aapt2</li>
 *   <li>{@code --compose-plugin} / {@code --compose-plugin-version} Compose compiler plugin</li>
 *   <li>Multidex output (classes.dex, classes2.dex, ...)</li>
 * </ul>
 */
public class J2S {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            boolean verbose = false;
            for (String a : args) {
                if (a.equals("-v") || a.equals("--verbose")) verbose = true;
            }
            if (verbose) e.printStackTrace();
            String msg = e.getMessage();
            Utils.error(msg == null || msg.isEmpty() ? e.toString() : msg);
        }
    }

    private static void run(String[] args) throws Exception {
        String selfJar = Utils.getJarPath();
        if (selfJar == null) {
            Utils.error("Cannot determine J2S.jar path. Run with java -jar J2S.jar");
        }

        if (args.length == 0) usage(1);

        // ── Parsed options ──
        Path out = Paths.get("smali_out");
        Path androidJar = null;
        List<Path> libs = new ArrayList<>();
        List<Path> inputFiles = new ArrayList<>();
        String composePlugin = null;
        String composePluginVersion = null;
        String kotlinHome = null;
        Path manifest = null;
        Path resDir = null;
        Path assetsDir = null;
        String aapt2Path = null;
        Path outApk = null;
        Integer targetSdk = null;
        boolean keepDex = false;
        boolean makeJar = false;
        int minApi = 21;
        boolean outSpecified = false;
        Path dexCacheRoot = null;
        boolean noDexCache = false;
        boolean clearDexCache = false;

        // ── Dependency resolution options ──
        List<String> depCoords = new ArrayList<>();
        List<String> extraRepos = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        Path localRepo = null;
        Path settingsPath = null;
        boolean offline = false;
        boolean noTransitive = false;
        boolean manifestMerge = true;
        Path dexresPath = null;
        Path depLockFile = null;
        boolean updateDepLock = false;
        boolean verbose = false;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-o":
                    if (++i < args.length) {
                        out = Paths.get(args[i]).toAbsolutePath();
                        outSpecified = true;
                    } else Utils.error("Missing output dir after -o");
                    break;
                case "-a": case "--android-jar":
                    if (++i < args.length) androidJar = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing android.jar after " + args[i-1]);
                    break;
                case "-l": case "--lib":
                    if (++i < args.length) libs.add(Paths.get(args[i]).toAbsolutePath());
                    else Utils.error("Missing lib jar after " + args[i-1]);
                    break;
                case "-D": case "--dep":
                    if (++i < args.length) depCoords.add(args[i]);
                    else Utils.error("Missing Maven coordinate after " + args[i-1]);
                    break;
                case "--repo":
                    if (++i < args.length) extraRepos.add(args[i]);
                    else Utils.error("Missing URL after --repo");
                    break;
                case "--local-repo": case "--cache-dir":
                    if (++i < args.length) localRepo = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing directory after " + args[i-1]);
                    break;
                case "--settings":
                    if (++i < args.length) {
                        settingsPath = Paths.get(args[i]).toAbsolutePath();
                        if (!Files.isRegularFile(settingsPath)) {
                            Utils.error(settingsPath + " does not exist");
                        }
                    } else Utils.error("Missing file after --settings");
                    break;
                case "--offline":
                    offline = true;
                    break;
                case "--no-transitive":
                    noTransitive = true;
                    break;
                case "--exclude":
                    if (++i < args.length) {
                        String e = args[i].trim();
                        if (!e.contains(":")) Utils.error("--exclude expects group:artifact, got " + e);
                        excludes.add(e);
                    } else Utils.error("Missing group:artifact after --exclude");
                    break;
                case "--compose-plugin-version":
                    if (++i < args.length) composePluginVersion = args[i];
                    else Utils.error("Missing version after --compose-plugin-version");
                    break;
                case "--dexres":
                    if (++i < args.length) dexresPath = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing file after --dexres");
                    break;
                case "--dep-lock":
                    if (++i < args.length) depLockFile = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing file after --dep-lock");
                    break;
                case "--update-dep-lock":
                    updateDepLock = true;
                    break;
                case "--no-manifest-merge":
                    manifestMerge = false;
                    break;
                case "-v": case "--verbose":
                    verbose = true;
                    break;
                case "--dex-cache":
                    if (++i < args.length) dexCacheRoot = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing dir after --dex-cache");
                    break;
                case "--no-dex-cache":
                    noDexCache = true;
                    break;
                case "--clear-dex-cache":
                    clearDexCache = true;
                    break;
                case "--compose-plugin":
                    if (++i < args.length) {
                        Path cp = Paths.get(args[i]).toAbsolutePath();
                        if (!Files.exists(cp)) Utils.error(cp + " does not exist");
                        composePlugin = cp.toString();
                    } else Utils.error("Missing jar after --compose-plugin");
                    break;
                case "--manifest":
                    if (++i < args.length) {
                        manifest = Paths.get(args[i]).toAbsolutePath();
                        if (!Files.exists(manifest)) Utils.error(manifest + " does not exist");
                    } else Utils.error("Missing file after --manifest");
                    break;
                case "--res":
                    if (++i < args.length) {
                        resDir = Paths.get(args[i]).toAbsolutePath();
                        if (!Files.isDirectory(resDir)) Utils.error(resDir + " is not a directory");
                    } else Utils.error("Missing dir after --res");
                    break;
                case "--assets":
                    if (++i < args.length) {
                        assetsDir = Paths.get(args[i]).toAbsolutePath();
                        if (!Files.isDirectory(assetsDir)) Utils.error(assetsDir + " is not a directory");
                    } else Utils.error("Missing dir after --assets");
                    break;
                case "--aapt2":
                    if (++i < args.length) aapt2Path = args[i];
                    else Utils.error("Missing path after --aapt2");
                    break;
                case "--apk":
                    if (++i < args.length) outApk = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing file after --apk");
                    break;
                case "--target-sdk":
                    if (++i < args.length) {
                        try { targetSdk = Integer.parseInt(args[i]); }
                        catch (NumberFormatException e) { Utils.error("--target-sdk must be a number"); }
                    } else Utils.error("Missing number after --target-sdk");
                    break;
                case "--dex": case "--keep-dex":
                    keepDex = true;
                    break;
                case "--jar":
                    makeJar = true;
                    break;
                case "--min-api":
                    if (++i < args.length) {
                        try { minApi = Integer.parseInt(args[i]); }
                        catch (NumberFormatException e) { Utils.error("--min-api must be a number"); }
                    } else Utils.error("Missing API level after --min-api");
                    break;
                case "--kotlin-home":
                    if (++i < args.length) kotlinHome = args[i];
                    else Utils.error("Missing path after --kotlin-home");
                    break;
                case "-h": case "--help":
                    usage(0);
                    break;
                default:
                    Path p = Paths.get(args[i]).toAbsolutePath();
                    if (!Files.exists(p)) Utils.error(p + " does not exist");
                    String name = p.toString().toLowerCase();
                    if (name.endsWith(".java") || name.endsWith(".kt") ||
                        name.endsWith(".kts") || name.endsWith(".dex") ||
                        name.endsWith(".jar")) {
                        inputFiles.add(p);
                    } else {
                        Utils.error("Unrecognized file or option: " + args[i]);
                    }
            }
            i++;
        }

        // ── Dex cache ──
        DexCache.configure(dexCacheRoot, !noDexCache);
        if (clearDexCache) {
            System.out.println("Dex cache: " + DexCache.root());
            System.out.println("Before: " + DexCache.stats());
            long n = DexCache.clear();
            System.out.println("Cleared " + n + " entrie(s). After: " + DexCache.stats());
            if (inputFiles.isEmpty()) return;
        }

        if (inputFiles.isEmpty())
            Utils.error("At least one .java, .kt, .dex, or .jar file is required");

        // ── Detect mode ──
        boolean hasJava = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".java"));
        boolean hasKotlin = inputFiles.stream().anyMatch(f -> {
            String n = f.toString().toLowerCase();
            return n.endsWith(".kt") || n.endsWith(".kts");
        });
        boolean hasDex = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".dex"));
        boolean hasJar = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".jar"));

        int kindCount = ((hasJava || hasKotlin) ? 1 : 0) + (hasDex ? 1 : 0) + (hasJar ? 1 : 0);
        if (kindCount > 1)
            Utils.error("Cannot mix source (.java/.kt), .dex, and .jar inputs");

        boolean sourceMode = hasJava || hasKotlin;
        boolean kotlinOnly = hasKotlin && !hasJava;
        boolean mixedMode  = hasJava && hasKotlin;

        // ── Flag / mode consistency ──
        if (keepDex && !sourceMode)
            Utils.error("--keep-dex only applies to .java / .kt input");
        if (makeJar && !hasDex)
            Utils.error("--jar only applies to .dex input");
        if (keepDex && makeJar)
            Utils.error("--keep-dex and --jar are mutually exclusive");
        if (outApk != null && manifest == null)
            Utils.error("--apk requires --manifest");
        if (manifest != null && outApk == null)
            Utils.error("--manifest requires --apk");
        if (manifest != null && !sourceMode)
            Utils.error("--manifest only applies to .java / .kt input");
        if (resDir != null && manifest == null)
            Utils.error("--res requires --manifest (and --apk)");
        if (assetsDir != null && manifest == null)
            Utils.error("--assets requires --manifest (and --apk)");
        if (manifest != null && androidJar == null)
            Utils.error("APK builds require -a/--android-jar (aapt2 link needs android.jar)");
        if ((!depCoords.isEmpty() || composePluginVersion != null) && !sourceMode)
            Utils.error("-D / --compose-plugin-version only apply to .java / .kt input");
        if (dexresPath != null && !sourceMode)
            Utils.error("--dexres only applies to .java / .kt input");
        if (depLockFile != null && depCoords.isEmpty())
            Utils.error("--dep-lock requires -D/--dep");
        if (updateDepLock && depLockFile == null)
            Utils.error("--update-dep-lock requires --dep-lock");
        if (composePlugin != null && composePluginVersion != null)
            Utils.error("--compose-plugin and --compose-plugin-version are mutually exclusive");

        // ── Infer default output path ──
        if (hasJar && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.jar$", ".dex.jar"));
        } else if (keepDex && sourceMode && !outSpecified && outApk == null) {
            out = Paths.get("dex_out");
        } else if (makeJar && hasDex && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.dex$", ".jar"));
        }

        // ── Validate referenced paths ──
        if (androidJar != null && !Files.exists(androidJar))
            Utils.error(androidJar + " does not exist");
        for (Path lib : libs)
            if (!Files.exists(lib)) Utils.error(lib + " does not exist");

        // ── Locate aapt2 if needed ──
        Path aapt2Exe = null;
        if (manifest != null) {
            aapt2Exe = Aapt2Builder.findAapt2(aapt2Path);
            if (aapt2Exe == null) {
                Utils.error("aapt2 not found. Install Android build-tools, "
                        + "set ANDROID_HOME, or pass --aapt2 <path>.");
            }
            System.out.println("aapt2: " + aapt2Exe);
        }

        // ── Resolve Maven dependencies ──
        DepResources resources = DepResources.EMPTY;
        boolean dependencyRequested = !depCoords.isEmpty() || composePluginVersion != null;
        if (sourceMode && dependencyRequested) {
            RepoConfig cfg = RepoConfig.create(localRepo, extraRepos, offline,
                    !noTransitive, excludes, settingsPath);
            if (!cfg.hasRemotes()) {
                System.err.println("Warning: " + cfg.reminder());
            }
            if (verbose) {
                System.out.println("Dependency configuration:");
                System.out.print(cfg.describe());
            }

            DependencyResolver resolver;
            try {
                resolver = new DependencyResolver(cfg);
            } catch (Exception e) {
                Utils.error("Cannot initialize Maven resolver: " + e.getMessage());
                return;
            }

            if (!depCoords.isEmpty()) {
                try {
                    resources = resolver.resolve(depCoords);
                } catch (Exception e) {
                    Utils.error(e.getMessage());
                }
                System.out.println("Dependencies: " + resources.summary());
                if (verbose) {
                    for (DepResources.ResolvedArtifact a : resources.artifacts) {
                        System.out.println("  " + a.coord);
                    }
                }
                if (resources.hasAarResources()) {
                    Path dexres = dexresPath != null
                            ? dexresPath
                            : defaultDexResPath(outApk, out);
                    try {
                        resources.writeDexResZip(dexres);
                    } catch (IOException e) {
                        System.err.println("Warning: cannot write " + dexres + " (" + e + ")");
                    }
                }
                if (depLockFile != null) {
                    try {
                        DepLock.writeOrVerify(depLockFile, updateDepLock, resources, cfg);
                    } catch (Exception e) {
                        Utils.error(e.getMessage());
                    }
                }
            }

            if (composePluginVersion != null) {
                try {
                    composePlugin = resolveComposePlugin(resolver, composePluginVersion).toString();
                } catch (Exception e) {
                    Utils.error("Cannot resolve Compose compiler plugin "
                            + composePluginVersion + ": " + e.getMessage());
                }
                System.out.println("Compose plugin: " + composePlugin);
            }
        }

        // ── Dispatch ──
        BuildContext ctx = new BuildContext();
        ctx.androidJar = androidJar;
        ctx.libs = libs;
        ctx.deps = resources.jars;
        ctx.minApi = minApi;
        ctx.keepDex = keepDex;
        ctx.selfJar = selfJar;
        ctx.kotlinHome = kotlinHome;
        ctx.composePlugin = composePlugin;
        ctx.manifest = manifest;
        ctx.resDir = resDir;
        ctx.assetsDir = assetsDir;
        ctx.resources = resources;
        ctx.aapt2Exe = aapt2Exe;
        ctx.outApk = outApk;
        ctx.targetSdk = targetSdk;
        ctx.manifestMerge = manifestMerge;

        if (hasJar) {
            JarDex.run(inputFiles, out, androidJar, libs, minApi, selfJar);
        } else if (hasDex) {
            if (makeJar) DexJar.run(inputFiles, out);
            else         DexSmali.run(inputFiles, out, selfJar);
        } else if (mixedMode) {
            MixedPipeline.run(inputFiles, out, ctx);
        } else if (kotlinOnly) {
            KotlinPipeline.run(inputFiles, out, ctx);
        } else {
            JavaPipeline.run(inputFiles, out, ctx);
        }
    }

    /** Default location of dexres.zip: next to the APK, otherwise inside the output directory. */
    private static Path defaultDexResPath(Path outApk, Path out) {
        if (outApk != null && outApk.getParent() != null) {
            return outApk.getParent().resolve("dexres.zip").toAbsolutePath();
        }
        return out.toAbsolutePath().resolve("dexres.zip");
    }

    /**
     * Resolve the Compose compiler plugin. Kotlin 1.9- uses
     * {@code androidx.compose.compiler:compiler}, Kotlin 2.x ships it as
     * {@code org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable}.
     */
    private static Path resolveComposePlugin(DependencyResolver resolver, String version)
            throws Exception {
        try {
            return resolver.resolveSingle("androidx.compose.compiler:compiler:" + version);
        } catch (Exception first) {
            try {
                return resolver.resolveSingle(
                        "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:" + version);
            } catch (Exception second) {
                throw new Exception("tried androidx.compose.compiler:compiler and "
                        + "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable. "
                        + first.getMessage());
            }
        }
    }

    static void usage(int exitCode) {
        System.err.println("Usage: java -jar J2S.jar [options] <src.java... | src.kt... | src.dex... | src.jar...>");
        System.err.println();
        System.err.println("Modes (detected by input file extension):");
        System.err.println("  Java mode (.java)        Compile .java -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("  Kotlin mode (.kt)        Compile .kt -> .class -> .dex -> .smali   (K2S)");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("    --kotlin-home <p>      Override kotlinc location (default: PATH)");
        System.err.println("    --compose-plugin <jar> Compose compiler plugin jar (-Xplugin)");
        System.err.println("  Mixed mode (.java+.kt)   Compile together -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("  Dex mode (.dex)          Disassemble .dex -> .smali");
        System.err.println("    --jar                  Convert .dex -> .jar (dex2jar)");
        System.err.println("  Jar mode (.jar)          Compress .jar -> .dex.jar (D8)");
        System.err.println();
        System.err.println("Maven dependencies (source modes only):");
        System.err.println("  -D, --dep <g:a[:v][:classifier][@jar|aar|pom]>");
        System.err.println("                           Maven coordinate (repeatable). A BOM (packaging=pom)");
        System.err.println("                           imports dependencyManagement; later -D may omit <v>.");
        System.err.println("  --repo <url>             Maven repository URL (repeatable). NO repository is");
        System.err.println("                           configured by default; set --repo or " + RepoConfig.ENV_REPOS + ".");
        System.err.println("  --local-repo <dir>       Maven local repository (default: ~/.m2/repository)");
        System.err.println("  --settings <file>        Maven settings.xml (mirrors/proxies/servers)");
        System.err.println("  --offline                Resolve from the local repository only");
        System.err.println("  --no-transitive          Do not resolve transitive dependencies");
        System.err.println("  --exclude <g:a>          Exclude group:artifact from transitive resolution");
        System.err.println("  --compose-plugin-version <v>  Resolve the Compose compiler plugin from Maven");
        System.err.println("  --dexres <file>          Where to write AAR resources as a zip");
        System.err.println("                           (default: next to --apk, else <out>/dexres.zip)");
        System.err.println("  --assets <dir>           User assets dir for APK builds (merged on top of AAR assets)");
        System.err.println("  --dep-lock <file>        Write/verify a dependency lock file (SHA-256 of artifacts)");
        System.err.println("  --update-dep-lock        Update an existing lock file instead of verifying it");
        System.err.println();
        System.err.println("Environment variables:");
        System.err.println("  " + RepoConfig.ENV_REPOS + "            Comma separated Maven repository URLs");
        System.err.println("  " + RepoConfig.ENV_LOCAL_REPO + "        Maven local repository directory (" + RepoConfig.ENV_M2 + " works too)");
        System.err.println("  " + RepoConfig.ENV_OFFLINE + "            1/true = offline mode");
        System.err.println("  " + RepoConfig.ENV_SETTINGS + "   settings.xml path");
        System.err.println("  " + RepoConfig.ENV_PROXY + "              HTTP proxy: [http://][user:pass@]host:port");
        System.err.println("  " + RepoConfig.ENV_NO_PROXY + "           Comma separated hosts that bypass the proxy");
        System.err.println();
        System.err.println("APK build (requires --manifest and --apk):");
        System.err.println("  --manifest <file>    AndroidManifest.xml");
        System.err.println("  --res <dir>          res/ directory (user resources, highest priority)");
        System.err.println("  --apk <file>         Output APK");
        System.err.println("  --target-sdk <N>     Target SDK (default: 34)");
        System.err.println("  --aapt2 <path>       Explicit aapt2 path (default: PATH / SDK)");
        System.err.println("  --no-manifest-merge  Do not merge AAR AndroidManifest.xml files");
        System.err.println();
        System.err.println("Dex cache (dependencies are dexed once, then reused):");
        System.err.println("  --dex-cache <path>   Cache dir (default: $J2S_CACHE/dex,");
        System.err.println("                       $XDG_CACHE_HOME/j2s/dex, ~/.cache/j2s/dex)");
        System.err.println("  --no-dex-cache       Disable the dex cache");
        System.err.println("  --clear-dex-cache    Clear the cache and exit (works without input files)");
        System.err.println();
        System.err.println("Other options:");
        System.err.println("  -o <path>            Output path");
        System.err.println("  -a, --android-jar    Android framework jar");
        System.err.println("  -l, --lib <jar>      Additional library jar (repeatable, not merged)");
        System.err.println("  --min-api <N>        Minimum API level for D8 (default: 21)");
        System.err.println("  -v, --verbose        Verbose output (resolved artifacts, config)");
        System.err.println("  -h, --help           Show this help");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar J2S.jar Hello.java");
        System.err.println("  java -jar J2S.jar Hello.kt");
        System.err.println("  java -jar J2S.jar Main.kt Util.java");
        System.err.println("  export " + RepoConfig.ENV_REPOS + "=https://repo.maven.apache.org/maven2");
        System.err.println("  java -jar J2S.jar -D junit:junit:4.13.2 --keep-dex Hello.java");
        System.err.println("  java -jar J2S.jar -D androidx.compose:compose-bom:2024.02.00 \\");
        System.err.println("      -D androidx.compose.ui:ui --keep-dex Main.kt");
        System.err.println("  java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \\");
        System.err.println("      --res res/ -D androidx.appcompat:appcompat:1.6.1 Main.kt");
        System.exit(exitCode);
    }
}
