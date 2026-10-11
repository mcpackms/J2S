package com.j2s;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * D8 产物缓存。
 *
 * <p><b>解决什么问题</b>：Kotlin / 混合模式下 {@code kotlin-stdlib.jar} 与 Maven 依赖每次
 * 都被完整地重新 dex 一遍。实测在 Termux 上仅 {@code kotlin-stdlib.jar} 一项就要 ~32s，
 * 而业务代码本身只需 ~2s。由于这些 jar 在多次构建之间<b>内容完全不变</b>，其 dex 产物
 * 可以按指纹缓存复用。
 *
 * <p><b>工作方式</b>：调用方把「可缓存输入」（stdlib + maven deps）交给 {@link #resolve}，
 * 并提供一个 {@link Producer} 回调用于真正跑 D8。命中时回调<b>根本不会被执行</b>；
 * 未命中时执行一次、写入缓存、并返回缓存中的 dex 路径。随后调用方把这些 dex
 * 连同自己的业务 class 一起交给第二次 D8，产出最终 classes.dex。
 *
 * <p><b>缓存键（指纹）</b>覆盖：格式版本、D8 版本、--min-api、android.jar 内容、
 * 全部 --lib 内容、release/debug 模式、以及每个可缓存 jar 的 SHA-256。
 * 任一变化都会 miss，因此不会产生陈旧产物。
 *
 * <p><b>设计原则</b>：缓存是纯加速器，不是单点故障。任何异常（目录不可写、只读文件系统、
 * 不支持原子移动、指纹失败）都只打印一行 warning，然后让构建照常继续。
 *
 * <p><b>磁盘布局</b>：
 * <pre>
 * &lt;root&gt;/&lt;key&gt;/classes.dex      缓存产物（multidex 时含 classes2.dex …）
 * &lt;root&gt;/&lt;key&gt;/meta.properties  key + 时间戳 + 输入清单（便于排查）
 * &lt;root&gt;/&lt;key&gt;.lock            single-flight 锁文件
 * &lt;root&gt;/.trash-&lt;key&gt;-&lt;n&gt;        原子替换时的临时目录
 * </pre>
 */
public final class DexCache {

    /** 缓存格式版本。语义变化时递增，使全部旧条目自动失效。 */
    private static final String FORMAT = "v1";

    private static final String META = "meta.properties";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String TRASH_PREFIX = ".trash-";

    /** 最多保留的条目数（LRU）。 */
    private static final int MAX_ENTRIES = 12;
    /** 缓存总字节上限。 */
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    /** 获取 single-flight 锁的最长等待时间。 */
    private static final long LOCK_WAIT_MS = 90_000L;
    private static final long LOCK_POLL_MS = 250L;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Properties BUILD = loadBuildInfo();

    private static boolean enabled = true;
    private static Path root;
    private static boolean rootAnnounced = false;

    private DexCache() {}

    /** 真正的 D8 执行回调，由调用方提供（因为它需要 selfJar 等上下文）。 */
    public interface Producer {
        /** 把给定输入 dex 化到 outDir，返回生成的 classes*.dex 路径列表。 */
        List<Path> produce(Path outDir) throws Exception;
    }

    // ───────────────────────────────────────────── 配置

    /**
     * @param explicitRoot 显式缓存根目录（--dex-cache），null 则用默认规则
     * @param enable        false 时完全禁用缓存（--no-dex-cache）
     */
    static void configure(Path explicitRoot, boolean enable) {
        enabled = enable;
        root = (explicitRoot != null) ? explicitRoot.toAbsolutePath() : defaultRoot();
        rootAnnounced = false;
    }

    static Path root() {
        return root;
    }

    static boolean enabled() {
        return enabled;
    }

    /** 默认根目录：J2S_CACHE 环境变量 > $XDG_CACHE_HOME/j2s/dex > ~/.cache/j2s/dex */
    private static Path defaultRoot() {
        String env = System.getenv("J2S_CACHE");
        if (isNotEmpty(env)) return Paths.get(env, "dex").toAbsolutePath();
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (isNotEmpty(xdg)) return Paths.get(xdg, "j2s", "dex").toAbsolutePath();
        return Paths.get(System.getProperty("user.home"), ".cache", "j2s", "dex")
                     .toAbsolutePath();
    }

    // ───────────────────────────────────────────── 主入口

    /**
     * 取得可缓存输入对应的 dex（命中缓存则免去 D8 开销）。
     *
     * @param cacheable 可缓存输入（kotlin-stdlib、maven deps 等）
     * @param workDir   未命中时供 {@link Producer} 写产物的临时目录；该目录由调用方负责清理
     * @return 可直接作为 D8 program input 的 dex 列表；缓存不可用时返回空列表
     *         （此时 producer 也不会被调用，调用方应自行处理无 stdlib 的构建）
     */
    static List<Path> resolve(List<Path> cacheable, int minApi, Path androidJar,
                              List<Path> libs, Path workDir, Producer producer)
            throws Exception {
        if (!enabled || cacheable == null || cacheable.isEmpty()) {
            return Collections.emptyList();
        }
        if (producer == null) {
            return Collections.emptyList();
        }

        final String key;
        try {
            key = key(cacheable, minApi, androidJar, libs);
        } catch (Exception e) {
            warn("cannot compute cache key (" + e + "); caching disabled for this run");
            enabled = false;
            return Collections.emptyList();
        }

        if (!ensureRoot()) return Collections.emptyList();

        final Path entry = root.resolve(key);

        // ── 快路径：直接命中，不加锁 ──
        List<Path> hit = readEntry(entry, key);
        if (hit != null) {
            announce();
            log("HIT  " + key + "  (" + hit.size() + " dex, " + cacheable.size()
                    + " jar(s) skipped)");
            touch(entry);
            return hit;
        }

        try (Lock lock = acquire(entry)) {
            // ── double-check：等锁期间别人可能已写好 ──
            if (lock.acquired) {
                hit = readEntry(entry, key);
                if (hit != null) {
                    announce();
                    log("HIT  " + key + "  (waited for peer build)");
                    touch(entry);
                    return hit;
                }
            }

            announce();
            long t0 = System.nanoTime();
            System.out.println("DexCache: MISS " + key + "  (" + cacheable.size()
                    + " jar(s) -> dex, this is paid once)");

            // 生产者失败属于真正的构建失败，必须向上抛。
            List<Path> produced = producer.produce(workDir);
            double secs = (System.nanoTime() - t0) / 1e9;

            try {
                List<Path> stored = store(entry, key, cacheable, produced);
                log("STORE " + key + "  (" + produced.size() + " dex, "
                        + String.format("%.1fs", secs) + ")");
                prune();
                return stored;
            } catch (Exception e) {
                // 写缓存失败不影响构建：直接用临时产物继续。
                warn("cannot write cache (" + e + "); continuing without caching");
                return produced;
            }
        }
    }

    // ───────────────────────────────────────────── 指纹

    /** 组合出缓存键。任一输入变化 → 键变化 → miss。 */
    static String key(List<Path> inputs, int minApi, Path androidJar, List<Path> libs)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        update(md, "format=" + FORMAT);
        update(md, "d8=" + BUILD.getProperty("j2s.d8.version", "unknown"));
        update(md, "minApi=" + minApi);
        update(md, "android=" + sha(androidJar));
        if (libs != null) {
            List<Path> sorted = new ArrayList<>();
            for (Path l : libs) if (l != null && Files.isRegularFile(l)) sorted.add(l);
            sorted.sort(Comparator.comparing(Path::toString));
            for (Path l : sorted) update(md, "lib=" + sha(l));
        }
        List<Path> sortedInputs = new ArrayList<>(inputs);
        sortedInputs.sort(Comparator.comparing(Path::toString));
        for (Path p : sortedInputs) update(md, "input=" + sha(p));

        String hex = hex(md.digest());
        return hex.substring(0, 32);
    }

    /** 单个文件的 SHA-256；文件不存在时返回 "none"（与空文件区分）。 */
    private static String sha(Path p) throws Exception {
        if (p == null || !Files.isRegularFile(p)) return "none";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static void update(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────── 读写

    /** 校验并读取一个缓存条目；任何异常/不完整都视为 miss（返回 null）。 */
    private static List<Path> readEntry(Path entry, String key) {
        try {
            if (!Files.isDirectory(entry)) return null;
            Path meta = entry.resolve(META);
            if (!Files.isRegularFile(meta)) return null;

            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(meta)) {
                p.load(in);
            }
            if (!key.equals(p.getProperty("key"))) return null;

            List<Path> found;
            try (Stream<Path> s = Files.list(entry)) {
                found = s.filter(Files::isRegularFile)
                         .filter(f -> f.getFileName().toString().matches("classes\\d*\\.dex"))
                         .sorted(DEX_ORDER)
                         .collect(Collectors.toList());
            }
            if (found.isEmpty()) return null;
            // 0 字节 dex = 上次构建被强杀留下的垃圾
            for (Path f : found) {
                if (Files.size(f) == 0) return null;
            }
            return found;
        } catch (Exception e) {
            return null;   // 读失败一律当作 miss，绝不因缓存问题中断构建
        }
    }

    /** 写入缓存。返回缓存目录内的 dex 路径；失败时抛异常由调用方降级。 */
    private static List<Path> store(Path entry, String key, List<Path> inputs,
                                    List<Path> produced) throws IOException {
        if (produced == null || produced.isEmpty()) {
            throw new IOException("D8 produced no dex to cache");
        }

        Path staging = root.resolve(TRASH_PREFIX + key + "-" + token());
        deleteRecursively(staging);
        Files.createDirectories(staging);

        List<String> names = new ArrayList<>();
        try {
            for (Path d : produced) {
                String name = d.getFileName().toString();
                if (!name.matches("classes\\d*\\.dex")) continue;
                Files.copy(d, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                names.add(name);
            }
            if (names.isEmpty()) throw new IOException("no classes*.dex to cache");

            writeMeta(staging.resolve(META), key, inputs, names);

            // 目录级原子替换：先把旧目录挪到 trash，再 rename 新目录进去。
            Path trash = null;
            if (Files.exists(entry)) {
                trash = root.resolve(TRASH_PREFIX + "old-" + key + "-" + token());
                deleteRecursively(trash);
                Files.move(entry, trash, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, entry);
            }
            if (trash != null) deleteRecursively(trash);
        } catch (Exception e) {
            deleteRecursively(staging);
            throw (e instanceof IOException) ? (IOException) e : new IOException(e);
        }

        List<Path> out = new ArrayList<>(names.size());
        for (String n : names) out.add(entry.resolve(n));
        return out;
    }

    private static void writeMeta(Path meta, String key, List<Path> inputs,
                                  List<String> dexNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# J2S dex cache entry — 缓存命中条件即 key 的全部字段\n");
        sb.append("key=").append(key).append('\n');
        sb.append("format=").append(FORMAT).append('\n');
        sb.append("d8=").append(BUILD.getProperty("j2s.d8.version", "unknown")).append('\n');
        sb.append("j2s=").append(BUILD.getProperty("j2s.version", "unknown")).append('\n');
        sb.append("writtenAt=").append(System.currentTimeMillis()).append('\n');
        sb.append("dex=").append(String.join(",", dexNames)).append('\n');
        List<Path> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path p : sorted) {
            sb.append("input=").append(p.getFileName()).append('\n');
        }
        try (OutputStream os = Files.newOutputStream(meta,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ───────────────────────────────────────────── 维护

    /** LRU 清理：条目数或总字节超限就删最旧的。永远 best-effort。 */
    static void prune() {
        if (!enabled || root == null) return;
        try {
            if (!Files.isDirectory(root)) return;
            List<Path> entries = listEntries();
            long total = 0;
            Map<Path, Long> sizes = new LinkedHashMap<>();
            for (Path e : entries) {
                long sz = sizeOf(e);
                sizes.put(e, sz);
                total += sz;
            }
            if (entries.size() <= MAX_ENTRIES && total <= MAX_BYTES) return;

            entries.sort(Comparator.comparingLong(DexCache::mtimeOf));
            int removed = 0;
            for (Path e : entries) {
                boolean overCount = entries.size() - removed > MAX_ENTRIES;
                boolean overSize = total > MAX_BYTES;
                if (!overCount && !overSize) break;
                long sz = sizes.getOrDefault(e, 0L);
                deleteRecursively(e);
                total -= sz;
                removed++;
            }
            if (removed > 0) log("PRUNE removed " + removed + " stale entrie(s)");
        } catch (Exception ignored) {
            // 清理失败完全无害
        }
    }

    /** 清空缓存，返回被删除的条目数（--clear-dex-cache）。 */
    static long clear() {
        if (root == null) return 0;
        long n = 0;
        try {
            if (Files.isDirectory(root)) {
                List<Path> entries = listEntries();
                for (Path e : entries) deleteRecursively(e);
                n = entries.size();
            }
            // lock / trash 残留一并清掉
            try (Stream<Path> s = Files.list(root)) {
                List<Path> junk = s.filter(Files::isRegularFile).collect(Collectors.toList());
                for (Path j : junk) deleteRecursively(j);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return n;
    }

    /** 统计缓存占用，供 --clear-dex-cache 打印。 */
    static String stats() {
        if (root == null || !Files.isDirectory(root)) return "(empty)";
        try {
            List<Path> entries = listEntries();
            long bytes = 0;
            for (Path e : entries) bytes += sizeOf(e);
            return entries.size() + " entrie(s), " + (bytes / 1024) + " KB";
        } catch (Exception e) {
            return "(unreadable: " + e + ")";
        }
    }

    private static List<Path> listEntries() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
        }
    }

    /** 用 meta 的修改时间作为「最近使用时间」，命中时刷新。 */
    private static void touch(Path entry) {
        try {
            Path meta = entry.resolve(META);
            if (Files.isRegularFile(meta)) {
                Files.setLastModifiedTime(meta,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (Exception ignored) {}
    }

    private static long mtimeOf(Path entry) {
        try {
            Path meta = entry.resolve(META);
            return Files.isRegularFile(meta) ? Files.getLastModifiedTime(meta).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.size(f); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ───────────────────────────────────────────── 并发

    private static final class Lock implements AutoCloseable {
        final FileChannel channel;
        final FileLock lock;
        final boolean acquired;
        private Lock(FileChannel c, FileLock l, boolean a) {
            this.channel = c; this.lock = l; this.acquired = a;
        }
        @Override public void close() {
            try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) {}
            try { if (channel != null) channel.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 取得 single-flight 锁。多个进程构建同一 key 时只有一个真正跑 D8，
     * 其余等待并复用结果。等待超时则无锁继续（宁可重复劳动也不阻塞构建）。
     */
    private static Lock acquire(Path entry) {
        Path lockFile = root.resolve(entry.getFileName().toString() + LOCK_SUFFIX);
        FileChannel ch;
        try {
            ch = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            warn("cannot open cache lock (" + e + "); proceeding without it");
            return new Lock(null, null, false);
        }
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (true) {
            try {
                FileLock fl = ch.tryLock();
                if (fl != null) return new Lock(ch, fl, true);
            } catch (OverlappingFileLockException ignored) {
                // 同 JVM 内已持锁——不会发生，但保险起见继续等
            } catch (Exception e) {
                warn("cannot lock cache entry (" + e + "); proceeding without it");
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                warn("timed out waiting for cache lock; proceeding without it");
                break;
            }
            try {
                Thread.sleep(LOCK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Lock(ch, null, false);
    }

    // ───────────────────────────────────────────── 杂项

    /** classes.dex < classes2.dex < classes10.dex（数字后缀按数值排序）。 */
    private static final Comparator<Path> DEX_ORDER = (a, b) -> {
        String an = a.getFileName().toString(), bn = b.getFileName().toString();
        return Long.compare(dexIndex(an), dexIndex(bn));
    };

    private static long dexIndex(String name) {
        String n = name.substring("classes".length(), name.length() - ".dex".length());
        if (n.isEmpty()) return 1L;
        try { return Long.parseLong(n); } catch (NumberFormatException e) { return 0L; }
    }

    private static String token() {
        return Long.toHexString(System.nanoTime()) + "-" + SEQ.incrementAndGet();
    }

    private static boolean ensureRoot() {
        if (root == null) return false;
        try {
            Files.createDirectories(root);
            if (!Files.isWritable(root)) {
                warn("cache dir not writable: " + root + "; caching disabled for this run");
                enabled = false;
                return false;
            }
            return true;
        } catch (Exception e) {
            warn("cannot create cache dir " + root + " (" + e
                    + "); caching disabled for this run");
            enabled = false;
            return false;
        }
    }

    private static void announce() {
        if (!rootAnnounced) {
            rootAnnounced = true;
            System.out.println("Dex cache: " + root);
        }
    }

    private static void log(String s) {
        System.out.println("DexCache: " + s);
    }

    private static void warn(String s) {
        System.err.println("DexCache: warning: " + s);
    }

    static void deleteRecursively(Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static boolean isNotEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static Properties loadBuildInfo() {
        Properties p = new Properties();
        try (InputStream in = DexCache.class
                .getResourceAsStream("/j2s-build.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        return p;
    }
}