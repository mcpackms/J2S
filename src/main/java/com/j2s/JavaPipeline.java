package com.j2s;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Java compilation pipeline: javac → d8 → baksmali.
 *
 * <p>With {@code --keep-dex} the baksmali step is skipped and the raw
 * {@code classes.dex} is copied to the output directory instead.
 */
public class JavaPipeline {

    public static void run(List<Path> inputFiles, Path out, Path androidJar,
                           List<Path> libs, int minApi, boolean keepDex,
                           String selfJar) throws Exception {
        Path tmp = Files.createTempDirectory("j2s_");
        System.out.println("Temp dir: " + tmp);
        final Path outputDir = out;

        try {
            // ── javac ──
            List<String> javacArgs = new ArrayList<>();
            javacArgs.add("javac");
            javacArgs.add("-d");
            javacArgs.add(tmp.toString());
            if (androidJar != null || !libs.isEmpty()) {
                String cp = "";
                if (androidJar != null) cp = androidJar.toString();
                if (!libs.isEmpty()) {
                    if (!cp.isEmpty()) cp += File.pathSeparator;
                    cp += libs.stream().map(Path::toString)
                            .collect(Collectors.joining(File.pathSeparator));
                }
                javacArgs.add("-cp");
                javacArgs.add(cp);
            }
            javacArgs.add("-source");
            javacArgs.add("1.8");
            javacArgs.add("-target");
            javacArgs.add("1.8");
            inputFiles.forEach(f -> javacArgs.add(f.toString()));
            Utils.run(javacArgs.toArray(new String[0]));

            List<Path> classes = Files.walk(tmp)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            if (classes.isEmpty()) Utils.error("No .class files generated");
            System.out.println("Compiled " + classes.size() + " classes");

            // ── d8 ──
            Path dexDir = tmp.resolve("dex");
            Files.createDirectory(dexDir);
            List<String> d8cmd = new ArrayList<>();
            d8cmd.add("java");
            d8cmd.add("-cp");
            d8cmd.add(selfJar);
            d8cmd.add("com.android.tools.r8.D8");
            if (androidJar != null) {
                d8cmd.add("--lib");
                d8cmd.add(androidJar.toString());
            }
            for (Path lib : libs) {
                d8cmd.add("--lib");
                d8cmd.add(lib.toString());
            }
            d8cmd.add("--min-api");
            d8cmd.add(String.valueOf(minApi));
            d8cmd.add("--output");
            d8cmd.add(dexDir.toString());
            classes.forEach(c -> d8cmd.add(c.toString()));
            Utils.run(d8cmd.toArray(new String[0]));

            Path dex = dexDir.resolve("classes.dex");
            if (!Files.exists(dex)) Utils.error("d8 did not produce classes.dex");

            if (keepDex) {
                // ── dex only (skip baksmali) ──
                Files.createDirectories(out);
                Path dexOut = out.resolve("classes.dex");
                Files.copy(dex, dexOut, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Dex output: " + dexOut);
            } else {
                // ── baksmali ──
                Files.createDirectories(out);
                Utils.run("java", "-cp", selfJar,
                    "com.android.tools.smali.baksmali.Main",
                    "d", dex.toString(), "-o", out.toString());

                System.out.println("Smali output: " + out);
                Files.walk(out)
                        .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".smali"))
                        .forEach(f -> System.out.println("  " + outputDir.relativize(f)));
            }
        } finally {
            Utils.delete(tmp);
            System.out.println("Cleaned up temp files");
        }
    }
}
