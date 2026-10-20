package com.example.studiomobile.workspace;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells the screen that the AI (through MCP) changed a project on disk, so editor, structure and preview can follow in real time.
 * Listeners are called on the thread that made the change and must only post work elsewhere.
 */
public final class ProjectChangeBus {

    public interface Listener {
        /** @param structure true when files or folders were created, copied, moved or deleted (the file tree must be redrawn) */
        void onProjectChanged(File project, boolean structure);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ProjectChangeBus() { }

    public static void addListener(Listener listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    public static void changed(File project, boolean structure) {
        for (Listener listener : LISTENERS) {
            try { listener.onProjectChanged(project, structure); } catch (RuntimeException ignored) { /* one bad listener must not break the tool call */ }
        }
    }
}
