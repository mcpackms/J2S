package com.j2s;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Java compilation pipeline: javac -&gt; d8 -&gt; (multidex) -&gt; smali / APK.
 */
public class JavaPipeline {

    /** JVM tuning for javac. Passed via -J (javac-only flag). */
    static final String[] JAVAC_JVM_OPTS = {
            "-J-Xms256m",
            "-J-Xmx1g",
            "-J-XX:+UseParallelGC",
    };

    /** JVM tuning for the R8/D8 launcher. D8 benefits noticeably from a larger heap. */
    static final String[] D8_JVM_OPTS = {
            "-Xms512m",
            "-Xmx2g",
            "-XX:+UseParallelGC",
    };

    /** JVM tuning for the baksmali launcher. */
    static final String[] BAKSMALI_JVM_OPTS = {
            "-Xms256m",
            "-Xmx1g",
            "-XX:+UseParallelGC",
    };

    public static void run(List<Path> inputFiles, Path out, BuildContext ctx) throws Exception {
        Path tmp = Files.createTempDirectory("j2s_");
        System.out.println("Temp dir: " + tmp);

        try {
            List<String> javacArgs = new ArrayList<>();
            javacArgs.add("javac");

            // JVM tuning: reduce startup latency and avoid tiny default heap.
            javacArgs.addAll(Arrays.asList(JAVAC_JVM_OPTS));

            javacArgs.add("-d");
            javacArgs.add(tmp.toString());

            List<String> cp = new ArrayList<>();
            if (ctx.androidJar != null) cp.add(ctx.androidJar.toString());
            for (Path lib : ctx.libs) cp.add(lib.toString());
            for (Path dep : ctx.deps) cp.add(dep.toString());
            if (!cp.isEmpty()) {
                javacArgs.add("-cp");
                javacArgs.add(String.join(File.pathSeparator, cp));
            }

            javacArgs.add("-source");
            javacArgs.add("1.8");
            javacArgs.add("-target");
            javacArgs.add("1.8");
            javacArgs.add("-Xlint:-options");
            inputFiles.forEach(f -> javacArgs.add(f.toString()));
            Utils.run(javacArgs.toArray(new String[0]));

            List<Path> classes = Files.walk(tmp)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            if (classes.isEmpty()) Utils.error("No .class files generated");
            System.out.println("Compiled " + classes.size() + " classes");

            Path baseApk = null;
            if (ctx.manifest != null && ctx.aapt2Exe != null) {
                System.out.println("=== aapt2: resources + manifest ===");
                Aapt2Builder.Result r = Aapt2Builder.process(
                        ctx.androidJar, ctx.manifest, ctx.resDir,
                        ctx.resources.resDirs(), ctx.resources.aarManifests(),
                        ctx.resources.assetsDirs(), ctx.assetsDir,
                        tmp, ctx.aapt2Exe, ctx.minApi, ctx.targetSdk, ctx.manifestMerge);
                baseApk = r.baseApk;
                classes.addAll(Aapt2Builder.compileRJava(r.genDir, ctx.androidJar, tmp));
            }

            compileClassesToDex(classes, out, ctx, baseApk, ctx.deps);

        } finally {
            Utils.delete(tmp);
            System.out.println("Cleaned up temp files");
        }
    }

    /**
     * Full backend shared by the Java, Kotlin and mixed pipelines.
     *
     * @param mergeJars jars fed into d8 as program input (resolved deps); they are dexed through
     *                  {@link DexCache} and merged into the output dex
     * @param baseApk   base.apk produced by aapt2 link (resources.arsc + manifest), or null
     */
    static void compileClassesToDex(List<Path> classes, Path out, BuildContext ctx,
                                    Path baseApk, List<Path> mergeJars) throws Exception {
        if (classes.isEmpty()) Utils.error("No .class files to convert");

        // Cacheable inputs: stdlib + resolved deps (content is stable across builds).
        List<Path> cacheable = mergeJars.stream()
                .filter(p -> p != null && Files.isRegularFile(p))
                .collect(Collectors.toList());

        Path dexWork = Files.createTempDirectory("j2s_dex_");
        try {
            Path dexDir = dexWork.resolve("dex");
            Files.createDirectory(dexDir);

            // ── 1. deps: go through DexCache, skip D8 entirely on hit ──
            List<Path> cacheableDex = Collections.emptyList();
            if (!cacheable.isEmpty()) {
                Path cacheWork = dexWork.resolve("cache");
                Files.createDirectory(cacheWork);
                cacheableDex = DexCache.resolve(cacheable, ctx.minApi, ctx.androidJar, ctx.libs,
                        cacheWork,
                        o -> runD8(cacheable, o, ctx.androidJar, ctx.libs, ctx.minApi, ctx.selfJar));
            }

            // ── 2. Business classes + (cached) deps dex ──
            List<Path> mainInputs = new ArrayList<>(classes);
            mainInputs.addAll(cacheableDex);
            runD8(mainInputs, dexDir, ctx.androidJar, ctx.libs, ctx.minApi, ctx.selfJar);

            // Collect all dex files (multidex).
            List<Path> dexFiles;
            try (Stream<Path> s = Files.list(dexDir)) {
                dexFiles = s.filter(p -> p.getFileName().toString().matches("classes\\d*\\.dex"))
                        .sorted()
                        .collect(Collectors.toList());
            }
            if (dexFiles.isEmpty()) Utils.error("d8 did not produce any dex");
            System.out.println("D8 produced " + dexFiles.size() + " dex file(s)");

            if (baseApk != null && ctx.outApk != null) {
                // APK mode: pack dex files (and AAR JNI libs) into the base.apk from aapt2.
                Aapt2Builder.packApk(baseApk, dexFiles, ctx.outApk, ctx.resources.jniDirs());
                System.out.println("APK output: " + ctx.outApk);
            } else if (ctx.keepDex) {
                Files.createDirectories(out);
                for (Path d : dexFiles) {
                    Path target = out.resolve(d.getFileName().toString());
                    Files.copy(d, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Dex output: " + target);
                }
            } else {
                Files.createDirectories(out);
                List<String> args = new ArrayList<>();
                args.add("java");

                // JVM tuning for the baksmali launcher.
                args.addAll(Arrays.asList(BAKSMALI_JVM_OPTS));

                args.add("-cp");
                args.add(ctx.selfJar);
                args.add("com.android.tools.smali.baksmali.Main");
                args.add("d");
                for (Path d : dexFiles) args.add(d.toString());
                args.add("-o");
                args.add(out.toString());
                Utils.run(args.toArray(new String[0]));

                System.out.println("Smali output: " + out);
                final Path outputDir = out;
                Files.walk(out)
                        .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".smali"))
                        .forEach(f -> System.out.println("  " + outputDir.relativize(f)));
            }
        } finally {
            Utils.delete(dexWork);
        }
    }

    /**
     * Run D8 to dex the inputs into outDir; returns the generated classes*.dex files
     * (multiple when multidex).
     *
     * @param selfJar J2S's own jar (R8 loaded as a child process)
     */
    static List<Path> runD8(List<Path> inputs, Path outDir, Path androidJar,
                            List<Path> libs, int minApi, String selfJar)
            throws Exception {
        List<String> d8cmd = new ArrayList<>();
        d8cmd.add("java");

        // JVM tuning for the D8 launcher.
        d8cmd.addAll(Arrays.asList(D8_JVM_OPTS));

        d8cmd.add("-cp");
        d8cmd.add(selfJar);
        d8cmd.add("com.android.tools.r8.D8");
        if (androidJar != null) {
            d8cmd.add("--lib");
            d8cmd.add(androidJar.toString());
        }
        if (libs != null) {
            for (Path lib : libs) {
                if (lib == null) continue;
                d8cmd.add("--lib");
                d8cmd.add(lib.toString());
            }
        }
        d8cmd.add("--min-api");
        d8cmd.add(String.valueOf(minApi));
        d8cmd.add("--output");
        d8cmd.add(outDir.toString());
        for (Path in : inputs) d8cmd.add(in.toString());

        Utils.run(d8cmd.toArray(new String[0]));

        try (Stream<Path> s = Files.list(outDir)) {
            return s.filter(p -> p.getFileName().toString().matches("classes\\d*\\.dex"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
