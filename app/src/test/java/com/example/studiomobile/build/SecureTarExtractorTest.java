package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.SecureTarExtractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SecureTarExtractorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void extractsFilesStripsTopLevelAndAppliesFilter() throws Exception {
        byte[] tar = tar(entry("ndk/bin/clang", "ELF", '0', 0755, ""), entry("ndk/sysroot/usr/include/stdio.h", "#include", '0', 0644, ""), entry("ndk/docs/readme.txt", "x", '0', 0644, ""));
        File out = temporary.newFolder();
        int written = SecureTarExtractor.extract(new ByteArrayInputStream(tar), out, 1, path -> !path.startsWith("docs"), 1 << 20);
        assertEquals(2, written);
        assertTrue(new File(out, "bin/clang").canExecute());
        assertEquals("#include", new String(Files.readAllBytes(new File(out, "sysroot/usr/include/stdio.h").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(out, "docs").exists());
    }

    @Test public void rejectsPathTraversal() throws Exception {
        byte[] tar = tar(entry("ndk/../../escape.txt", "x", '0', 0644, ""));
        try { SecureTarExtractor.extract(new ByteArrayInputStream(tar), temporary.newFolder(), 1, null, 1 << 20); fail("expected IOException"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("escapes")); }
    }

    @Test public void rejectsSymlinkOutsideDestination() throws Exception {
        byte[] tar = tar(entry("ndk/link", "", '2', 0777, "../../outside"));
        try { SecureTarExtractor.extract(new ByteArrayInputStream(tar), temporary.newFolder(), 1, null, 1 << 20); fail("expected IOException"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Symbolic link")); }
    }

    @Test public void enforcesTotalSizeLimit() throws Exception {
        byte[] tar = tar(entry("ndk/big.bin", new String(new char[2000]).replace('\0', 'a'), '0', 0644, ""));
        try { SecureTarExtractor.extract(new ByteArrayInputStream(tar), temporary.newFolder(), 1, null, 100); fail("expected IOException"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("permitted size")); }
    }

    // ---- minimal ustar writer -------------------------------------------------------------------------------------------------------

    private static byte[] entry(String name, String content, char type, int mode, String link) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        put(header, 0, name, 100); put(header, 100, String.format("%07o", mode), 8); put(header, 108, "0000000", 8); put(header, 116, "0000000", 8);
        put(header, 124, String.format("%011o", type == '0' ? data.length : 0), 12); put(header, 136, "00000000000", 12);
        header[156] = (byte) type; put(header, 157, link, 100); put(header, 257, "ustar", 6); header[263] = '0'; header[264] = '0';
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long sum = 0; for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum), 7); header[154] = 0; header[155] = ' ';
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        if (type == '0') { out.write(data); out.write(new byte[(512 - data.length % 512) % 512]); }
        return out.toByteArray();
    }

    private static byte[] tar(byte[]... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] entry : entries) out.write(entry);
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] block, int offset, String text, int max) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, max));
    }
}
