import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class J2S {
    public static void main(String[] args) throws Exception {
        // ---- 定位当前 JAR 路径 ----
        String selfJar = getJarPath();
        if (selfJar == null) {
            error("Cannot determine J2S.jar path. Run with java -jar J2S.jar");
        }

        // ---- 无参数时显示帮助 ----
        if (args.length == 0) {
            usage();
        }

        // ---- 解析参数 ----
        Path out = Paths.get("smali_out");
        Path androidJar = null;
        List<Path> libs = new ArrayList<>();
        List<Path> inputFiles = new ArrayList<>();
        boolean keepDex = false;
        boolean outSpecified = false;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "-o":
                    if (++i < args.length) {
                        out = Paths.get(args[i]).toAbsolutePath();
                        outSpecified = true;
                    } else error("Missing output dir after -o");
                    break;
                case "-a": case "--android-jar":
                    if (++i < args.length) androidJar = Paths.get(args[i]).toAbsolutePath();
                    else error("Missing android.jar after " + args[i-1]);
                    break;
                case "-l": case "--lib":
                    if (++i < args.length) libs.add(Paths.get(args[i]).toAbsolutePath());
                    else error("Missing lib jar after " + args[i-1]);
                    break;
                case "--dex":
                    keepDex = true;
                    break;
                default:
                    Path p = Paths.get(args[i]).toAbsolutePath();
                    if (!Files.exists(p)) error(p + " does not exist");
                    if (p.toString().endsWith(".java") || p.toString().endsWith(".dex")) inputFiles.add(p);
                    else error("Unrecognized file or option: " + args[i]);
            }
            i++;
        }

        if (inputFiles.isEmpty())
            error("At least one .java or .dex file is required");

        boolean javaMode = inputFiles.stream().allMatch(f -> f.toString().endsWith(".java"));
        boolean dexMode = inputFiles.stream().allMatch(f -> f.toString().endsWith(".dex"));
        if (!javaMode && !dexMode)
            error("Mixed .java and .dex files are not allowed");

        // --dex 模式下默认输出到 dex_out（除非用户通过 -o 指定了输出目录）
        if (keepDex && javaMode && !outSpecified) {
            out = Paths.get("dex_out");
        }

        if (androidJar != null && !Files.exists(androidJar))
            error(androidJar + " does not exist");
        for (Path lib : libs)
            if (!Files.exists(lib)) error(lib + " does not exist");

        Path finalOut = out;
        if (dexMode) {
            // ---- dex → smali ----
            Files.createDirectories(out);
            for (Path dexFile : inputFiles) {
                System.out.println("Processing: " + dexFile);
                run("java", "-cp", selfJar,
                    "com.android.tools.smali.baksmali.Main",
                    "d", dexFile.toString(), "-o", out.toString());
            }

            System.out.println("Smali output: " + finalOut);
            Files.walk(finalOut)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".smali"))
                    .forEach(f -> System.out.println("  " + finalOut.relativize(f)));
        } else {
            // ---- java pipeline ----
            Path tmp = Files.createTempDirectory("j2s_");
            System.out.println("Temp dir: " + tmp);
            final Path outputDir = out;

            try {
                // ---- javac ----
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
                run(javacArgs.toArray(new String[0]));

                List<Path> classes = Files.walk(tmp)
                        .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".class"))
                        .collect(Collectors.toList());
                if (classes.isEmpty()) error("No .class files generated");
                System.out.println("Compiled " + classes.size() + " classes");

                // ---- d8 ----
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
                d8cmd.add("--output");
                d8cmd.add(dexDir.toString());
                classes.forEach(c -> d8cmd.add(c.toString()));
                run(d8cmd.toArray(new String[0]));

                Path dex = dexDir.resolve("classes.dex");
                if (!Files.exists(dex)) error("d8 did not produce classes.dex");

                if (keepDex) {
                    // ---- only dex, no smali ----
                    Files.createDirectories(out);
                    Path dexOut = out.resolve("classes.dex");
                    Files.copy(dex, dexOut, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Dex output: " + dexOut);
                } else {
                    // ---- baksmali ----
                    Files.createDirectories(out);
                    run("java", "-cp", selfJar,
                        "com.android.tools.smali.baksmali.Main",
                        "d", dex.toString(), "-o", out.toString());

                    System.out.println("Smali output: " + out);
                    Files.walk(out)
                            .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".smali"))
                            .forEach(f -> System.out.println("  " + outputDir.relativize(f)));
                }
            } finally {
                delete(tmp);
                System.out.println("Cleaned up temp files");
            }
        }
    }

    /** 获取当前 JAR 的路径 */
    private static String getJarPath() {
        try {
            URL url = J2S.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null && "file".equals(url.getProtocol())) {
                Path p = Paths.get(url.toURI());
                if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                    return p.toAbsolutePath().toString();
                }
            }
            String cp = System.getProperty("java.class.path");
            for (String entry : cp.split(File.pathSeparator)) {
                if (entry.endsWith(".jar") && Files.isRegularFile(Paths.get(entry))) {
                    return Paths.get(entry).toAbsolutePath().toString();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    static void run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new Exception("Command failed: " + String.join(" ", cmd));
        }
    }

    static void delete(Path p) throws IOException {
        if (Files.exists(p)) {
            Files.walk(p)
                 .sorted(Comparator.reverseOrder())
                 .forEach(f -> {
                     try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                 });
        }
    }

    static void usage() {
        System.err.println("Usage: java -jar J2S.jar [options] <source.java... | source.dex...>");
        System.err.println();
        System.err.println("Modes:");
        System.err.println("  Java mode (default)    Compile .java → .class → .dex → .smali");
        System.err.println("  Java mode (--dex)      Same as above, but keep only .dex");
        System.err.println("  Dex mode              Disassemble .dex → .smali directly");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -o <dir>              Output directory (default: smali_out)");
        System.err.println("  -a, --android-jar     Android framework jar (required if source uses android.* APIs)");
        System.err.println("  -l, --lib <jar>       Additional library jar (repeatable, Java mode only)");
        System.err.println("  --dex                 Dex-only output in Java mode");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar J2S.jar Hello.java");
        System.err.println("  java -jar J2S.jar --dex Hello.java              # dex only");
        System.err.println("  java -jar J2S.jar -a android.jar -o out *.java");
        System.err.println("  java -jar J2S.jar classes.dex                   # dex → smali");
        System.err.println("  java -jar J2S.jar -o smali_out classes.dex");
        System.exit(1);
    }

    static void error(String s) {
        System.err.println("Error: " + s);
        System.exit(1);
    }
}
