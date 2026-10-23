package com.j2s;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a source-mode pipeline (Java / Kotlin / mixed) needs. Keeps the pipeline signatures
 * short and lets all modes share the same dependency + APK handling.
 */
public final class BuildContext {

    public Path androidJar;
    /** Compile-only libraries ({@code -l/--lib}), never merged into the output dex. */
    public List<Path> libs = new ArrayList<>();
    /** Resolved dependencies ({@code -D/--dep}), on the classpath and merged into the output dex. */
    public List<Path> deps = new ArrayList<>();
    public int minApi = 21;
    public boolean keepDex;
    /** J2S's own jar; D8 / baksmali / dex2jar are launched from it as child processes. */
    public String selfJar;

    public String kotlinHome;
    public String composePlugin;

    public Path manifest;
    public Path resDir;
    /** Optional user assets directory (APK mode), added on top of AAR assets. */
    public Path assetsDir;
    /** AAR resources / assets / JNI libs / manifests coming from resolved dependencies. */
    public DepResources resources = DepResources.EMPTY;

    public Path aapt2Exe;
    public Path outApk;
    public Integer targetSdk;
    /** Merge AAR AndroidManifest.xml files into the user manifest (default: true). */
    public boolean manifestMerge = true;
}
