package com.j2s;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Kotlin compilation pipeline (K2S)。
 */
public final class KotlinPipeline {

    private KotlinPipeline() {}

    public static void run(List<Path> ktFiles,
                           Path out,
                           Path androidJar,
                           List<Path> libs,
                           List<Path> deps,
                           int minApi,
                           boolean keepDex,
                           String selfJar,
                           String kotlinHome,
                           String composePlugin,
                           Path manifest,
                           Path resDir,
                           List<Path> extraResDirs,
                           Path aapt2Exe,
                           Path outApk,
                           Integer targetSdk) throws Exception {

        Path work = Files.createTempDirectory("j2s-kt-");
        try {
            Path classesDir = work.resolve("classes");
            Files.createDirectories(classesDir);

            Path kotlinc = resolveKotlinc(kotlinHome);
            if (kotlinc == null) {
                Utils.error("kotlinc not found. Install the Kotlin compiler "
                        + "and make sure it is on PATH, or pass --kotlin-home <dir>.");
            }

            // ── 0. aapt2 ──
            Path baseApk = null;
            List<Path> rClasses = Collections.emptyList();
            if (manifest != null && aapt2Exe != null) {
                System.out.println("=== aapt2: resources + manifest ===");
                Aapt2Builder.Result r = Aapt2Builder.process(
                        androidJar, manifest, resDir, extraResDirs,
                        work, aapt2Exe, minApi, targetSdk);
                baseApk = r.baseApk;
                rClasses = Aapt2Builder.compileRJava(r.genDir, androidJar, work);
            }

            // ── 1. kotlinc ──
            List<String> ktCmd = new ArrayList<>();
            ktCmd.add(kotlinc.toString());
            ktCmd.add("-d");
            ktCmd.add(classesDir.toString());
            ktCmd.add("-jvm-target");
            ktCmd.add("1.8");

            if (composePlugin != null) {
                ktCmd.add("-Xplugin=" + composePlugin);
                System.out.println("Compose plugin: " + composePlugin);
            }

            List<String> cp = new ArrayList<>();
            if (androidJar != null) cp.add(androidJar.toString());
            for (Path lib : libs) cp.add(lib.toString());
            for (Path dep : deps) cp.add(dep.toString());
            if (!cp.isEmpty()) {
                ktCmd.add("-classpath");
                ktCmd.add(String.join(java.io.File.pathSeparator, cp));
            }

            for (Path kt : ktFiles) ktCmd.add(kt.toString());

            System.out.println("kotlinc " + ktFiles.size() + " file(s) ...");
            Utils.run(ktCmd.toArray(new String[0]));

            // ── 2. 收集 .class + R.class ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                        .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) Utils.error("kotlinc produced no .class files");
            classFiles.addAll(rClasses);
            System.out.println("Compiled " + classFiles.size() + " classes");

            // ── 3. 合并 stdlib + deps ──
            LinkedHashSet<Path> mergeSet = new LinkedHashSet<>();
            mergeSet.addAll(resolveKotlinStdlib(kotlinc));
            mergeSet.addAll(deps);
            List<Path> mergeJars = new ArrayList<>(mergeSet);

            if (mergeJars.isEmpty()) {
                System.out.println("Warning: no stdlib/deps to merge; "
                        + "runtime may fail with NoClassDefFoundError.");
            } else {
                System.out.println("Merging " + mergeJars.size() + " jar(s) into dex:");
                for (Path j : mergeJars) System.out.println("  " + j);
            }

            JavaPipeline.compileClassesToDex(
                    classFiles, out, androidJar, libs, minApi, keepDex, selfJar,
                    mergeJars, baseApk, outApk);

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