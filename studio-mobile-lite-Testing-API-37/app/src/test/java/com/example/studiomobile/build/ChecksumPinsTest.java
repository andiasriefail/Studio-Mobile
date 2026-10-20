package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.ChecksumFailure;
import com.example.studiomobile.build.dependency.ChecksumPins;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ChecksumPinsTest {
    private static final String HASH = "2f5bec7a83d88881dec0e82c8aec3f30610900b2fafd806e2fee26dd132b47de";

    private static ChecksumPins load(String text) throws IOException {
        File project = Files.createTempDirectory("pins").toFile();
        File file = new File(project, ChecksumPins.FILE);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return ChecksumPins.load(project);
    }
    private static void invalid(String text, String expectedPart) throws IOException {
        try { load(text); fail("expected a ChecksumFailure"); }
        catch (ChecksumFailure failure) { assertEquals(ChecksumFailure.Kind.PIN_FILE_INVALID, failure.kind()); assertTrue(failure.getMessage(), failure.getMessage().contains(expectedPart)); }
    }

    @Test public void missingFileMeansNoPins() throws IOException {
        assertTrue(ChecksumPins.load(Files.createTempDirectory("nopins").toFile()).isEmpty());
    }

    @Test public void readsLinesCommentsAndPrefix() throws IOException {
        ChecksumPins pins = load("# from the release page\n\ncom.github.User:Repo:1.0 = " + HASH.toUpperCase() + "  # jar\nlib:b:2=sha256:" + HASH + "\n");
        assertEquals(2, pins.size());
        assertEquals(HASH, pins.get("com.github.User:Repo:1.0"));
        assertEquals(HASH, pins.get("lib:b:2"));
        assertNull(pins.get("lib:other:1"));
    }

    @Test public void badLinesAreRejectedWithTheLineNumber() throws IOException {
        invalid("com.github.User:Repo:1.0\n", "line 1");
        invalid("\nlib:a=" + HASH + "\n", "line 2");
        invalid("lib:a:1=abc123\n", "64 hexadecimal");
        invalid("lib:a:1=" + HASH + "\nlib:a:1=" + HASH.replace('2', '3') + "\n", "twice");
    }
}
