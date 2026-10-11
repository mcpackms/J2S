package com.j2s;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * 资源 + AndroidManifest 处理（基于 aapt2）。
 *
 * <p>流程：
 * <ol>
 *   <li>对每个 res 目录执行 {@code aapt2 compile --dir ... -o ...zip}</li>
 *   <li>{@code aapt2 link ... --auto-add-overlay <zip...> -o base.apk}</li>
 *   <li>{@code javac gen/**&#47;R.java} → R.class</li>
 *   <li>后续把 d8 生成的 dex 塞进 base.apk</li>
 * </ol>
 *
 * <p>资源 overlay 顺序：AAR 的 res 先编译，用户 res 后编译。
 * aapt2 link 中「后传入的覆盖先传入的」，因此用户资源优先级最高。
 */
public final class Aapt2Builder {

    private Aapt2Builder() {}

    public static final class Result {
        public final Path genDir;
        public final Path baseApk;
        Result(Path g, Path a) { genDir = g; baseApk = a; }
    }

    /** 定位 aapt2：显式路径 > PATH > $ANDROID_HOME/build-tools > $ANDROID_SDK_ROOT > $PREFIX/opt/android-sdk。 */
    public static Path findAapt2(String override) {
        if (override != null) {
            Path p = Paths.get(override);
            return Files.isExecutable(p) ? p : null;
        }
        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                Path p = Paths.get(dir, "aapt2");
                if (Files.isExecutable(p)) return p;
            }
        }
        List<Path> sdkRoots = new ArrayList<>();
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk != null) sdkRoots.add(Paths.get(sdk));
        sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk != null) sdkRoots.add(Paths.get(sdk));
        String pfx = System.getenv("PREFIX");
        if (pfx != null) sdkRoots.add(Paths.get(pfx, "opt", "android-sdk"));

        for (Path root : sdkRoots) {
            Path bt = root.resolve("build-tools");
            if (!Files.isDirectory(bt)) continue;
            try (Stream<Path> s = Files.list(bt)) {
                Optional<Path> found = s.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder())
                        .map(d -> d.resolve("aapt2"))
                        .filter(Files::isExecutable)
                        .findFirst();
                if (found.isPresent()) return found.get();
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * aapt2 compile + link。
     *
     * @param androidJar    android.jar
     * @param manifest      AndroidManifest.xml
     * @param resDir        用户主 res 目录（可为 null）
     * @param extraResDirs  额外 res 目录（如 AAR 的 res/），可为 null
     * @param workDir       临时目录
     * @param aapt2Exe      aapt2 可执行文件
     * @param minApi        --min-sdk-version
     * @param targetSdk     --target-sdk-version（默认 34）
     */
    public static Result process(Path androidJar, Path manifest, Path resDir,
                                 List<Path> extraResDirs,
                                 Path workDir, Path aapt2Exe, int minApi,
                                 Integer targetSdk) throws Exception {
        Path genDir = workDir.resolve("gen");
        Path baseApk = workDir.resolve("base.apk");
        Files.createDirectories(genDir);

        List<Path> compiledZips = new ArrayList<>();

        // 1. AAR 的 res（先编译，被用户 res 覆盖）
        if (extraResDirs != null) {
            int idx = 0;
            for (Path extra : extraResDirs) {
                if (extra == null || !Files.isDirectory(extra)) continue;
                Path zip = workDir.resolve("aar-res-" + (idx++) + ".zip");
                compileResDir(aapt2Exe, extra, zip);
                compiledZips.add(zip);
            }
        }

        // 2. 用户主 res（最后编译，overlay 优先级最高）
        if (resDir != null && Files.isDirectory(resDir)) {
            Path zip = workDir.resolve("user-res.zip");
            compileResDir(aapt2Exe, resDir, zip);
            compiledZips.add(zip);
        }

        // 3. aapt2 link
        List<String> linkCmd = new ArrayList<>();
        linkCmd.add(aapt2Exe.toString());
        linkCmd.add("link");
        linkCmd.add("-o");
        linkCmd.add(baseApk.toString());
        linkCmd.add("-I");
        linkCmd.add(androidJar.toString());
        linkCmd.add("--manifest");
        linkCmd.add(manifest.toString());
        linkCmd.add("--java");
        linkCmd.add(genDir.toString());
        linkCmd.add("--min-sdk-version");
        linkCmd.add(String.valueOf(minApi));
        linkCmd.add("--target-sdk-version");
        linkCmd.add(String.valueOf(targetSdk != null ? targetSdk : 34));
        linkCmd.add("--auto-add-overlay");
        for (Path zip : compiledZips) {
            linkCmd.add(zip.toString());
        }
        System.out.println("aapt2 link -> " + baseApk.getFileName()
                + " (" + compiledZips.size() + " res zip(s))");
        Utils.run(linkCmd.toArray(new String[0]));

        if (!Files.exists(baseApk)) {
            Utils.error("aapt2 link did not produce base.apk");
        }
        return new Result(genDir, baseApk);
    }

    private static void compileResDir(Path aapt2, Path resDir, Path outZip) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(aapt2.toString());
        cmd.add("compile");
        cmd.add("--dir");
        cmd.add(resDir.toString());
        cmd.add("-o");
        cmd.add(outZip.toString());
        System.out.println("aapt2 compile " + resDir);
        Utils.run(cmd.toArray(new String[0]));
    }

    /** 编译 aapt2 生成的 R.java，返回 .class 文件列表。 */
    public static List<Path> compileRJava(Path genDir, Path androidJar,
                                          Path workDir) throws Exception {
        if (genDir == null || !Files.exists(genDir)) {
            return Collections.emptyList();
        }
        List<Path> rJavaFiles;
        try (Stream<Path> s = Files.walk(genDir)) {
            rJavaFiles = s.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        if (rJavaFiles.isEmpty()) return Collections.emptyList();

        Path rClasses = workDir.resolve("r_classes");
        Files.createDirectories(rClasses);

        List<String> javacArgs = new ArrayList<>();
        javacArgs.add("javac");
        javacArgs.add("-d");
        javacArgs.add(rClasses.toString());
        javacArgs.add("-source");
        javacArgs.add("1.8");
        javacArgs.add("-target");
        javacArgs.add("1.8");
        javacArgs.add("-Xlint:-options");
        javacArgs.add("-cp");
        javacArgs.add(androidJar.toString());
        rJavaFiles.forEach(f -> javacArgs.add(f.toString()));
        System.out.println("javac R.java (" + rJavaFiles.size() + " file(s)) ...");
        Utils.run(javacArgs.toArray(new String[0]));

        try (Stream<Path> s = Files.walk(rClasses)) {
            return s.filter(p -> p.toString().endsWith(".class"))
                    .collect(Collectors.toList());
        }
    }

    /** 把 dex 塞进 base.apk，输出最终 apk。 */
    public static void packApk(Path baseApk, List<Path> dexFiles, Path outApk)
            throws Exception {
        Path tmp = Files.createTempFile("j2s-apk-", ".apk");
        try {
            Files.copy(baseApk, tmp, StandardCopyOption.REPLACE_EXISTING);
            Map<String, String> env = new HashMap<>();
            env.put("create", "false");
            URI uri = URI.create("jar:" + tmp.toUri());
            try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
                for (Path dex : dexFiles) {
                    Path target = fs.getPath("/" + dex.getFileName().toString());
                    Files.copy(dex, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("  + " + dex.getFileName());
                }
            }
            Path parent = outApk.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.move(tmp, outApk, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}