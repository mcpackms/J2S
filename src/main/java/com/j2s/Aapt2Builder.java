package com.j2s;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Resources + AndroidManifest handling (based on aapt2).
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code aapt2 compile --dir <res> -o <zip>} for every AAR {@code res/} and the user's
 *       {@code res/}</li>
 *   <li>merge AAR assets + user assets, and AAR manifests + user manifest (conservative merge)</li>
 *   <li>{@code aapt2 link ... --auto-add-overlay <zip...> -o base.apk}</li>
 *   <li>{@code javac gen/**&#47;R.java} -&gt; R.class</li>
 *   <li>pack the d8 output (and JNI libs) into base.apk</li>
 * </ol>
 *
 * <p>Resource overlay order: AAR resources first, user resources last. {@code aapt2 link} lets
 * later inputs override earlier ones, so user resources always win.
 */
public final class Aapt2Builder {

    private Aapt2Builder() {}

    public static final class Result {
        public final Path genDir;
        public final Path baseApk;
        Result(Path g, Path a) { genDir = g; baseApk = a; }
    }

    /** Locate aapt2: explicit path &gt; PATH &gt; $ANDROID_HOME/build-tools &gt; $ANDROID_SDK_ROOT &gt; $PREFIX/opt/android-sdk. */
    public static Path findAapt2(String override) {
        if (override != null) {
            Path p = Paths.get(override);
            if (Files.isExecutable(p)) return p;
            Path exe = Paths.get(override + ".exe");
            if (Files.isRegularFile(exe)) return exe;
            return Files.isRegularFile(p) ? p : null;
        }
        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                Path p = executableIn(Paths.get(dir), "aapt2");
                if (p != null) return p;
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
                        .map(d -> executableIn(d, "aapt2"))
                        .filter(Objects::nonNull)
                        .findFirst();
                if (found.isPresent()) return found.get();
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static Path executableIn(Path dir, String name) {
        Path p = dir.resolve(name);
        if (Files.isExecutable(p)) return p;
        Path exe = dir.resolve(name + ".exe");
        if (Files.isRegularFile(exe)) return exe;
        return null;
    }

    /**
     * aapt2 compile + link.
     *
     * @param androidJar      android.jar
     * @param manifest        user AndroidManifest.xml
     * @param resDir          user res/ directory (may be null), highest priority
     * @param extraResDirs    AAR res/ directories, lower priority, in dependency order
     * @param aarManifests    AAR AndroidManifest.xml files to merge into the user manifest
     * @param aarAssetsDirs   AAR assets/ directories
     * @param userAssetsDir   user assets/ directory (may be null), highest priority
     * @param workDir         temporary directory
     * @param aapt2Exe        aapt2 executable
     * @param minApi          --min-sdk-version
     * @param targetSdk       --target-sdk-version (default 34)
     * @param mergeManifests  false to skip the AAR manifest merge
     */
    public static Result process(Path androidJar, Path manifest, Path resDir,
                                 List<Path> extraResDirs,
                                 List<Path> aarManifests,
                                 List<Path> aarAssetsDirs,
                                 Path userAssetsDir,
                                 Path workDir, Path aapt2Exe, int minApi,
                                 Integer targetSdk, boolean mergeManifests) throws Exception {
        Path genDir = workDir.resolve("gen");
        Path baseApk = workDir.resolve("base.apk");
        Files.createDirectories(genDir);

        List<Path> compiledZips = new ArrayList<>();

        // 1. AAR res (compiled first => lower priority)
        if (extraResDirs != null) {
            int idx = 0;
            for (Path extra : extraResDirs) {
                if (extra == null || !Files.isDirectory(extra)) continue;
                Path zip = workDir.resolve("aar-res-" + (idx++) + ".zip");
                compileResDir(aapt2Exe, extra, zip);
                compiledZips.add(zip);
            }
        }

        // 2. user res (compiled last => highest priority)
        if (resDir != null && Files.isDirectory(resDir)) {
            Path zip = workDir.resolve("user-res.zip");
            compileResDir(aapt2Exe, resDir, zip);
            compiledZips.add(zip);
        }

        // 3. assets: AAR assets first, user assets on top
        Path mergedAssets = null;
        boolean hasAarAssets = aarAssetsDirs != null && !aarAssetsDirs.isEmpty();
        if (hasAarAssets || (userAssetsDir != null && Files.isDirectory(userAssetsDir))) {
            mergedAssets = workDir.resolve("assets-merged");
            Files.createDirectories(mergedAssets);
            if (aarAssetsDirs != null) {
                for (Path assets : aarAssetsDirs) {
                    if (assets != null && Files.isDirectory(assets)) copyTree(assets, mergedAssets);
                }
            }
            if (userAssetsDir != null && Files.isDirectory(userAssetsDir)) {
                copyTree(userAssetsDir, mergedAssets);
            }
        }

        // 4. manifest merge
        Path effectiveManifest = manifest;
        if (mergeManifests && aarManifests != null && !aarManifests.isEmpty()) {
            effectiveManifest = ManifestMerger.merge(manifest, aarManifests,
                    workDir.resolve("AndroidManifest-merged.xml"));
        }

        // 5. aapt2 link
        List<String> linkCmd = new ArrayList<>();
        linkCmd.add(aapt2Exe.toString());
        linkCmd.add("link");
        linkCmd.add("-o");
        linkCmd.add(baseApk.toString());
        linkCmd.add("-I");
        linkCmd.add(androidJar.toString());
        linkCmd.add("--manifest");
        linkCmd.add(effectiveManifest.toString());
        linkCmd.add("--java");
        linkCmd.add(genDir.toString());
        linkCmd.add("--min-sdk-version");
        linkCmd.add(String.valueOf(minApi));
        linkCmd.add("--target-sdk-version");
        linkCmd.add(String.valueOf(targetSdk != null ? targetSdk : 34));
        linkCmd.add("--auto-add-overlay");
        if (mergedAssets != null) {
            linkCmd.add("-A");
            linkCmd.add(mergedAssets.toString());
        }
        for (Path zip : compiledZips) {
            linkCmd.add(zip.toString());
        }
        System.out.println("aapt2 link -> " + baseApk.getFileName()
                + " (" + compiledZips.size() + " res zip(s)"
                + (mergedAssets != null ? ", assets merged" : "") + ")");
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

    /** Compile aapt2-generated R.java files, returning the produced .class files. */
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

    /** Pack dex files into base.apk, without JNI libs. */
    public static void packApk(Path baseApk, List<Path> dexFiles, Path outApk)
            throws Exception {
        packApk(baseApk, dexFiles, outApk, null);
    }

    /**
     * Pack dex files and JNI libraries into base.apk.
     *
     * @param jniDirs AAR {@code jni/} directories (layout {@code jni/<abi>/<lib>.so}); copied to
     *                {@code lib/<abi>/<lib>.so}. The first AAR providing a given .so wins.
     */
    public static void packApk(Path baseApk, List<Path> dexFiles, Path outApk,
                               List<Path> jniDirs) throws Exception {
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
                if (jniDirs != null) {
                    for (Path jni : jniDirs) {
                        if (jni == null || !Files.isDirectory(jni)) continue;
                        List<Path> libs;
                        try (Stream<Path> s = Files.walk(jni)) {
                            libs = s.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".so"))
                                    .sorted()
                                    .collect(Collectors.toList());
                        }
                        for (Path lib : libs) {
                            String rel = jni.relativize(lib).toString().replace('\\', '/');
                            Path target = fs.getPath("/lib/" + rel);
                            if (Files.exists(target)) continue;
                            Path parent = target.getParent();
                            if (parent != null) Files.createDirectories(parent);
                            Files.copy(lib, target, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("  + lib/" + rel);
                        }
                    }
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

    /** Recursively copy all regular files from {@code source} into {@code target}. */
    static void copyTree(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) return;
        List<Path> files;
        try (Stream<Path> s = Files.walk(source)) {
            files = s.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path f : files) {
            Path rel = source.relativize(f);
            Path out = target.resolve(rel.toString());
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(f, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
