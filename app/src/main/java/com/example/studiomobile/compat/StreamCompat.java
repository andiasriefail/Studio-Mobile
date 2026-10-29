package com.example.studiomobile.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Stand-ins for the java.io.InputStream methods that were added in Java 9 and that Android only has from API 33:
 * readAllBytes(), readNBytes(int), readNBytes(byte[], int, int) and transferTo(OutputStream).
 *
 * ECJ 3.46 calls them (for example Util.getInputStreamAsByteArray while it reads a jar manifest), so on a phone below Android 13 the
 * in-process Java compiler failed with "NoSuchMethodError: No virtual method readAllBytes()[B in class Ljava/io/InputStream;".
 * The Gradle task patchEcj (app/build.gradle) rewrites every such call inside the ECJ jar into a call to the matching static method here;
 * the receiver stream becomes the first argument. The behaviour follows the JDK documentation, so the shims are also safe on API 33+.
 * Like {@link RuntimeVersion} this class only exists inside the Studio Mobile app; it never ends up in the APKs that Studio Mobile builds.
 */
public final class StreamCompat {
    private static final int BUFFER_SIZE = 8192;

    private StreamCompat() { }

    /** Replacement for InputStream.readAllBytes(). */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return readNBytes(in, Integer.MAX_VALUE);
    }

    /** Replacement for InputStream.readNBytes(int). */
    public static byte[] readNBytes(InputStream in, int len) throws IOException {
        Objects.requireNonNull(in, "in");
        if (len < 0) throw new IllegalArgumentException("len < 0");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(len, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int remaining = len;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    /** Replacement for InputStream.readNBytes(byte[], int, int). */
    public static int readNBytes(InputStream in, byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(b, "b");
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + b.length);
        int total = 0;
        while (total < len) {
            int read = in.read(b, off + total, len - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    /** Replacement for InputStream.transferTo(OutputStream). */
    public static long transferTo(InputStream in, OutputStream out) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        byte[] buffer = new byte[BUFFER_SIZE];
        long transferred = 0;
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }
}
