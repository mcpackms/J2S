package com.j2s;

import java.nio.file.*;
import java.util.*;

/** Disassemble DEX files to Smali using baksmali. */
public class DexSmali {

    public static void run(List<Path> inputFiles, Path out, String selfJar) throws Exception {
        Files.createDirectories(out);
        for (Path dexFile : inputFiles) {
            System.out.println("Processing: " + dexFile);
            Utils.run("java", "-cp", selfJar,
                "com.android.tools.smali.baksmali.Main",
                "d", dexFile.toString(), "-o", out.toString());
        }

        System.out.println("Smali output: " + out);
        Files.walk(out)
                .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".smali"))
                .forEach(f -> System.out.println("  " + out.relativize(f)));
    }
}
