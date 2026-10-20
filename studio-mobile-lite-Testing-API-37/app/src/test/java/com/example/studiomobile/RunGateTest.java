package com.example.studiomobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunGateTest {

    @Test public void theFirstRunWinsAndTheSecondIsRefused() {
        RunGate gate = new RunGate();
        RunGate.Ticket first = gate.tryAcquire();
        assertNotNull(first);
        assertTrue(gate.isBusy());
        assertNull(gate.tryAcquire());
    }

    @Test public void releasingFreesTheGateAndCanBeRepeated() {
        RunGate gate = new RunGate();
        RunGate.Ticket first = gate.tryAcquire();
        first.release();
        first.release();
        assertFalse(gate.isBusy());
        assertNotNull(gate.tryAcquire());
    }

    @Test public void aStaleTicketCannotFreeTheNextRun() {
        RunGate gate = new RunGate();
        RunGate.Ticket old = gate.tryAcquire();
        old.release();
        RunGate.Ticket current = gate.tryAcquire();
        old.release();
        assertTrue(gate.isBusy());
        assertNull(gate.tryAcquire());
        current.release();
        assertFalse(gate.isBusy());
    }

    @Test public void onlyOneOfManyThreadsGetsTheGate() throws Exception {
        RunGate gate = new RunGate();
        int[] winners = { 0 };
        Thread[] threads = new Thread[50];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { if (gate.tryAcquire() != null) synchronized (winners) { winners[0]++; } });
            threads[i].start();
        }
        for (Thread thread : threads) thread.join();
        org.junit.Assert.assertEquals(1, winners[0]);
    }
}
