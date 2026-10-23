package com.j2s;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Mixed Java + Kotlin pipeline.
 */
public final class MixedPipeline {

    private MixedPipeline() {}

    public static void run(List<Path> inputs, Path out, BuildContext ctx) throws Exception {
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

            Path kotlinc = KotlinPipeline.resolveKotlinc(ctx.kotlinHome);
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
            ktCmd.addAll(Arrays.asList(KotlinPipeline.JVM_OPTS));
            ktCmd.addAll(Arrays.asList(KotlinPipeline.DAEMON_OPTS));

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
                ktCmd.add(String.join(File.pathSeparator, cp));
            }

            for (Path kt : ktFiles) ktCmd.add(kt.toString());
            for (Path jf : javaFiles) ktCmd.add(jf.toString());

            System.out.println("kotlinc " + ktFiles.size() + " .kt + "
                    + javaFiles.size() + " .java (symbol resolution) ...");
            Utils.run(ktCmd.toArray(new String[0]));

            // ── 2. javac ──
            if (!javaFiles.isEmpty()) {
                List<String> javacArgs = new ArrayList<>();
                javacArgs.add("javac");

                // JVM tuning for javac (short-lived, but still benefits from a larger heap).
                javacArgs.add("-J-Xms256m");
                javacArgs.add("-J-Xmx1g");
                javacArgs.add("-J-XX:+UseParallelGC");

                javacArgs.add("-d");
                javacArgs.add(classesDir.toString());
                javacArgs.add("-source");
                javacArgs.add("1.8");
                javacArgs.add("-target");
                javacArgs.add("1.8");
                javacArgs.add("-Xlint:-options");

                List<String> javacCp = new ArrayList<>();
                javacCp.add(classesDir.toString());
                if (ctx.androidJar != null) javacCp.add(ctx.androidJar.toString());
                for (Path lib : ctx.libs) javacCp.add(lib.toString());
                for (Path dep : ctx.deps) javacCp.add(dep.toString());
                javacArgs.add("-cp");
                javacArgs.add(String.join(File.pathSeparator, javacCp));

                javaFiles.forEach(f -> javacArgs.add(f.toString()));
                System.out.println("javac " + javaFiles.size() + " .java ...");
                Utils.run(javacArgs.toArray(new String[0]));
            }

            // ── 3. Collect .class files + R.class ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                        .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) Utils.error("No .class files produced");
            classFiles.addAll(rClasses);
            System.out.println("Compiled " + classFiles.size() + " classes total");

            // ── 4. Merge stdlib + deps ──
            LinkedHashSet<Path> mergeSet = new LinkedHashSet<>();
            mergeSet.addAll(KotlinPipeline.resolveKotlinStdlib(kotlinc));
            mergeSet.addAll(ctx.deps);
            List<Path> mergeJars = new ArrayList<>(mergeSet);

            if (!mergeJars.isEmpty()) {
                System.out.println("Merging " + mergeJars.size() + " jar(s) into dex:");
                for (Path j : mergeJars) System.out.println("  " + j);
            }

            JavaPipeline.compileClassesToDex(classFiles, out, ctx, baseApk, mergeJars);

        } finally {
            KotlinPipeline.deleteRecursive(work);
            System.out.println("Cleaned up temp files");
        }
    }
}
