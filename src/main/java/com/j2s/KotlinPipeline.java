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
 *
 * <p>kotlinc's bundled kotlin-stdlib.jar is automatically located and merged
 * into the output dex, so the runtime no longer crashes with
 * {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics}.
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
            ktCmd.add("-jvm-target");
            ktCmd.add("1.8");

            // Classpath: android.jar + user libs
            // kotlinc 自己会加载 stdlib，无需在 -classpath 里重复指定
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

            // ── 2. Collect .class files ──
            List<Path> classFiles;
            try (Stream<Path> s = Files.walk(classesDir)) {
                classFiles = s.filter(p -> p.toString().endsWith(".class"))
                              .collect(Collectors.toList());
            }
            if (classFiles.isEmpty()) {
                Utils.error("kotlinc produced no .class files");
            }
            System.out.println("Compiled " + classFiles.size() + " classes");

            // ── 3. Locate & merge kotlin-stdlib into the dex ──
            List<Path> stdlibJars = resolveKotlinStdlib(kotlinc);
            if (stdlibJars.isEmpty()) {
                System.out.println("Warning: kotlin-stdlib not found near kotlinc; "
                                 + "runtime may fail with NoClassDefFoundError. "
                                 + "Pass -l <kotlin-stdlib.jar> to include it explicitly.");
            } else {
                System.out.println("Merging kotlin-stdlib (" + stdlibJars.size() + " jar(s)):");
                for (Path j : stdlibJars) System.out.println("  " + j);
            }

            JavaPipeline.compileClassesToDex(
                    classFiles, out, androidJar, libs, minApi, keepDex, selfJar,
                    stdlibJars);

        } finally {
            deleteRecursive(work);
            System.out.println("Cleaned up temp files");
        }
    }

    /** Locate kotlinc: explicit --kotlin-home > PATH. */
    static Path resolveKotlinc(String kotlinHome) {
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

    /**
     * Locate kotlinc's bundled kotlin-stdlib jars (stdlib + jdk7 + jdk8).
     *
     * <p>Search order:
     * <ol>
     *   <li>The real path of {@code kotlinc} (resolving symlinks) → {@code <home>/lib}</li>
     *   <li>{@code $KOTLIN_HOME/lib}</li>
     * </ol>
     *
     * <p>Returns an empty list if nothing is found; caller should not treat
     * that as a hard error (user may supply stdlib via {@code -l}).
     */
    static List<Path> resolveKotlinStdlib(Path kotlinc) {
        if (kotlinc == null) return Collections.emptyList();

        List<Path> homes = new ArrayList<>();

        // 1. Resolve symlinks to find the real kotlinc location
        Path real = kotlinc;
        try { real = kotlinc.toRealPath(); } catch (IOException ignored) {}
        Path bin = real.getParent();
        if (bin != null) {
            Path home = bin.getParent();
            if (home != null) homes.add(home);
        }

        // 2. KOTLIN_HOME environment variable
        String env = System.getenv("KOTLIN_HOME");
        if (env != null && !env.isEmpty()) {
            homes.add(Paths.get(env));
        }

        // 3. Sibling to the raw kotlinc path (in case of wrapper scripts)
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