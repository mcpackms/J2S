package com.j2s;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Mixed Java + Kotlin pipeline:
 *
 *   .java + .kt
 *        │
 *        ├─(kotlinc, sees both)──► .class (only for .kt)
 *        │
 *        ├─(javac, with kotlinc output on cp)──► .class (for .java)
 *        │
 *        └─(d8 + baksmali via JavaPipeline)──► .smali / .dex
 *
 * kotlinc is given the .java files too so Kotlin code can reference Java
 * symbols (and vice-versa) in a single pass. javac then compiles the .java
 * files that kotlinc did not emit classes for.
 */
public final class MixedPipeline {

    private MixedPipeline() {}

    public static void run(List<Path> inputs,
                           Path out,
                           Path androidJar,
                           List<Path> libs,
                           int minApi,
                           boolean keepDex,
                           String selfJar,
                           String kotlinHome) throws Exception {

        Path work = Files.createTempDirectory("j2s-mix-");
        try {
            Path classesDir = work.resolve("classes");
            Files.createDirectories(classesDir);

            List<Path> javaFiles = inputs.stream()
                    .filter(p -> p.toString().toLowerCase().endsWith(".java"))
                    .collect(Collectors.toList());
            List<Path> ktFiles = inputs.stream()
                    .filter(p -> {
                        String n = p.toString().toLowerCase();
                        return n.endsWith(".kt") || n.endsWith(".kts");
                    })
                    .collect(Collectors.toList());

            // ── 1. kotlinc sees both .kt and .java ──
            Path kotlinc = KotlinPipeline.resolveKotlinc(kotlinHome);
            if (kotlinc == null) {
                Utils.error("kotlinc not found. Install the Kotlin compiler "
                          + "and make sure it is on PATH, or pass --kotlin-home <dir>.");
            }

            List<String> ktCmd = new ArrayList<>();
            ktCmd.add(kotlinc.toString());
            ktCmd.add("-d");
            ktCmd.add(classesDir.toString());
            ktCmd.add("-jvm-target");
            ktCmd.add("1.8");

            List<String> cp = new ArrayList<>();
            if (androidJar != null) cp.add(androidJar.toString());
            for (Path lib : libs)   cp.add(lib.toString());
            if (!cp.isEmpty()) {
                ktCmd.add("-classpath");
                ktCmd.add(String.join(File.pathSeparator, cp));
            }

            for (Path kt : ktFiles)   ktCmd.add(kt.toString());
            for (Path jf : javaFiles) ktCmd.add(jf.toString());

            System.out.println("kotlinc " + ktFiles.size() + " .kt + "
                             + javaFiles.size() + " .java (symbol resolution) ...");
            Utils.run(ktCmd.toArray(new String[0]));

            // ── 2. javac compiles the .java sources, with kotlinc output on cp ──
            if (!javaFiles.isEmpty()) {
                List<String> javacArgs = new ArrayList<>();
                javacArgs.add("javac");
                javacArgs.add("-d");
                javacArgs.add(classesDir.toString());
                javacArgs.add("-source");
                javacArgs.add("1.8");
                javacArgs.add("-target");
                javacArgs.add("1.8");
                javacArgs.add("-Xlint:-options");

                List<String> javacCp = new ArrayList<>();
                javacCp.add(classesDir.toString());   // kotlinc output
                if (androidJar != null) javacCp.add(androidJar.toString());
                for (Path lib : libs)   javacCp.add(lib.toString());
                javacArgs.add("-cp");
                javacArgs.add(String.join(File.pathSeparator, javacCp));

                javaFiles.forEach(f -> javacArgs.add(f.toString()));
                System.out.println("javac " + javaFiles.size() + " .java ...");
                Utils.run(javacArgs.toArray(new String[0]));
            }

            // ── 3. Collect every .class and run d8 + baksmali ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                              .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) Utils.error("No .class files produced");
            System.out.println("Compiled " + classFiles.size() + " classes total");

            JavaPipeline.compileClassesToDex(classFiles, out, androidJar, libs, minApi, keepDex, selfJar);

        } finally {
            KotlinPipeline.deleteRecursive(work);
            System.out.println("Cleaned up temp files");
        }
    }
}