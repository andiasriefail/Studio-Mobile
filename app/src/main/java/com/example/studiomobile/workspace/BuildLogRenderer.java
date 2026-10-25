package com.example.studiomobile.workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays the Build log out as text. Every line, wrapped or not, starts its text to the right of one straight vertical rule, like the margin
 * line of a notebook, so nothing ever drifts back toward the time stamps:
 *
 * <pre>
 * [00:03] | Preparing libraries…  (00:00)
 * [00:03] |   Checking cached libraries  (00:00)
 * [00:22] | Warning: a long message wraps and
 *         | keeps its text right of the rule
 * </pre>
 *
 * Pure text code with no Android classes, so it can be tested on its own.
 */
final class BuildLogRenderer {
    private BuildLogRenderer() { }

    static final int STEP = 0, DETAIL = 1, MESSAGE = 2, HEARTBEAT = 3;
    /** The margin rule that sits between the time stamp column and the text. */
    static final String RULE = "| ";

    static final class Entry {
        final int kind;
        String text;           // only a heartbeat is rewritten in place
        final long at;
        /** False for the continuation lines of a multi-line message: they get no time stamp. */
        final boolean stamped;
        Entry(int kind, String text, long at, boolean stamped) { this.kind = kind; this.text = text; this.at = at; this.stamped = stamped; }
    }

    /**
     * The whole log: one entry per step or detail as "[time since start] | text  (how long it took)". The running step shows its time so far.
     * With [columns] greater than 0 a long entry is wrapped at that width and every following line starts under the first letter of the
     * text. A detail that repeats inside one step (six "Processing resources" tasks) is shown once with its count and the total time.
     */
    static String render(List<Entry> entries, long start, long end, int columns) {
        int size = entries.size();
        // Every time stamp is padded to the widest one (a build over an hour has "[1:00:00]"), so the text column never moves.
        int stampWidth = 0;
        for (Entry e : entries) if (e.stamped) stampWidth = Math.max(stampWidth, clock(e.at - start).length() + 2);
        if (stampWidth == 0) stampWidth = 7;
        String ruled = spaces(stampWidth + 1) + RULE;

        boolean[] hidden = new boolean[size];
        int[] count = new int[size];
        long[] total = new long[size];
        Map<String, Integer> firstOf = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Entry e = entries.get(i);
            if (e.kind == STEP) { firstOf.clear(); continue; }
            if (e.kind != DETAIL) continue;
            long took = nextStart(entries, i, end) - e.at;
            Integer first = firstOf.get(e.text);
            if (first == null) { firstOf.put(e.text, i); count[i] = 1; total[i] = took; }
            else { hidden[i] = true; count[first]++; total[first] += took; }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (hidden[i]) continue;
            Entry e = entries.get(i);
            if (out.length() > 0) out.append('\n');
            String first;
            if (e.stamped) {
                String stamp = "[" + clock(e.at - start) + "]";
                first = stamp + spaces(stampWidth + 1 - stamp.length()) + RULE;
            } else first = ruled;
            String text = e.text;
            long took = nextStart(entries, i, end) - e.at;
            if (e.kind == DETAIL) { text = "  " + text + (count[i] > 1 ? " · " + count[i] + " parts" : ""); took = total[i]; }
            else if (e.kind == HEARTBEAT) text = "    " + text;
            String duration = e.stamped && e.kind != MESSAGE && e.kind != HEARTBEAT ? "(" + clock(took) + ")" : "";
            out.append(wrapEntry(first, ruled, text, duration, columns));
        }
        return out.toString();
    }

    /** A step lasts until the next step starts; a detail lasts until the next step or detail starts. Falls back to the end time. */
    static long nextStart(List<Entry> entries, int index, long fallback) {
        int kind = entries.get(index).kind;
        for (int j = index + 1; j < entries.size(); j++) {
            int other = entries.get(j).kind;
            if (other == STEP || (other == DETAIL && kind == DETAIL)) return entries.get(j).at;
        }
        return fallback;
    }

    /** "Time per part: Starting the build 01:50 · Processing resources 00:44 · ..." for the end of the log, or "" when it adds nothing. */
    static String phaseSummary(List<Entry> entries, long end) {
        StringBuilder out = new StringBuilder();
        int parts = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.kind != STEP) continue;
            long took = nextStart(entries, i, end) - e.at;
            if (took < 2000) continue;
            String name = e.text.endsWith("…") ? e.text.substring(0, e.text.length() - 1) : e.text;
            if (parts++ > 0) out.append(" · ");
            out.append(name).append(' ').append(clock(took));
        }
        return parts < 2 ? "" : "Time per part: " + out;
    }

    static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String spaces(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(' ');
        return out.toString();
    }

    /**
     * One entry as text. [first] starts the first line (the padded time stamp), [indent] starts every other line. Text that begins
     * with spaces (details, stack traces) keeps that indentation on the lines it wraps into. Breaks happen at spaces; a word longer
     * than the line is cut. The duration stays on the last line when it fits, otherwise it gets a line of its own under the text.
     */
    static String wrapEntry(String first, String indent, String text, String duration, int columns) {
        String tail = duration.isEmpty() ? "" : "  " + duration;
        int width = columns - indent.length();
        if (columns <= 0 || width < 12) return first + text + tail;
        int lead = 0;
        while (lead < text.length() && text.charAt(lead) == ' ') lead++;
        String carry = spaces(Math.min(lead, width / 2));
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (true) {
            String prefix = lines.isEmpty() ? "" : carry;
            int room = width - prefix.length();
            if (rest.length() <= room) { lines.add(prefix + rest); break; }
            int cut = rest.lastIndexOf(' ', room);
            int skip;
            boolean hard = cut <= (lines.isEmpty() ? lead : 0);   // one long word: cut it
            if (!hard && cut < room / 2) {
                // a short beginning ("at") followed by a word longer than the line: fill the line instead of leaving it almost empty
                int wordStart = cut;
                while (wordStart < rest.length() && rest.charAt(wordStart) == ' ') wordStart++;
                int wordEnd = rest.indexOf(' ', wordStart);
                if ((wordEnd < 0 ? rest.length() : wordEnd) - wordStart > room) hard = true;
            }
            if (hard) {
                cut = room;
                int dot = rest.lastIndexOf('.', room - 1);   // prefer to break after a dot (package and file names)
                if (dot > room - 18 && dot > lead) cut = dot + 1;
                skip = cut;
            } else {
                skip = cut;
                while (skip < rest.length() && rest.charAt(skip) == ' ') skip++;
                while (cut > 0 && rest.charAt(cut - 1) == ' ') cut--;                // no trailing spaces
            }
            lines.add(prefix + rest.substring(0, cut));
            rest = rest.substring(skip);
            if (rest.isEmpty()) break;
        }
        if (!tail.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            if (last.length() + tail.length() <= width) lines.set(lines.size() - 1, last + tail);
            else lines.add(duration);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(i == 0 ? first : indent).append(lines.get(i));
        }
        return out.toString();
    }
}
