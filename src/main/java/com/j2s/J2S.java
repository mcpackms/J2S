package com.j2s;

import java.nio.file.*;
import java.util.*;

/**
 * J2S — Convert between Java source, DEX, and JAR formats.
 *
 * <p>Three input modes, auto-detected by file extension:
 * <ul>
 *   <li>{@code .java} → Java compilation pipeline
 *   <li>{@code .dex} → Dex disassembly or dex2jar
 *   <li>{@code .jar} → D8 compression to dex jar
 * </ul>
 */
public class J2S {

    public static void main(String[] args) throws Exception {
        // ── Locate the JAR (child processes need it for bundled deps) ──
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
                default:
                    Path p = Paths.get(args[i]).toAbsolutePath();
                    if (!Files.exists(p)) Utils.error(p + " does not exist");
                    String name = p.toString().toLowerCase();
                    if (name.endsWith(".java") || name.endsWith(".dex") || name.endsWith(".jar")) {
                        inputFiles.add(p);
                    } else {
                        Utils.error("Unrecognized file or option: " + args[i]);
                    }
            }
            i++;
        }

        if (inputFiles.isEmpty())
            Utils.error("At least one .java, .dex, or .jar file is required");

        // ── Detect mode from input file extensions ──
        boolean javaMode = inputFiles.stream().allMatch(f -> f.toString().toLowerCase().endsWith(".java"));
        boolean dexMode  = inputFiles.stream().allMatch(f -> f.toString().toLowerCase().endsWith(".dex"));
        boolean jarMode  = inputFiles.stream().allMatch(f -> f.toString().toLowerCase().endsWith(".jar"));
        if (!javaMode && !dexMode && !jarMode)
            Utils.error("Mixed file types are not allowed");

        // ── Validate flag vs mode consistency ──
        if (keepDex && !javaMode) Utils.error("--keep-dex only applies to .java input");
        if (makeJar && !dexMode)  Utils.error("--jar only applies to .dex input");
        if (keepDex && makeJar)   Utils.error("--keep-dex and --jar are mutually exclusive");

        // ── Infer default output path when -o is omitted ──
        if (jarMode && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.jar$", ".dex.jar"));
        } else if (keepDex && javaMode && !outSpecified) {
            out = Paths.get("dex_out");
        } else if (makeJar && dexMode && !outSpecified) {
            String name = inputFiles.get(0).getFileName().toString();
            out = inputFiles.get(0).resolveSibling(name.replaceAll("\\.dex$", ".jar"));
        }

        // ── Validate input files exist ──
        if (androidJar != null && !Files.exists(androidJar))
            Utils.error(androidJar + " does not exist");
        for (Path lib : libs)
            if (!Files.exists(lib)) Utils.error(lib + " does not exist");

        // ── Dispatch to the appropriate converter ──
        if (jarMode) {
            JarDex.run(inputFiles, out, androidJar, libs, minApi, selfJar);
        } else if (dexMode) {
            if (makeJar) {
                DexJar.run(inputFiles, out);
            } else {
                DexSmali.run(inputFiles, out, selfJar);
            }
        } else {
            JavaPipeline.run(inputFiles, out, androidJar, libs, minApi, keepDex, selfJar);
        }
    }

    static void usage() {
        System.err.println("Usage: java -jar J2S.jar [options] <source.java... | source.dex... | source.jar...>");
        System.err.println();
        System.err.println("Modes (detected by input file extension):");
        System.err.println("  Java mode (.java)    Compile .java -> .class -> .dex -> .smali");
        System.err.println("    --keep-dex         Skip smali, output .dex only");
        System.err.println("  Dex mode (.dex)      Disassemble .dex -> .smali");
        System.err.println("    --jar              Convert .dex -> .jar (dex2jar)");
        System.err.println("  Jar mode (.jar)      Compress .jar -> .dex.jar (D8)");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -o <path>            Output path (default depends on mode)");
        System.err.println("  -a, --android-jar    Android framework jar");
        System.err.println("  -l, --lib <jar>      Additional library jar (repeatable)");
        System.err.println("  --min-api <N>        Minimum API level for D8 (default: 21)");
        System.err.println("  --keep-dex           Java mode: output .dex only");
        System.err.println("  --jar                Dex mode: output .jar instead of .smali");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar J2S.jar Hello.java");
        System.err.println("  java -jar J2S.jar --keep-dex Hello.java");
        System.err.println("  java -jar J2S.jar -a android.jar -o out *.java");
        System.err.println("  java -jar J2S.jar classes.dex              # dex -> smali");
        System.err.println("  java -jar J2S.jar --jar classes.dex        # dex -> jar");
        System.err.println("  java -jar J2S.jar app.jar                  # jar -> dex");
        System.err.println("  java -jar J2S.jar -a android.jar --min-api 24 app.jar");
        System.exit(1);
    }
}
