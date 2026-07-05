package com.j2s;

import java.nio.file.*;
import java.util.*;

/** Convert JAR files to DEX (dex.jar) using D8 with --release. */
public class JarDex {

    public static void run(List<Path> inputFiles, Path out, Path androidJar,
                           List<Path> libs, int minApi, String selfJar) throws Exception {
        System.out.println("Jar -> Dex mode: " + inputFiles);
        List<String> d8cmd = new ArrayList<>();
        d8cmd.add("java");
        d8cmd.add("-cp");
        d8cmd.add(selfJar);
        d8cmd.add("com.android.tools.r8.D8");
        d8cmd.add("--release");
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
        d8cmd.add(out.toString());
        for (Path jarFile : inputFiles) {
            d8cmd.add(jarFile.toString());
        }
        Utils.run(d8cmd.toArray(new String[0]));
        System.out.println("Dex output: " + out);
    }
}
