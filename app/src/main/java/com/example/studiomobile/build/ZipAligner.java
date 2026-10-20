package com.example.studiomobile.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java equivalent of {@code zipalign -p}, so no native zipalign binary is required.
 * Uncompressed (STORED) entries start at a multiple of {@code alignment}; uncompressed {@code .so}
 * entries start at a multiple of {@code pageAlignment}. The result is never trusted: callers (and
 * {@link #align}) re-read the written file and check the real offsets with {@link #findMisaligned}.
 * Must run before APK signing; any later change to the archive breaks alignment and the signature.
 */
public final class ZipAligner {
    private static final int LOCAL_HEADER_FIXED_BYTES = 30;
    private static final int PADDING_EXTRA_ID = 0xD935; // the id zipalign itself uses for padding
    private ZipAligner() { }

    public static void align(File input, File output, int alignment, int pageAlignment) throws IOException {
        if (alignment < 1 || pageAlignment < 1) throw new IllegalArgumentException("Alignment must be positive.");
        if (output.exists() && !output.delete()) throw new IOException("Could not replace " + output);
        try (ZipFile source = new ZipFile(input); CountingOutputStream counter = new CountingOutputStream(new FileOutputStream(output));
             ZipOutputStream target = new ZipOutputStream(counter)) {
            byte[] buffer = new byte[65536];
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                ZipEntry copy = new ZipEntry(original.getName());
                if (original.getTime() != -1) copy.setTime(original.getTime());
                if (original.isDirectory()) { target.putNextEntry(copy); target.closeEntry(); continue; }
                boolean stored = original.getMethod() == ZipEntry.STORED;
                if (stored) {
                    copy.setMethod(ZipEntry.STORED); copy.setSize(original.getSize()); copy.setCompressedSize(original.getSize()); copy.setCrc(original.getCrc());
                    int boundary = original.getName().endsWith(".so") ? pageAlignment : alignment;
                    int nameBytes = original.getName().getBytes(StandardCharsets.UTF_8).length;
                    long dataStart = counter.count + LOCAL_HEADER_FIXED_BYTES + nameBytes;
                    int padding = (int) ((boundary - (dataStart % boundary)) % boundary);
                    if (padding > 0 && padding < 4) padding += boundary; // an extra block needs at least a 4-byte header
                    if (padding > 0) copy.setExtra(paddingBlock(padding));
                } else copy.setMethod(ZipEntry.DEFLATED);
                target.putNextEntry(copy);
                try (InputStream in = source.getInputStream(original)) { int count; while ((count = in.read(buffer)) != -1) target.write(buffer, 0, count); }
                target.closeEntry();
            }
        }
        List<String> bad = findMisaligned(output, alignment, pageAlignment);
        if (!bad.isEmpty()) throw new IOException("Alignment verification failed for " + bad.size() + " entries, first: " + bad.get(0));
    }

    /** Reads the real local-header offsets of the file and lists STORED entries that are not aligned. */
    public static List<String> findMisaligned(File apk, int alignment, int pageAlignment) throws IOException {
        List<String> bad = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(apk, "r")) {
            long length = file.length();
            long eocd = -1;
            for (long position = length - 22; position >= Math.max(0, length - 22 - 65535); position--) {
                file.seek(position);
                if (readInt(file) == 0x06054b50) { eocd = position; break; }
            }
            if (eocd < 0) throw new IOException("Not a ZIP archive: end-of-central-directory record not found.");
            file.seek(eocd + 10); int total = readShort(file);
            file.seek(eocd + 16); long centralOffset = readInt(file) & 0xFFFFFFFFL;
            if (total == 0xFFFF) throw new IOException("ZIP64 archives are not supported.");
            long cursor = centralOffset;
            for (int index = 0; index < total; index++) {
                file.seek(cursor);
                if (readInt(file) != 0x02014b50) throw new IOException("Corrupt central directory.");
                file.seek(cursor + 10); int method = readShort(file);
                file.seek(cursor + 28); int nameLength = readShort(file), extraLength = readShort(file), commentLength = readShort(file);
                file.seek(cursor + 42); long localOffset = readInt(file) & 0xFFFFFFFFL;
                byte[] name = new byte[nameLength]; file.seek(cursor + 46); file.readFully(name);
                file.seek(localOffset + 26); int localName = readShort(file), localExtra = readShort(file);
                String entryName = new String(name, StandardCharsets.UTF_8);
                if (method == 0 && !entryName.endsWith("/")) {
                    long dataOffset = localOffset + LOCAL_HEADER_FIXED_BYTES + localName + localExtra;
                    int boundary = entryName.endsWith(".so") ? pageAlignment : alignment;
                    if (dataOffset % boundary != 0) bad.add(entryName + " @" + dataOffset);
                }
                cursor += 46 + nameLength + extraLength + commentLength;
            }
        }
        return bad;
    }

    private static byte[] paddingBlock(int totalBytes) {
        byte[] extra = new byte[totalBytes];
        extra[0] = (byte) (PADDING_EXTRA_ID & 0xFF); extra[1] = (byte) (PADDING_EXTRA_ID >> 8);
        int payload = totalBytes - 4; extra[2] = (byte) (payload & 0xFF); extra[3] = (byte) (payload >> 8);
        return extra;
    }
    private static int readInt(RandomAccessFile file) throws IOException { return Integer.reverseBytes(file.readInt()); }
    private static int readShort(RandomAccessFile file) throws IOException { return Short.reverseBytes(file.readShort()) & 0xFFFF; }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); count += length; }
    }
}
