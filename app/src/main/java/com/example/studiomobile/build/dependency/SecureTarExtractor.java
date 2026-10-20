package com.example.studiomobile.build.dependency;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Extracts a verified {@code .tar}, {@code .tar.gz} or {@code .tar.xz} (for example an Android-host NDK) with path-traversal,
 * entry-count and total-size protection. Understands ustar, GNU long names and pax headers, directories, regular files, symbolic
 * links and hard links. Links may never point outside the destination. The executable bit of the archive is preserved.
 *
 * <p>Decompression of {@code .xz} uses {@code org.tukaani.xz}, loaded by name so the tar logic itself stays testable on a plain JVM.
 */
public final class SecureTarExtractor {
    private static final int MAX_ENTRIES = 400000;
    private static final int BLOCK = 512;
    private static final int MAX_HEADER_TEXT = 1 << 20;
    private SecureTarExtractor() { }

    /**
     * @param archive         .tar, .tar.gz/.tgz or .tar.xz file (detected from the magic bytes, not the file name)
     * @param stripComponents number of leading path segments to drop (1 removes {@code android-ndk-r29/})
     * @param filter          receives the path after stripping (never null, no leading slash); entries it rejects are skipped. May be null.
     * @param maxTotalBytes   upper bound on the uncompressed size, protecting against decompression bombs
     * @return the number of files written
     */
    public static int extract(File archive, File destination, int stripComponents, Predicate<String> filter, long maxTotalBytes) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), 1 << 16)) {
            return extract(decompress(raw), destination, stripComponents, filter, maxTotalBytes);
        }
    }

    /** Reads an already decompressed tar stream. */
    public static int extract(InputStream tar, File destination, int stripComponents, Predicate<String> filter, long maxTotalBytes) throws IOException {
        File root = destination.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create extraction directory: " + root);
        String rootPrefix = root.getPath() + File.separator;
        byte[] header = new byte[BLOCK];
        String longName = null, longLink = null, paxPath = null, paxLink = null;
        long paxSize = -1, total = 0; int entries = 0, written = 0;
        while (true) {
            if (!readBlock(tar, header)) break;
            if (isZeroBlock(header)) break;
            if (++entries > MAX_ENTRIES) throw new IOException("Archive has too many entries.");
            checkChecksum(header);
            char type = (char) (header[156] & 0xff);
            long size = parseNumber(header, 124, 12);
            if (size < 0) throw new IOException("Corrupt tar header (negative size).");
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                if (size > MAX_HEADER_TEXT) throw new IOException("Tar metadata entry is too large.");
                byte[] data = new byte[(int) size];
                readFully(tar, data); skipPadding(tar, size);
                if (type == 'L') longName = cString(data, 0, data.length);
                else if (type == 'K') longLink = cString(data, 0, data.length);
                else if (type == 'x') {
                    String[] values = parsePax(data);
                    if (values[0] != null) paxPath = values[0];
                    if (values[1] != null) paxLink = values[1];
                    if (values[2] != null) paxSize = Long.parseLong(values[2]);
                } // 'g' (global pax) is ignored
                continue;
            }
            String name = paxPath != null ? paxPath : longName != null ? longName : fullName(header);
            String link = paxLink != null ? paxLink : longLink != null ? longLink : cString(header, 157, 100);
            if (paxSize >= 0) size = paxSize;
            longName = longLink = paxPath = paxLink = null; paxSize = -1;

            long padded = size;
            boolean hasData = type == '0' || type == 0 || type == '7';
            String relative = strip(name.replace('\\', '/'), stripComponents);
            boolean wanted = relative != null && !relative.isEmpty() && (filter == null || filter.test(relative));
            if (!wanted || !(hasData || type == '5' || type == '2' || type == '1')) { skipData(tar, size); continue; }
            File target = safeChild(root, rootPrefix, relative, name);
            int mode = (int) parseNumber(header, 100, 8);
            if (type == '5') { if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create " + target); skipData(tar, padded); continue; }
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
            if (type == '2') {
                String linkRelative = link.replace('\\', '/');
                Path resolved = (linkRelative.startsWith("/") ? root.toPath().resolve(linkRelative.substring(1)) : parent.toPath().resolve(linkRelative)).normalize();
                if (!resolved.toString().startsWith(rootPrefix) && !resolved.equals(root.toPath())) throw new IOException("Symbolic link escapes destination: " + name + " -> " + link);
                deleteIfPresent(target);
                Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link));
                continue;
            }
            if (type == '1') {
                String sourceRelative = strip(link.replace('\\', '/'), stripComponents);
                if (sourceRelative == null) throw new IOException("Hard link points outside the archive layout: " + link);
                File source = safeChild(root, rootPrefix, sourceRelative, link);
                if (!source.isFile()) throw new IOException("Hard link target was not extracted: " + link + " (needed by " + name + ")");
                deleteIfPresent(target);
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (source.canExecute()) target.setExecutable(true, false);
                written++;
                continue;
            }
            deleteIfPresent(target);
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 1 << 16)) {
                long remaining = size; byte[] buffer = new byte[1 << 16];
                while (remaining > 0) {
                    int read = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new EOFException("Archive ended in the middle of " + name);
                    total += read; remaining -= read;
                    if (total > maxTotalBytes) throw new IOException("Archive expands beyond the permitted size.");
                    out.write(buffer, 0, read);
                }
            }
            skipPadding(tar, size);
            if ((mode & 0111) != 0) target.setExecutable(true, false);
            written++;
        }
        return written;
    }

    // ------------------------------------------------------------------ decompression

    private static InputStream decompress(InputStream in) throws IOException {
        in.mark(8);
        int a = in.read(), b = in.read(), c = in.read(), d = in.read(), e = in.read(), f = in.read();
        in.reset();
        if (a == 0xFD && b == '7' && c == 'z' && d == 'X' && e == 'Z' && f == 0) return newXzStream(in);
        if (a == 0x1f && b == 0x8b) return new BufferedInputStream(new GZIPInputStream(in, 1 << 16), 1 << 16);
        return in; // plain tar
    }

    private static InputStream newXzStream(InputStream in) throws IOException {
        try {
            Class<?> type = Class.forName("org.tukaani.xz.XZInputStream");
            InputStream xz = (InputStream) type.getConstructor(InputStream.class).newInstance(in);
            return new BufferedInputStream(xz, 1 << 16);
        } catch (ReflectiveOperationException error) {
            throw new IOException("XZ support (org.tukaani:xz) is not available in this build: " + error, error);
        }
    }

    // ------------------------------------------------------------------ tar format helpers

    private static File safeChild(File root, String rootPrefix, String relative, String original) throws IOException {
        String clean = relative;
        while (clean.startsWith("/")) clean = clean.substring(1);
        for (String segment : clean.split("/")) if (segment.equals("..")) throw new IOException("Archive entry escapes destination: " + original);
        File target = new File(root, clean);
        // Do not follow a symlink that an earlier entry planted: check the parent chain with the canonical path.
        File canonicalParent = target.getParentFile().getCanonicalFile();
        if (!(canonicalParent.getPath() + File.separator).startsWith(rootPrefix) && !canonicalParent.equals(root)) throw new IOException("Archive entry escapes destination: " + original);
        return new File(canonicalParent, target.getName());
    }

    private static void deleteIfPresent(File target) throws IOException {
        if (Files.isSymbolicLink(target.toPath()) || target.isFile()) Files.delete(target.toPath());
        else if (target.isDirectory()) throw new IOException("A directory already exists where a file is expected: " + target);
    }

    private static String strip(String name, int components) {
        String value = name;
        while (value.startsWith("./")) value = value.substring(2);
        while (value.startsWith("/")) value = value.substring(1);
        for (int i = 0; i < components; i++) {
            int slash = value.indexOf('/');
            if (slash < 0) return null;
            value = value.substring(slash + 1);
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String fullName(byte[] header) {
        String name = cString(header, 0, 100);
        boolean ustar = new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
        if (!ustar) return name;
        String prefix = cString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String[] parsePax(byte[] data) throws IOException {
        String[] result = new String[3];
        int position = 0;
        while (position < data.length) {
            int space = position;
            while (space < data.length && data[space] != ' ') space++;
            int length;
            try { length = Integer.parseInt(new String(data, position, space - position, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException error) { throw new IOException("Corrupt pax header."); }
            if (length <= 0 || position + length > data.length) throw new IOException("Corrupt pax header.");
            String record = new String(data, space + 1, position + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals > 0) {
                String key = record.substring(0, equals), value = record.substring(equals + 1);
                if (key.equals("path")) result[0] = value; else if (key.equals("linkpath")) result[1] = value; else if (key.equals("size")) result[2] = value;
            }
            position += length;
        }
        return result;
    }

    private static long parseNumber(byte[] block, int offset, int length) throws IOException {
        if ((block[offset] & 0x80) != 0) { // GNU base-256
            long value = block[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = (value << 8) | (block[offset + i] & 0xff);
            return value;
        }
        long value = 0; int i = offset, end = offset + length;
        while (i < end && (block[i] == ' ' || block[i] == 0)) i++;
        for (; i < end; i++) {
            byte b = block[i];
            if (b == ' ' || b == 0) break;
            if (b < '0' || b > '7') throw new IOException("Corrupt tar header (bad octal number).");
            value = (value << 3) | (b - '0');
        }
        return value;
    }

    private static void checkChecksum(byte[] header) throws IOException {
        long expected = parseNumber(header, 148, 8), sum = 0;
        for (int i = 0; i < BLOCK; i++) sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
        if (sum != expected) throw new IOException("Corrupt tar header (checksum mismatch). The file may not be a tar archive.");
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] block) { for (byte b : block) if (b != 0) return false; return true; }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int done = 0;
        while (done < block.length) {
            int read = in.read(block, done, block.length - done);
            if (read < 0) { if (done == 0) return false; throw new EOFException("Truncated tar archive."); }
            done += read;
        }
        return true;
    }

    private static void readFully(InputStream in, byte[] data) throws IOException {
        int done = 0;
        while (done < data.length) { int read = in.read(data, done, data.length - done); if (read < 0) throw new EOFException("Truncated tar archive."); done += read; }
    }

    private static void skipPadding(InputStream in, long size) throws IOException { skipFully(in, (BLOCK - (size % BLOCK)) % BLOCK); }
    private static void skipData(InputStream in, long size) throws IOException { skipFully(in, size + (BLOCK - (size % BLOCK)) % BLOCK); }

    private static void skipFully(InputStream in, long count) throws IOException {
        byte[] scratch = null;
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (scratch == null) scratch = new byte[1 << 14];
                int read = in.read(scratch, 0, (int) Math.min(scratch.length, count));
                if (read < 0) throw new EOFException("Truncated tar archive.");
                skipped = read;
            }
            count -= skipped;
        }
    }

    /** True for names such as {@code foo.tar.xz}, {@code foo.tar.gz}, {@code foo.tgz}, {@code foo.tar}. */
    public static boolean looksLikeTar(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.xz") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".txz");
    }
}
