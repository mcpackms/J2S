package com.j2s;

import java.nio.file.*;
import java.util.*;
import com.googlecode.d2j.dex.Dex2jar;

/** Convert DEX files to JAR using dex2jar. */
public class DexJar {

    public static void run(List<Path> inputFiles, Path out) throws Exception {
        for (Path dexFile : inputFiles) {
            System.out.println("Processing: " + dexFile);
            Dex2jar.from(dexFile.toFile()).doTranslate(out);
        }
        System.out.println("Jar output: " + out);
    }
}
