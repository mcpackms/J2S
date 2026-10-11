package com.j2s;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Java compilation pipeline: javac → d8 → (multidex) → smali / APK.
 */
public class JavaPipeline {

    public static void run(List<Path> inputFiles, Path out, Path androidJar,
                           List<Path> libs, int minApi, boolean keepDex,
                           String selfJar) throws Exception {
        Path tmp = Files.createTempDirectory("j2s_");
        System.out.println("Temp dir: " + tmp);

        try {
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
            javacArgs.add("-Xlint:-options");
            inputFiles.forEach(f -> javacArgs.add(f.toString()));
            Utils.run(javacArgs.toArray(new String[0]));

            List<Path> classes = Files.walk(tmp)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            if (classes.isEmpty()) Utils.error("No .class files generated");
            System.out.println("Compiled " + classes.size() + " classes");

            compileClassesToDex(classes, out, androidJar, libs, minApi, keepDex, selfJar);

        } finally {
            Utils.delete(tmp);
            System.out.println("Cleaned up temp files");
        }
    }

    /** 旧签名：无 merge jars、无 APK。 */
    public static void compileClassesToDex(List<Path> classes, Path out, Path androidJar,
                                           List<Path> libs, int minApi, boolean keepDex,
                                           String selfJar) throws Exception {
        compileClassesToDex(classes, out, androidJar, libs, minApi, keepDex,
                selfJar, Collections.<Path>emptyList(), null, null);
    }

    /** 中间签名：有 merge jars、无 APK。 */
    public static void compileClassesToDex(List<Path> classes, Path out, Path androidJar,
                                           List<Path> libs, int minApi, boolean keepDex,
                                           String selfJar,
                                           List<Path> mergeJars) throws Exception {
        compileClassesToDex(classes, out, androidJar, libs, minApi, keepDex,
                selfJar, mergeJars, null, null);
    }

    /**
     * 完整签名。支持 multidex 和 APK 打包。
     *
     * @param mergeJars  作为 d8 输入编进 dex 的 jar（kotlin-stdlib、maven deps）
     * @param baseApk    aapt2 link 生成的 base.apk（含 resources.arsc + manifest）
     * @param outApk     输出 APK 路径（baseApk 非 null 时必填）
     */
    public static void compileClassesToDex(List<Path> classes, Path out, Path androidJar,
                                           List<Path> libs, int minApi, boolean keepDex,
                                           String selfJar,
                                           List<Path> mergeJars,
                                           Path baseApk, Path outApk) throws Exception {
        if (classes.isEmpty()) Utils.error("No .class files to convert");

        // 可缓存输入：stdlib + maven deps（多次构建之间内容不变）
        List<Path> cacheable = (mergeJars == null) ? Collections.<Path>emptyList()
                : mergeJars.stream()
                        .filter(p -> p != null && Files.isRegularFile(p))
                        .collect(Collectors.toList());

        Path dexWork = Files.createTempDirectory("j2s_dex_");
        try {
            Path dexDir = dexWork.resolve("dex");
            Files.createDirectory(dexDir);

            // ── 1. stdlib / deps：走 DexCache，命中则完全跳过 D8 ──
            List<Path> cacheableDex = Collections.emptyList();
            if (!cacheable.isEmpty()) {
                Path cacheWork = dexWork.resolve("cache");
                Files.createDirectory(cacheWork);
                cacheableDex = DexCache.resolve(cacheable, minApi, androidJar, libs,
                        cacheWork,
                        o -> runD8(cacheable, o, androidJar, libs, minApi, selfJar));
            }

            // ── 2. 业务 class + （缓存得到的）stdlib/deps dex ──
            List<Path> mainInputs = new ArrayList<>(classes);
            mainInputs.addAll(cacheableDex);
            runD8(mainInputs, dexDir, androidJar, libs, minApi, selfJar);

            // 收集所有 dex（multidex）
            List<Path> dexFiles;
            try (Stream<Path> s = Files.list(dexDir)) {
                dexFiles = s.filter(p -> p.getFileName().toString().matches("classes\\d*\\.dex"))
                        .sorted()
                        .collect(Collectors.toList());
            }
            if (dexFiles.isEmpty()) Utils.error("d8 did not produce any dex");
            System.out.println("D8 produced " + dexFiles.size() + " dex file(s)");

            if (baseApk != null && outApk != null) {
                // APK 模式：把 dex 塞进 aapt2 生成的 base.apk
                Aapt2Builder.packApk(baseApk, dexFiles, outApk);
                System.out.println("APK output: " + outApk);
            } else if (keepDex) {
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
                args.add("-cp");
                args.add(selfJar);
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
     * 调用 D8 把 inputs dex 化到 outDir，返回生成的 classes*.dex（multidex 时多个）。
     *
     * @param selfJar J2S 自身 jar（R8 以子进程方式加载）
     */
    static List<Path> runD8(List<Path> inputs, Path outDir, Path androidJar,
                            List<Path> libs, int minApi, String selfJar)
            throws Exception {
        List<String> d8cmd = new ArrayList<>();
        d8cmd.add("java");
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