package com.example.studiomobile.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts attempts to leave the project folder. When the AI sends the same escaping path twice, the trip
 * action runs once (the relay service uses it to reconnect to the server URL) and the counters start over.
 */
public final class PathViolationGuard {

    public static final int LIMIT = 2;
    private static final int MAX_TRACKED = 64;

    private final Runnable onTrip;
    private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) { return size() > MAX_TRACKED; }
    };

    public PathViolationGuard(Runnable onTrip) { this.onTrip = onTrip; }

    /** Records one blocked attempt. Returns true when this attempt trips the guard. */
    public boolean record(String project, String path) {
        String key = (project == null ? "" : project.trim()) + "|" + (path == null ? "" : path.trim());
        boolean tripped;
        synchronized (this) {
            int count = counts.containsKey(key) ? counts.get(key) + 1 : 1;
            counts.put(key, count);
            tripped = count >= LIMIT;
            if (tripped) counts.clear();
        }
        if (tripped && onTrip != null) onTrip.run();
        return tripped;
    }
}
