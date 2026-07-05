package com.j2s;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Shared utility methods. */
public class Utils {

    /** Locate the current JAR path (used by child processes to invoke bundled deps). */
    public static String getJarPath() {
        try {
            URL url = Utils.class.getProtectionDomain().getCodeSource().getLocation();
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

    /** Run an external command, inheriting IO. */
    public static void run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new Exception("Command failed: " + String.join(" ", cmd));
        }
    }

    /** Recursively delete a file or directory. */
    public static void delete(Path p) throws IOException {
        if (Files.exists(p)) {
            Files.walk(p)
                 .sorted(Comparator.reverseOrder())
                 .forEach(f -> {
                     try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                 });
        }
    }

    /** Print error and exit. */
    public static void error(String s) {
        System.err.println("Error: " + s);
        System.exit(1);
    }
}
