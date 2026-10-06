package com.j2s;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Kotlin compilation pipeline (K2S):
 *   .kt / .kts  --(kotlinc)-->  .class  --(d8 + baksmali via JavaPipeline)-->  .smali
 *
 * kotlinc is located as follows:
 *   1. {@code --kotlin-home <dir>}  ->  {@code <dir>/bin/kotlinc[.bat]}
 *   2. Otherwise, look up {@code kotlinc} / {@code kotlinc.bat} on the system PATH.
 */
public final class KotlinPipeline {

    private KotlinPipeline() {}

    public static void run(List<Path> ktFiles,
                           Path out,
                           Path androidJar,
                           List<Path> libs,
                           int minApi,
                           boolean keepDex,
                           String selfJar,
                           String kotlinHome) throws Exception {

        Path work = Files.createTempDirectory("j2s-kt-");
        try {
            // ── 1. Compile .kt -> .class with kotlinc ──
            Path classesDir = work.resolve("classes");
            Files.createDirectories(classesDir);

            Path kotlinc = resolveKotlinc(kotlinHome);
            if (kotlinc == null) {
                Utils.error("kotlinc not found. Install the Kotlin compiler "
                          + "and make sure it is on PATH, or pass --kotlin-home <dir>.");
            }

            List<String> ktCmd = new ArrayList<>();
            ktCmd.add(kotlinc.toString());
            ktCmd.add("-d");
            ktCmd.add(classesDir.toString());
            ktCmd.add("-no-stdlib");   // users must supply kotlin-stdlib via -l
            ktCmd.add("-no-reflect");
            ktCmd.add("-jvm-target");
            ktCmd.add("1.8");

            // Classpath: android.jar + user libs (should include kotlin-stdlib.jar)
            List<String> cp = new ArrayList<>();
            if (androidJar != null) cp.add(androidJar.toString());
            for (Path lib : libs)   cp.add(lib.toString());
            if (!cp.isEmpty()) {
                ktCmd.add("-classpath");
                ktCmd.add(String.join(java.io.File.pathSeparator, cp));
            }

            for (Path kt : ktFiles) ktCmd.add(kt.toString());

            System.out.println("kotlinc " + ktFiles.size() + " file(s) ...");
            Utils.run(ktCmd.toArray(new String[0]));

            // ── 2. Hand off .class tree to the shared Java backend ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                              .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) {
                Utils.error("kotlinc produced no .class files");
            }
            System.out.println("Compiled " + classFiles.size() + " classes");

            JavaPipeline.compileClassesToDex(classFiles, out, androidJar, libs, minApi, keepDex, selfJar);

        } finally {
            deleteRecursive(work);
            System.out.println("Cleaned up temp files");
        }
    }

    /** Locate kotlinc: explicit --kotlin-home > PATH. */
    private static Path resolveKotlinc(String kotlinHome) {
        // 1. explicit override
        if (kotlinHome != null) {
            Path p = Paths.get(kotlinHome, "bin", "kotlinc");
            if (Files.isExecutable(p)) return p;
            Path pBat = Paths.get(kotlinHome, "bin", "kotlinc.bat");
            if (Files.exists(pBat)) return pBat;
            return null;
        }

        // 2. default: look up on PATH
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

    private static void deleteRecursive(Path p) {
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                 .sorted(Comparator.reverseOrder())
                 .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}