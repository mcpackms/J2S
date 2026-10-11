package com.j2s;

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
 *   <li>{@code .jar}                 → D8 compression to dex jar</li>
 * </ul>
 *
 * <p>Source modes support:
 * <ul>
 *   <li>{@code -D/--dep} Maven dependency resolution (AAR + transitive)</li>
 *   <li>{@code --manifest} + {@code --res} + {@code --apk} full APK build with aapt2</li>
 *   <li>{@code --compose-plugin-version} Compose compiler plugin auto-resolve</li>
 *   <li>Multidex output (classes.dex, classes2.dex, ...)</li>
 *   <li>AAR {@code res/} dirs auto-merged into aapt2</li>
 * </ul>
 */
public class J2S {

    public static void main(String[] args) throws Exception {
        String selfJar = Utils.getJarPath();
        if (selfJar == null) {
            Utils.error("Cannot determine J2S.jar path. Run with java -jar J2S.jar");
        }

        if (args.length == 0) usage();

        // 必须在 inputFiles.isEmpty() 检查之前，以便 --clear-dex-cache 可单独使用

        // ── Parsed options ──
        Path out = Paths.get("smali_out");
        Path androidJar = null;
        List<Path> libs = new ArrayList<>();
        List<Path> inputFiles = new ArrayList<>();
        List<String> depCoords = new ArrayList<>();
        List<String> extraRepos = new ArrayList<>();
        Path cacheDir = null;
        String composePlugin = null;
        String composePluginVersion = null;
        String kotlinHome = null;
        Path manifest = null;
        Path resDir = null;
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
                    else Utils.error("Missing coordinate after " + args[i-1]);
                    break;
                case "--repo":
                    if (++i < args.length) extraRepos.add(args[i]);
                    else Utils.error("Missing url after --repo");
                    break;
                case "--cache-dir":
                    if (++i < args.length) cacheDir = Paths.get(args[i]).toAbsolutePath();
                    else Utils.error("Missing dir after --cache-dir");
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
                case "--compose-plugin-version":
                    if (++i < args.length) composePluginVersion = args[i];
                    else Utils.error("Missing version after --compose-plugin-version");
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

        // ── Dex 缓存 ──
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
        if ((!depCoords.isEmpty() || composePluginVersion != null) && !sourceMode)
            Utils.error("-D / --compose-plugin-version only apply to .java / .kt input");
        if (outApk != null && manifest == null)
            Utils.error("--apk requires --manifest");
        if (manifest != null && outApk == null)
            Utils.error("--manifest requires --apk");
        if (manifest != null && !sourceMode)
            Utils.error("--manifest only applies to .java / .kt input");
        if (resDir != null && manifest == null)
            Utils.error("--res requires --manifest (and --apk)");

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
        List<Path> depJars = new ArrayList<>();
        List<Path> aarResDirs = Collections.emptyList();

        if (sourceMode && (!depCoords.isEmpty() || composePluginVersion != null)) {
            DependencyResolver resolver = new DependencyResolver(cacheDir, extraRepos);

            for (String coord : depCoords) {
                System.out.println("Resolving " + coord + " ...");
                try {
                    List<Path> jars = resolver.resolve(coord);
                    for (Path jar : jars) {
                        System.out.println("  " + jar);
                        depJars.add(jar);
                    }
                } catch (Exception e) {
                    Utils.error("Failed to resolve " + coord + ": " + e.getMessage());
                }
            }

            // Collect AAR res dirs (for aapt2)
            aarResDirs = resolver.getAarResDirs();
            if (!aarResDirs.isEmpty()) {
                System.out.println("Found " + aarResDirs.size() + " AAR res dir(s):");
                for (Path r : aarResDirs) System.out.println("  " + r);
            }

            if (composePluginVersion != null) {
                String coord = "androidx.compose.compiler:compiler:" + composePluginVersion;
                System.out.println("Resolving " + coord + " ...");
                try {
                    Path plugin = resolver.resolveSingle(coord);
                    composePlugin = plugin.toString();
                    System.out.println("  " + plugin);
                } catch (Exception e) {
                    Utils.error("Failed to resolve compose plugin: " + e.getMessage());
                }
            }
        }

        // ── Dispatch ──
        if (hasJar) {
            JarDex.run(inputFiles, out, androidJar, libs, minApi, selfJar);
        } else if (hasDex) {
            if (makeJar) DexJar.run(inputFiles, out);
            else         DexSmali.run(inputFiles, out, selfJar);
        } else if (mixedMode) {
            MixedPipeline.run(inputFiles, out, androidJar, libs, depJars, minApi,
                    keepDex, selfJar, kotlinHome, composePlugin,
                    manifest, resDir, aarResDirs, aapt2Exe, outApk, targetSdk);
        } else if (kotlinOnly) {
            KotlinPipeline.run(inputFiles, out, androidJar, libs, depJars, minApi,
                    keepDex, selfJar, kotlinHome, composePlugin,
                    manifest, resDir, aarResDirs, aapt2Exe, outApk, targetSdk);
        } else {
            JavaPipeline.run(inputFiles, out, androidJar, libs, minApi, keepDex, selfJar);
        }
    }

    static void usage() {
        System.err.println("Usage: java -jar J2S.jar [options] <src.java... | src.kt... | src.dex... | src.jar...>");
        System.err.println();
        System.err.println("Modes (detected by input file extension):");
        System.err.println("  Java mode (.java)        Compile .java -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("  Kotlin mode (.kt)        Compile .kt -> .class -> .dex -> .smali   (K2S)");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("    --kotlin-home <p>      Override kotlinc location (default: PATH)");
        System.err.println("    --compose-plugin <jar> Compose compiler plugin jar (-Xplugin)");
        System.err.println("    --compose-plugin-version <v>  Auto-resolve compose plugin from Maven");
        System.err.println("  Mixed mode (.java+.kt)   Compile together -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("  Dex mode (.dex)          Disassemble .dex -> .smali");
        System.err.println("    --jar                  Convert .dex -> .jar (dex2jar)");
        System.err.println("  Jar mode (.jar)          Compress .jar -> .dex.jar (D8)");
        System.err.println();
        System.err.println("APK build (requires --manifest and --apk):");
        System.err.println("  --manifest <file>    AndroidManifest.xml");
        System.err.println("  --res <dir>          res/ directory (user resources, highest priority)");
        System.err.println("  --apk <file>         Output APK");
        System.err.println("  --target-sdk <N>     Target SDK (default: 34)");
        System.err.println("  --aapt2 <path>       Explicit aapt2 path (default: PATH / SDK)");
        System.err.println();
        System.err.println("Maven dependencies:");
        System.err.println("  -D, --dep <g:a:v>    Maven coordinate, auto-downloaded & merged (repeatable)");
        System.err.println("                       AARs are automatically unpacked (classes.jar + res/)");
        System.err.println("  --repo <url>         Extra Maven repository URL (repeatable)");
        System.err.println("  --cache-dir <path>   Maven local repo (default: ~/.m2/repository)");
        System.err.println();
        System.err.println("Dex cache (stdlib / dependencies are dexed once, then reused):");
        System.err.println("  --dex-cache <path>   Cache dir (default: $J2S_CACHE/dex,");
        System.err.println("                       $XDG_CACHE_HOME/j2s/dex, ~/.cache/j2s/dex)");
        System.err.println("  --no-dex-cache       Disable the dex cache");
        System.err.println("  --clear-dex-cache    Clear the cache and exit (works without input files)");
        System.err.println();
        System.err.println("Other options:");
        System.err.println("  -o <path>            Output path");
        System.err.println("  -a, --android-jar    Android framework jar");
        System.err.println("  -l, --lib <jar>      Additional library jar (repeatable, --lib reference)");
        System.err.println("  --min-api <N>        Minimum API level for D8 (default: 21)");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar J2S.jar Hello.java");
        System.err.println("  java -jar J2S.jar Hello.kt");
        System.err.println("  java -jar J2S.jar Main.kt Util.java");
        System.err.println("  java -jar J2S.jar --keep-dex -D androidx.compose.ui:ui:1.6.0 \\");
        System.err.println("      --compose-plugin-version 1.5.12 Main.kt");
        System.err.println("  java -jar J2S.jar --apk app.apk --manifest AndroidManifest.xml \\");
        System.err.println("      --res res/ --keep-dex -a android.jar \\");
        System.err.println("      -D androidx.appcompat:appcompat:1.6.1 Main.kt");
        System.exit(1);
    }
}