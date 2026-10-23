package com.j2s;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Kotlin compilation pipeline (K2S).
 */
public final class KotlinPipeline {

    private KotlinPipeline() {}

    /** JVM tuning flags: reduce kotlinc startup latency and override its small default heap. */
    static final String[] JVM_OPTS = {
            "-J-Xms512m",
            "-J-Xmx2g",
            "-J-XX:ReservedCodeCacheSize=512m",
            "-J-XX:+UseParallelGC",
    };

    /** Kotlin daemon flags: keep a background JVM alive to avoid restarting it on every compile. */
    static final String[] DAEMON_OPTS = {
            "-J-Dkotlin.daemon.enabled=true",
            "-J-Dkotlin.daemon.autoshutdownIdleSeconds=7200",
            "-J-Dkotlin.daemon.shutdownDelayMilliseconds=1000",
            "-J-Dkotlin.daemon.jvmargs=-Xmx1500m",
    };

    public static void run(List<Path> ktFiles, Path out, BuildContext ctx) throws Exception {
        Path work = Files.createTempDirectory("j2s-kt-");
        try {
            Path classesDir = work.resolve("classes");
            Files.createDirectories(classesDir);

            Path kotlinc = resolveKotlinc(ctx.kotlinHome);
            if (kotlinc == null) {
                Utils.error("kotlinc not found. Install the Kotlin compiler "
                        + "and make sure it is on PATH, or pass --kotlin-home <dir>.");
            }

            // ── 0. aapt2 ──
            Path baseApk = null;
            List<Path> rClasses = Collections.emptyList();
            if (ctx.manifest != null && ctx.aapt2Exe != null) {
                System.out.println("=== aapt2: resources + manifest ===");
                Aapt2Builder.Result r = Aapt2Builder.process(
                        ctx.androidJar, ctx.manifest, ctx.resDir,
                        ctx.resources.resDirs(), ctx.resources.aarManifests(),
                        ctx.resources.assetsDirs(), ctx.assetsDir,
                        work, ctx.aapt2Exe, ctx.minApi, ctx.targetSdk, ctx.manifestMerge);
                baseApk = r.baseApk;
                rClasses = Aapt2Builder.compileRJava(r.genDir, ctx.androidJar, work);
            }

            // ── 1. kotlinc ──
            List<String> ktCmd = new ArrayList<>();
            ktCmd.add(kotlinc.toString());

            // JVM tuning + Kotlin daemon (-J flags are forwarded to the JVM by the kotlinc script).
            ktCmd.addAll(Arrays.asList(JVM_OPTS));
            ktCmd.addAll(Arrays.asList(DAEMON_OPTS));

            ktCmd.add("-d");
            ktCmd.add(classesDir.toString());
            ktCmd.add("-jvm-target");
            ktCmd.add("1.8");

            if (ctx.composePlugin != null) {
                ktCmd.add("-Xplugin=" + ctx.composePlugin);
                System.out.println("Compose plugin: " + ctx.composePlugin);
            }

            List<String> cp = new ArrayList<>();
            if (ctx.androidJar != null) cp.add(ctx.androidJar.toString());
            for (Path lib : ctx.libs) cp.add(lib.toString());
            for (Path dep : ctx.deps) cp.add(dep.toString());
            if (!cp.isEmpty()) {
                ktCmd.add("-classpath");
                ktCmd.add(String.join(java.io.File.pathSeparator, cp));
            }

            for (Path kt : ktFiles) ktCmd.add(kt.toString());

            System.out.println("kotlinc " + ktFiles.size() + " file(s) ...");
            Utils.run(ktCmd.toArray(new String[0]));

            // ── 2. Collect .class files + R.class ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                        .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) Utils.error("kotlinc produced no .class files");
            classFiles.addAll(rClasses);
            System.out.println("Compiled " + classFiles.size() + " classes");

            // ── 3. Merge stdlib + deps ──
            LinkedHashSet<Path> mergeSet = new LinkedHashSet<>();
            mergeSet.addAll(resolveKotlinStdlib(kotlinc));
            mergeSet.addAll(ctx.deps);
            List<Path> mergeJars = new ArrayList<>(mergeSet);

            if (mergeJars.isEmpty()) {
                System.out.println("Warning: no stdlib/deps to merge; "
                        + "runtime may fail with NoClassDefFoundError.");
            } else {
                System.out.println("Merging " + mergeJars.size() + " jar(s) into dex:");
                for (Path j : mergeJars) System.out.println("  " + j);
            }

            JavaPipeline.compileClassesToDex(classFiles, out, ctx, baseApk, mergeJars);

        } finally {
            deleteRecursive(work);
            System.out.println("Cleaned up temp files");
        }
    }

    static Path resolveKotlinc(String kotlinHome) {
        if (kotlinHome != null) {
            Path p = Paths.get(kotlinHome, "bin", "kotlinc");
            if (Files.isExecutable(p)) return p;
            Path pBat = Paths.get(kotlinHome, "bin", "kotlinc.bat");
            if (Files.exists(pBat)) return pBat;
            return null;
        }
        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(java.io.File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                Path p = Paths.get(dir, "kotlinc");
                if (Files.isExecutable(p)) return p;
                Path pBat = Paths.get(dir, "kotlinc.bat");
                if (Files.exists(pBat)) return pBat;
            }
        }
        return null;
    }

    static List<Path> resolveKotlinStdlib(Path kotlinc) {
        if (kotlinc == null) return Collections.emptyList();
        List<Path> homes = new ArrayList<>();
        Path real = kotlinc;
        try { real = kotlinc.toRealPath(); } catch (IOException ignored) {}
        Path bin = real.getParent();
        if (bin != null) {
            Path home = bin.getParent();
            if (home != null) homes.add(home);
        }
        String env = System.getenv("KOTLIN_HOME");
        if (env != null && !env.isEmpty()) homes.add(Paths.get(env));
        Path rawBin = kotlinc.getParent();
        if (rawBin != null) {
            Path rawHome = rawBin.getParent();
            if (rawHome != null) homes.add(rawHome);
        }
        for (Path home : homes) {
            Path lib = home.resolve("lib");
            if (!Files.isDirectory(lib)) continue;
            List<Path> found = new ArrayList<>();
            for (String name : new String[] {
                    "kotlin-stdlib.jar",
                    "kotlin-stdlib-jdk7.jar",
                    "kotlin-stdlib-jdk8.jar" }) {
                Path p = lib.resolve(name);
                if (Files.isRegularFile(p)) found.add(p);
            }
            if (!found.isEmpty()) return found;
        }
        return Collections.emptyList();
    }

    static void deleteRecursive(Path p) {
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                    .sorted(Comparator.reverseOrder())
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
