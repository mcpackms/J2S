package com.j2s;

import java.nio.file.*;
import java.util.*;

/**
 * J2S — Convert between Java/Kotlin source, DEX, and JAR formats.
 *
 * <p>Input modes, auto-detected by file extension:
 * <ul>
 *   <li>{@code .java}          → Java compilation pipeline
 *   <li>{@code .kt} / {@code .kts} → Kotlin compilation pipeline (K2S)
 *   <li>{@code .java} + {@code .kt} → Mixed Java + Kotlin pipeline
 *   <li>{@code .dex}           → Dex disassembly or dex2jar
 *   <li>{@code .jar}           → D8 compression to dex jar
 * </ul>
 */
public class J2S {

    public static void main(String[] args) throws Exception {
        String selfJar = Utils.getJarPath();
        if (selfJar == null) {
            Utils.error("Cannot determine J2S.jar path. Run with java -jar J2S.jar");
        }

        if (args.length == 0) {
            usage();
        }

        // ── Parse CLI arguments ──
        Path out = Paths.get("smali_out");
        Path androidJar = null;
        List<Path> libs = new ArrayList<>();
        List<Path> inputFiles = new ArrayList<>();
        boolean keepDex = false;
        boolean makeJar = false;
        int minApi = 21;
        boolean outSpecified = false;
        String kotlinHome = null;

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
                case "--dex": case "--keep-dex":
                    keepDex = true;
                    break;
                case "--jar":
                    makeJar = true;
                    break;
                case "--min-api":
                    if (++i < args.length) {
                        try {
                            minApi = Integer.parseInt(args[i]);
                        } catch (NumberFormatException e) {
                            Utils.error("--min-api must be a number");
                        }
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

        if (inputFiles.isEmpty())
            Utils.error("At least one .java, .kt, .dex, or .jar file is required");

        // ── Detect input kind from file extensions ──
        boolean hasJava   = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".java"));
        boolean hasKotlin = inputFiles.stream().anyMatch(f -> {
            String n = f.toString().toLowerCase();
            return n.endsWith(".kt") || n.endsWith(".kts");
        });
        boolean hasDex    = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".dex"));
        boolean hasJar    = inputFiles.stream()
                .anyMatch(f -> f.toString().toLowerCase().endsWith(".jar"));

        int kindCount = ((hasJava || hasKotlin) ? 1 : 0)
                      + (hasDex ? 1 : 0)
                      + (hasJar ? 1 : 0);
        if (kindCount > 1)
            Utils.error("Cannot mix source (.java/.kt), .dex, and .jar inputs");

        boolean sourceMode = hasJava || hasKotlin;
        boolean javaOnly   = hasJava && !hasKotlin;
        boolean kotlinOnly = hasKotlin && !hasJava;
        boolean mixedMode  = hasJava && hasKotlin;

        // ── Validate flag vs mode consistency ──
        if (keepDex && !sourceMode)
            Utils.error("--keep-dex only applies to .java / .kt input");
        if (makeJar && !hasDex)
            Utils.error("--jar only applies to .dex input");
        if (keepDex && makeJar)
            Utils.error("--keep-dex and --jar are mutually exclusive");

        // ── Infer default output path when -o is omitted ──
        if (hasJar && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.jar$", ".dex.jar"));
        } else if (keepDex && sourceMode && !outSpecified) {
            out = Paths.get("dex_out");
        } else if (makeJar && hasDex && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.dex$", ".jar"));
        }

        // ── Validate input files exist ──
        if (androidJar != null && !Files.exists(androidJar))
            Utils.error(androidJar + " does not exist");
        for (Path lib : libs)
            if (!Files.exists(lib)) Utils.error(lib + " does not exist");

        // ── Dispatch ──
        if (hasJar) {
            JarDex.run(inputFiles, out, androidJar, libs, minApi, selfJar);
        } else if (hasDex) {
            if (makeJar) DexJar.run(inputFiles, out);
            else         DexSmali.run(inputFiles, out, selfJar);
        } else if (mixedMode) {
            MixedPipeline.run(inputFiles, out, androidJar, libs, minApi, keepDex, selfJar, kotlinHome);
        } else if (kotlinOnly) {
            KotlinPipeline.run(inputFiles, out, androidJar, libs, minApi, keepDex, selfJar, kotlinHome);
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
        System.err.println("  Mixed mode (.java+.kt)   Compile together -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex             Skip smali, output .dex only");
        System.err.println("  Dex mode (.dex)          Disassemble .dex -> .smali");
        System.err.println("    --jar                  Convert .dex -> .jar (dex2jar)");
        System.err.println("  Jar mode (.jar)          Compress .jar -> .dex.jar (D8)");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -o <path>            Output path (default depends on mode)");
        System.err.println("  -a, --android-jar    Android framework jar");
        System.err.println("  -l, --lib <jar>      Additional library jar (repeatable)");
        System.err.println("  --min-api <N>        Minimum API level for D8 (default: 21)");
        System.err.println("  --keep-dex           Source mode: output .dex only");
        System.err.println("  --jar                Dex mode: output .jar instead of .smali");
        System.err.println("  --kotlin-home <path> Kotlin/mixed mode: path to kotlinc home (default: PATH)");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar J2S.jar Hello.java");
        System.err.println("  java -jar J2S.jar --keep-dex Hello.java");
        System.err.println("  java -jar J2S.jar Hello.kt                          # kotlinc from PATH");
        System.err.println("  java -jar J2S.jar --keep-dex Hello.kt");
        System.err.println("  java -jar J2S.jar Main.kt Util.java                 # mixed Java + Kotlin");
        System.err.println("  java -jar J2S.jar -a android.jar --kotlin-home /opt/kotlinc *.kt");
        System.err.println("  java -jar J2S.jar classes.dex              # dex -> smali");
        System.err.println("  java -jar J2S.jar --jar classes.dex        # dex -> jar");
        System.err.println("  java -jar J2S.jar app.jar                  # jar -> dex");
        System.err.println("  java -jar J2S.jar -a android.jar --min-api 24 app.jar");
        System.exit(1);
    }
}