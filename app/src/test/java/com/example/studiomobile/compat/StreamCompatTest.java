package com.example.studiomobile.compat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class StreamCompatTest {
    private static byte[] sample(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + 7);
        return data;
    }

    /** Returns one byte per read call, like a slow network or zip stream. */
    private static InputStream trickle(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
        };
    }

    @Test public void readAllBytesReadsEverythingAcrossBufferBoundaries() throws IOException {
        for (int size : new int[] {0, 1, 8191, 8192, 8193, 50_000}) {
            byte[] data = sample(size);
            assertArrayEquals(data, StreamCompat.readAllBytes(new ByteArrayInputStream(data)));
            assertArrayEquals(data, StreamCompat.readAllBytes(trickle(data)));
        }
    }

    @Test public void readNBytesStopsAtTheRequestedLength() throws IOException {
        byte[] data = sample(100);
        InputStream in = new ByteArrayInputStream(data);
        byte[] first = StreamCompat.readNBytes(in, 40);
        assertEquals(40, first.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 0, 40), first);
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 40, 100), StreamCompat.readNBytes(in, 1000));
    }

    @Test public void readNBytesIntoArrayFillsAsMuchAsAvailable() throws IOException {
        byte[] data = sample(10);
        byte[] target = new byte[20];
        assertEquals(10, StreamCompat.readNBytes(trickle(data), target, 5, 15));
        assertArrayEquals(data, java.util.Arrays.copyOfRange(target, 5, 15));
        assertEquals(0, target[0]);
    }

    @Test(expected = IndexOutOfBoundsException.class) public void readNBytesIntoArrayChecksBounds() throws IOException {
        StreamCompat.readNBytes(new ByteArrayInputStream(new byte[4]), new byte[4], 2, 3);
    }

    @Test public void transferToCopiesAndCounts() throws IOException {
        byte[] data = sample(30_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(30_000, StreamCompat.transferTo(trickle(data), out));
        assertArrayEquals(data, out.toByteArray());
    }
}
