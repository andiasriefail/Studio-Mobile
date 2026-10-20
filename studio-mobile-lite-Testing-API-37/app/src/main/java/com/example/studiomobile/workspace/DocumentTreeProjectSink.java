package com.example.studiomobile.workspace;

import android.content.ContentResolver;
import android.webkit.MimeTypeMap;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Writes extracted ZIP entries into a Storage Access Framework folder. */
public final class DocumentTreeProjectSink implements ProjectZipImporter.Sink {
    private final ContentResolver resolver;
    private final DocumentFile root;
    private final Map<String, DocumentFile> directories = new HashMap<>();

    public DocumentTreeProjectSink(ContentResolver resolver, DocumentFile root) {
        this.resolver = resolver;
        this.root = root;
        directories.put("", root);
    }

    @Override public void ensureDirectory(String relativePath) throws IOException { directory(relativePath); }

    @Override public OutputStream openFile(String relativePath) throws IOException {
        int slash = relativePath.lastIndexOf('/');
        DocumentFile parent = directory(slash < 0 ? "" : relativePath.substring(0, slash));
        String name = relativePath.substring(slash + 1);
        DocumentFile file = parent.findFile(name);
        if (file == null || file.isDirectory()) {
            file = parent.createFile(mimeTypeFor(name), name);
            if (file == null) throw new IOException("Could not create file: " + relativePath);
            // Some providers append an extension derived from the MIME type (Foo.kt -> Foo.kt.txt); restore the exact name.
            if (!name.equals(file.getName()) && !file.renameTo(name)) throw new IOException("The storage provider renamed " + name + " to " + file.getName());
        }
        OutputStream out = resolver.openOutputStream(file.getUri(), "wt");
        if (out == null) throw new IOException("Could not open for writing: " + relativePath);
        return out;
    }

    private DocumentFile directory(String relativePath) throws IOException {
        DocumentFile cached = directories.get(relativePath);
        if (cached != null) return cached;
        int slash = relativePath.lastIndexOf('/');
        DocumentFile parent = directory(slash < 0 ? "" : relativePath.substring(0, slash));
        String name = relativePath.substring(slash + 1);
        DocumentFile child = parent.findFile(name);
        if (child == null) child = parent.createDirectory(name);
        if (child == null || !child.isDirectory()) throw new IOException("Could not create folder: " + relativePath);
        directories.put(relativePath, child);
        return child;
    }

    /** Uses a neutral binary type for names without a known MIME type so providers do not rewrite the extension. */
    private static String mimeTypeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }
}
