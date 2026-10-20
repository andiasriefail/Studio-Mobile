package com.example.studiomobile.build.dependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * How much memory a library download may use, chosen from the RAM of the phone it runs on.
 *
 * <pre>
 *   phone RAM   memory limit   chunk size
 *   4 GB        150 MB          4 MB
 *   6 GB        250 MB          8 MB
 *   8 GB        350 MB         16 MB
 *   12 GB+      500 MB         24 MB
 * </pre>
 *
 * The limit is the most a download may hold in memory at once. Files are never read as a whole: they are streamed to disk chunk by
 * chunk (HTTP range requests when the server supports them), and the chunk is sized so that several buffers always fit far inside
 * the limit. A phone reports a little less than its nominal RAM in /proc/meminfo (a "4 GB" phone shows about 3.7 GB), so the tiers
 * switch at 5, 7 and 10.5 GiB of reported RAM (a "12 GB" phone shows about 11 GB). Anything below 5 GiB gets the smallest tier, and a phone whose RAM cannot be read does too.
 */
public final class DownloadMemoryPolicy {
    private static final long MB = 1024L * 1024, GIB = 1024L * MB;
    /** Chunk is never more than 1/16 of the limit, so even the smallest limit holds many chunks. */
    private static final int CHUNK_DIVISOR = 16;

    public static final DownloadMemoryPolicy LOW = new DownloadMemoryPolicy(4 * GIB, 150 * MB, 4 * MB);
    public static final DownloadMemoryPolicy MEDIUM = new DownloadMemoryPolicy(6 * GIB, 250 * MB, 8 * MB);
    public static final DownloadMemoryPolicy HIGH = new DownloadMemoryPolicy(8 * GIB, 350 * MB, 16 * MB);
    public static final DownloadMemoryPolicy MAX = new DownloadMemoryPolicy(12 * GIB, 500 * MB, 24 * MB);

    private static volatile DownloadMemoryPolicy detected;

    private final long tierRamBytes, memoryLimitBytes, chunkBytes;

    private DownloadMemoryPolicy(long tierRamBytes, long memoryLimitBytes, long chunkBytes) {
        this.tierRamBytes = tierRamBytes;
        this.memoryLimitBytes = memoryLimitBytes;
        this.chunkBytes = Math.max(64 * 1024, Math.min(chunkBytes, memoryLimitBytes / CHUNK_DIVISOR));
    }

    /** The tier for a phone with this much RAM, as reported by the kernel. */
    public static DownloadMemoryPolicy forRam(long totalRamBytes) {
        if (totalRamBytes >= 10.5 * GIB) return MAX;
        if (totalRamBytes >= 7 * GIB) return HIGH;
        if (totalRamBytes >= 5 * GIB) return MEDIUM;
        return LOW;
    }

    /** Same, but never lets the limit grow past what the app's own heap can really hold. */
    public static DownloadMemoryPolicy forRam(long totalRamBytes, long maxHeapBytes) {
        DownloadMemoryPolicy tier = forRam(totalRamBytes);
        long heapCeiling = maxHeapBytes / 4 * 3;
        if (heapCeiling <= 0 || heapCeiling >= tier.memoryLimitBytes) return tier;
        return new DownloadMemoryPolicy(tier.tierRamBytes, Math.max(16 * MB, heapCeiling), tier.chunkBytes);
    }

    /** Reads the RAM of this device once ({@code /proc/meminfo}) and remembers the answer. */
    public static DownloadMemoryPolicy detect() {
        DownloadMemoryPolicy local = detected;
        if (local == null) {
            local = forRam(readTotalRamBytes(new File("/proc/meminfo")), Runtime.getRuntime().maxMemory());
            detected = local;
        }
        return local;
    }

    /** Total RAM in bytes from a meminfo file, or 0 when it cannot be read. Package-private for tests. */
    static long readTotalRamBytes(File meminfo) {
        try (BufferedReader reader = new BufferedReader(new FileReader(meminfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemTotal:")) continue;
                String digits = line.replaceAll("[^0-9]", "");
                return digits.isEmpty() ? 0 : Long.parseLong(digits) * 1024L;
            }
        } catch (IOException | NumberFormatException unreadable) { /* fall through: smallest tier */ }
        return 0;
    }

    /** The most one download may keep in memory. Also the cap for any file fetched as a whole (POM, checksum, metadata). */
    public long getMemoryLimitBytes() { return memoryLimitBytes; }
    /** Size of one download chunk: one range request, one read buffer, one flush to disk. */
    public int getChunkBytes() { return (int) chunkBytes; }

    /** Largest file that may be held in memory in one piece. */
    public long wholeFileLimit(long requestedMax) { return Math.min(requestedMax, memoryLimitBytes); }

    @Override public String toString() {
        return String.format(Locale.ROOT, "download memory limit %d MB, chunk %d MB", memoryLimitBytes / MB, chunkBytes / MB);
    }
}
