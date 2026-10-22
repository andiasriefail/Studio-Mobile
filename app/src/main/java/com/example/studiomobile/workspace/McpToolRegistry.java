package com.example.studiomobile.workspace;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The tools an AI client can call. Each tool is described once (name, description, input schema) and
 * executed here. Tools that change files or start builds are hidden and refused in read-only mode.
 */
public final class McpToolRegistry {

    private static final int MAX_LOG_CHARS = 50_000;
    /** Kept below common proxy and client timeouts so one wait never fails by timeout. */
    private static final int MAX_WAIT_SECONDS = 40;
    private static final int ERROR_LINES = 25;
    /** check_syntax reads one source file; anything larger is not a hand-written source file. */
    private static final long MAX_SYNTAX_FILE_BYTES = 1024 * 1024;

    private static final class ToolSpec {
        final String name;
        final String title;
        final String description;
        final boolean changesThings;
        final boolean destructive;
        final JSONObject inputSchema;

        ToolSpec(String name, String title, String description, boolean changesThings, boolean destructive, JSONObject inputSchema) {
            this.name = name;
            this.title = title;
            this.description = description;
            this.changesThings = changesThings;
            this.destructive = destructive;
            this.inputSchema = inputSchema;
        }
    }

    private final ProjectFileOperations files;
    private final McpSettings settings;
    private final ProjectBuildService buildService;
    private final File toolsDirectory;
    private final PathViolationGuard guard;
    private final DebugModeSettings debugSettings;
    private final List<ToolSpec> tools = new ArrayList<>();
    /** The app's native library folder (holds the Python and Dart executables); null when unknown, then check_syntax says so. */
    private volatile File nativeLibraryDirectory;

    /** Without a violation guard (used by the screen to list tools). */
    public McpToolRegistry(ProjectFileOperations files, McpSettings settings, ProjectBuildService buildService, File toolsDirectory) {
        this(files, settings, buildService, toolsDirectory, null, null);
    }

    public McpToolRegistry(ProjectFileOperations files, McpSettings settings, ProjectBuildService buildService, File toolsDirectory, PathViolationGuard guard) {
        this(files, settings, buildService, toolsDirectory, guard, null);
    }

    /**
     * @param guard counts attempts to leave the project folder; may be null (tests)
     * @param debugSettings backs the Debug On/Off tool; when null the tool is not offered
     */
    public McpToolRegistry(ProjectFileOperations files, McpSettings settings, ProjectBuildService buildService, File toolsDirectory, PathViolationGuard guard, DebugModeSettings debugSettings) {
        this.files = files;
        this.settings = settings;
        this.buildService = buildService;
        this.toolsDirectory = toolsDirectory;
        this.guard = guard;
        this.debugSettings = debugSettings;
        registerTools();
    }

    /** Tells check_syntax where the bundled Python and Dart executables live ({@code ApplicationInfo.nativeLibraryDir}). */
    public void setNativeLibraryDirectory(File directory) { this.nativeLibraryDirectory = directory; }

    // ------------------------------------------------------------------ catalogue

    private void registerTools() {
        tools.add(new ToolSpec("get_studio_limits", "Studio Mobile limits",
            "Returns what Studio Mobile can and cannot build (Java up to 21, Android up to 16 / API 36, supported Gradle features). Read this before changing build files.",
            false, false, schema().build()));

        tools.add(new ToolSpec("get_toolchain_info", "Installed toolchain",
            "Lists the phone's ABIs, which languages this phone supports (Python, Dart and on-device C/C++ need an arm64-v8a phone) and the Android platforms (android.jar) actually installed. compileSdk must equal one installed platform exactly.",
            false, false, schema().build()));

        tools.add(new ToolSpec("list_projects", "List projects",
            "Lists the project folders on the phone. Use a returned name as the 'project' argument of the other tools.",
            false, false, schema().build()));

        tools.add(new ToolSpec("list_files", "List files",
            "Lists files and folders inside a project. Folders end with '/'. Generated folders (build, .gradle, .git, outputs, .idea) are hidden. "
                + "A folder whose content is not shown because of 'depth' is marked '(N items hidden by depth limit)'.",
            false, false, schema()
                .string("project", "Project name from list_projects.", true)
                .string("path", "Folder relative to the project root. Empty means the project root.", false)
                .integer("depth", "How many levels to show, 1 to 8. Default 3.", false)
                .build()));

        tools.add(new ToolSpec("read_file", "Read file",
            "Reads a text file. Three modes: whole file (large files are cut; use 'offset' to continue), a line range (start_line, end_line, like sed -n) "
                + "or the last lines (tail_lines, like tail). Set line_numbers to prefix each line with its number. Files that are too large to load are only readable by line.",
            false, false, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .integer("start_line", "First line to return, 1 based. Use with end_line.", false)
                .integer("end_line", "Last line to return, inclusive. 0 or missing means until the output limit.", false)
                .integer("tail_lines", "Return only the last N lines (max 2000). Overrides start_line.", false)
                .bool("line_numbers", "Prefix lines with their number. Default false.", false)
                .integer("offset", "Whole-file mode: character offset to start from. Default 0.", false)
                .integer("max_chars", "Whole-file mode: maximum characters to return. Default and maximum " + ProjectFileOperations.MAX_READ_CHARS + ".", false)
                .build()));

        tools.add(new ToolSpec("search_text", "Search text",
            "Searches all text files of a project for a word or regular expression and returns 'file:line: text' (like grep). "
                + "Much cheaper than reading files: use it to find where something is before reading or editing.",
            false, false, schema()
                .string("project", "Project name.", true)
                .string("query", "Word or phrase, or a regular expression when regex is true.", true)
                .bool("regex", "Treat query as a Java regular expression. Default false.", false)
                .bool("ignore_case", "Case-insensitive search. Default false.", false)
                .string("path", "Folder or file to search, relative to the project root. Default the whole project.", false)
                .string("file_pattern", "Only search files whose name matches this glob, for example *.java or *.xml.", false)
                .build()));

        tools.add(new ToolSpec("find_files", "Find files",
            "Finds files by name pattern (like find). '*' matches within a name, '**' across folders. A pattern with '/' is matched against the whole relative path, "
                + "for example res/layout/*.xml.",
            false, false, schema()
                .string("project", "Project name.", true)
                .string("pattern", "Glob such as *.xml or **/Main*.java.", true)
                .string("path", "Folder to search in. Default the whole project.", false)
                .build()));

        tools.add(new ToolSpec("workspace_overview", "Workspace overview",
            "Shows the whole Studio Mobile root in one call: every project (file count, size, libs) plus the installed toolchain. Read-only.",
            false, false, schema().build()));

        tools.add(new ToolSpec("check_local_libs", "Check local libraries",
            "Checks that the toolchain jars (android.jar, kotlin-stdlib.jar) and, when 'project' is given, the jar/aar files in its libs folders exist and are not corrupt. "
                + "Set deep to read every entry (slower, finds truncated files).",
            false, false, schema()
                .string("project", "Optional project whose libs folders should be checked too.", false)
                .bool("deep", "Read every archive entry to detect damaged data. Default false.", false)
                .build()));

        tools.add(new ToolSpec("validate_project", "Validate project",
            "Static check of the project against Studio Mobile's limits: layout, Java level, compileSdk and targetSdk. It does not compile.",
            false, false, schema().string("project", "Project name.", true).build()));

        tools.add(new ToolSpec("check_syntax", "Check syntax",
            "Checks the syntax of ONE file without running it: .py, .dart, .js, .c, .cc, .cpp, .cxx, .h, .hpp. Never executes your code. "
                + "Answers 'RESULT: OK', 'RESULT: SYNTAX ERRORS' (file and line) or 'RESULT: NOT CHECKED' (with the reason). "
                + "Python, Dart and C/C++ work only on arm64-v8a phones (see get_toolchain_info); on other phones the answer is NOT CHECKED and you must tell the user, not try another way. "
                + "JavaScript is parsed with node --check on every phone (.js files as CommonJS). Java, Kotlin, XML and Gradle files are not covered: only the user's Build checks them.",
            false, false, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .build()));

        tools.add(new ToolSpec("get_build_status", "Build status",
            "Returns the state of the current or latest build: IDLE, RUNNING, SUCCESS, FAILED or CANCELLED.",
            false, false, schema().build()));

        tools.add(new ToolSpec("wait_for_build", "Wait for build",
            "Waits on the phone until the build finishes or about " + MAX_WAIT_SECONDS + " seconds pass, then returns a very short status. "
                + "Builds can take several minutes: call this again only while it says RUNNING. Never poll with other tools. "
                + "When the build fails, the answer already contains the error lines.",
            false, false, schema().integer("max_seconds", "How long to wait, 1 to " + MAX_WAIT_SECONDS + ". Default " + MAX_WAIT_SECONDS + ".", false).build()));

        tools.add(new ToolSpec("get_build_log", "Build log",
            "Returns build output. Mode 'errors' (default) returns only error lines, 'tail' the last lines, 'all' the whole log in pages. "
                + "Not needed while a build runs; use wait_for_build.",
            false, false, schema()
                .string("mode", "errors, tail or all. Default errors.", false)
                .integer("lines", "For tail: how many lines. Default 40.", false)
                .integer("offset", "For all: character offset to continue from. Default 0.", false)
                .build()));

        tools.add(new ToolSpec("write_file", "Write file",
            "Creates a NEW text file. It refuses to replace an existing file unless overwrite is true, so work cannot be lost by accident. "
                + "Before overwriting, list_files and read_file the file first. For changes to an existing file prefer edit_file, multi_edit or replace_lines. Missing folders are created. "
                + "Gradle files are allowed; the result then includes a limit check.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .string("content", "Full UTF-8 text content of the file.", true)
                .bool("overwrite", "Allow replacing an existing file. Default false.", false)
                .build()));

        tools.add(new ToolSpec("edit_file", "Edit file",
            "Replaces text in an existing file. 'old_text' must match exactly once unless replace_all is true. Works for Gradle files too.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .string("old_text", "Exact text to replace. Copy it from read_file.", true)
                .string("new_text", "Replacement text. May be empty to delete the old text.", true)
                .bool("replace_all", "Replace every occurrence instead of requiring a unique match. Default false.", false)
                .build()));

        tools.add(new ToolSpec("multi_edit", "Edit file (several replacements)",
            "Applies several text replacements to one file in order. All of them succeed or none is written. Each old_text must match exactly once unless replace_all is true.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .editsArray("edits", "List of {old_text, new_text, replace_all?} objects, applied in order (max 100).")
                .build()));

        tools.add(new ToolSpec("replace_lines", "Replace lines",
            "Replaces lines start_line to end_line (1 based, inclusive) with new text. Give expected_old_text (the current text of those lines) so a shifted file is never damaged; "
                + "use read_file with line_numbers first.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .integer("start_line", "First line to replace, 1 based.", true)
                .integer("end_line", "Last line to replace, inclusive.", true)
                .string("new_text", "Replacement text. Empty deletes the lines.", true)
                .string("expected_old_text", "Optional. The text those lines must currently have; the edit is refused when it differs.", false)
                .build()));

        tools.add(new ToolSpec("insert_lines", "Insert lines",
            "Inserts text after line N (0 inserts at the top, the last line number inserts at the end).",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .integer("after_line", "Insert after this line number. 0 = at the top.", true)
                .string("content", "Text to insert.", true)
                .build()));

        tools.add(new ToolSpec("append_file", "Append to file",
            "Adds text at the end of a file (like >>). The file is created when it does not exist.",
            true, false, schema()
                .string("project", "Project name.", true)
                .string("path", "File path relative to the project root.", true)
                .string("content", "Text to add. Include a leading line break if the file does not end with one.", true)
                .build()));

        tools.add(new ToolSpec("copy_path", "Copy",
            "Copies a file or folder inside the project (like cp -r). Never replaces an existing destination.",
            true, false, schema()
                .string("project", "Project name.", true)
                .string("from", "Source path relative to the project root.", true)
                .string("to", "New path relative to the project root.", true)
                .build()));

        tools.add(new ToolSpec("create_directory", "Create folder",
            "Creates a folder, including missing parent folders.",
            true, false, schema()
                .string("project", "Project name.", true)
                .string("path", "Folder path relative to the project root.", true)
                .build()));

        tools.add(new ToolSpec("move_path", "Move or rename",
            "Moves or renames a file or folder inside the project, for example to fix a file in the wrong place. "
                + "Does not replace an existing file unless overwrite is true, and never replaces a folder.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("from", "Current path relative to the project root.", true)
                .string("to", "New path relative to the project root, including the new file or folder name.", true)
                .bool("overwrite", "Replace an existing destination file. Default false.", false)
                .build()));

        if (debugSettings != null) {
            tools.add(new ToolSpec("set_debug_mode", "Debug On/Off", DebugModeSettings.TOOL_DESCRIPTION,
                true, false, schema()
                    .bool("enabled", "true turns Debug Mode on, false turns it off. Leave it out to only read the current state.", false)
                    .build()));
        }

        tools.add(new ToolSpec("delete_path", "Delete",
            "Deletes a file or a folder with everything inside it. Cannot be undone. The project root cannot be deleted, and '.' or an empty path is refused. Name the exact file or folder.",
            true, true, schema()
                .string("project", "Project name.", true)
                .string("path", "Path relative to the project root.", true)
                .build()));

    }

    /** Tools visible to the client right now. */
    public JSONArray listTools() {
        boolean readOnly = settings.isReadOnly();
        JSONArray array = new JSONArray();
        for (ToolSpec tool : tools) {
            if (readOnly && tool.changesThings) continue;
            JSONObject entry = new JSONObject();
            JSONObject annotations = new JSONObject();
            put(entry, "name", tool.name);
            put(entry, "title", tool.title);
            put(entry, "description", tool.description);
            put(entry, "inputSchema", tool.inputSchema);
            put(annotations, "readOnlyHint", !tool.changesThings);
            put(annotations, "destructiveHint", tool.destructive);
            put(entry, "annotations", annotations);
            array.put(entry);
        }
        return array;
    }

    public boolean exists(String name) {
        for (ToolSpec tool : tools) if (tool.name.equals(name)) return true;
        return false;
    }

    public List<String> toolNames(boolean includeChanging) {
        List<String> names = new ArrayList<>();
        for (ToolSpec tool : tools) if (includeChanging || !tool.changesThings) names.add(tool.name);
        return names;
    }

    // ------------------------------------------------------------------ execution

    /** Runs a tool and returns an MCP tool result. Failures come back as results with isError, not as protocol errors. */
    public JSONObject call(String name, JSONObject arguments) {
        ToolSpec spec = null;
        for (ToolSpec tool : tools) if (tool.name.equals(name)) { spec = tool; break; }
        if (spec == null) return result("Unknown tool: " + name, true);
        if (spec.changesThings && settings.isReadOnly())
            return result("Studio Mobile is in read-only mode. Ask the user to turn it off in the MCP page if you should change files or build.", true);
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        try {
            return result(redact(execute(name, args), !returnsFileContent(name)), false);
        } catch (ProjectFileOperations.PathEscapeException error) {
            return result(handleEscape(args, error), true);
        } catch (IOException | IllegalArgumentException error) {
            return result(redact(error.getMessage() == null ? error.toString() : error.getMessage()), true);
        } catch (RuntimeException error) {
            return result("Unexpected error: " + redact(String.valueOf(error)), true);
        }
    }

    /**
     * A path tried to leave the project. The same escaping path twice trips the guard: the service then reconnects
     * to the server URL, which ends the AI session. The warning is sent first so the AI learns why.
     */
    private String handleEscape(JSONObject args, ProjectFileOperations.PathEscapeException error) {
        String path = firstNonEmpty(args, "path", "from", "to", "project");
        boolean tripped = guard != null && guard.record(args.optString("project", ""), path);
        String message = "BLOCKED: " + error.getMessage() + "\nOnly paths inside the project are allowed. ";
        return message + (tripped
            ? "This path was refused twice, so Studio Mobile is resetting the connection to the server now. Reconnect and stay inside the project."
            : "Trying the same path again will disconnect this session.");
    }

    private static String firstNonEmpty(JSONObject args, String... keys) {
        for (String key : keys) { String value = args.optString(key, ""); if (!value.trim().isEmpty()) return value; }
        return "";
    }

    /** The AI never needs the app's private absolute paths, so they are replaced by short labels in every answer. */
    /** Tools that return the user's own file text. Their output must stay exactly as written, or the AI could not edit what it read. */
    private static boolean returnsFileContent(String tool) {
        return tool.equals("read_file") || tool.equals("search_text");
    }

    /** Android's own absolute paths (app data, native libraries, storage roots) that no tool needs to show. */
    private static final java.util.regex.Pattern DEVICE_PATH = java.util.regex.Pattern.compile(
        "/(?:data/(?:user(?:_de)?/\\d+|data|app|local)|storage/(?:emulated/\\d+|self|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})|sdcard|mnt/(?:sdcard|user|media_rw))[^\\s\"'`),;:<>]*");

    private String redact(String text) { return redact(text, true); }

    /**
     * Hides where things live on the phone. Known folders get a short label; with {@code sweep} any other Android system path
     * that slipped into a message (a tool error, a library location) becomes {@code <device-path>}.
     */
    private String redact(String text, boolean sweep) {
        if (text == null) return "";
        String result = text;
        File projectsRoot = files.getProjectsRoot();
        File appFiles = toolsDirectory.getParentFile();
        File appData = appFiles == null ? null : appFiles.getParentFile();   // holds files/ and cache/
        File studioRoot = projectsRoot.getParentFile();                      // holds projects, My Application, local libs, error logs
        result = replacePath(result, projectsRoot, "<projects>");
        result = replacePath(result, appFiles, "<app>");
        result = replacePath(result, appData, "<app-data>");
        result = replacePath(result, studioRoot, "<studio>");
        if (sweep) result = DEVICE_PATH.matcher(result).replaceAll("<device-path>");
        return result;
    }

    private static String replacePath(String text, File dir, String label) {
        if (dir == null) return text;
        String path = dir.getAbsolutePath();
        return path.length() < 2 ? text : text.replace(path, label);
    }

    private String execute(String name, JSONObject args) throws IOException {
        switch (name) {
            case "get_studio_limits":
                return McpLimits.instructions(settings.isReadOnly());

            case "get_toolchain_info":
                return describeToolchain();

            case "list_projects": {
                List<String> names = files.listProjects();
                if (names.isEmpty()) return "No projects yet. The user has to create or import one in Studio Mobile.";
                return String.join("\n", names);
            }

            case "list_files": {
                File project = files.projectDirectory(text(args, "project"));
                return files.list(project, text(args, "path"), args.optInt("depth", 3));
            }

            case "read_file": {
                File project = files.projectDirectory(text(args, "project"));
                return files.read(project, required(args, "path"), args.optInt("offset", 0), args.optInt("max_chars", 0),
                    args.optInt("start_line", 0), args.optInt("end_line", 0), args.optInt("tail_lines", 0), args.optBoolean("line_numbers", false));
            }

            case "search_text": {
                File project = files.projectDirectory(text(args, "project"));
                return files.search(project, text(args, "path"), args.optString("query", ""), args.optBoolean("regex", false),
                    args.optBoolean("ignore_case", false), text(args, "file_pattern"));
            }

            case "find_files": {
                File project = files.projectDirectory(text(args, "project"));
                return files.find(project, text(args, "path"), required(args, "pattern"));
            }

            case "workspace_overview":
                return McpWorkspaceInspector.overview(files.getProjectsRoot(), toolsDirectory);

            case "check_local_libs": {
                String name2 = text(args, "project");
                File project = name2.trim().isEmpty() ? null : files.projectDirectory(name2);
                return McpWorkspaceInspector.localLibs(toolsDirectory, project, args.optBoolean("deep", false));
            }

            case "validate_project":
                return McpLimits.validate(files.projectDirectory(text(args, "project")));

            case "check_syntax": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                File target = files.resolve(project, path);
                if (!target.isFile()) throw new IllegalArgumentException("Not a file: " + path);
                if (target.length() > MAX_SYNTAX_FILE_BYTES) throw new IllegalArgumentException(path + " is larger than 1 MB; check_syntax is for hand-written source files.");
                return new McpSyntaxChecker(toolsDirectory, nativeLibraryDirectory).check(project, target, path);
            }

            case "get_build_status":
                return describeBuildStatus();

            case "wait_for_build": {
                int seconds = Math.max(1, Math.min(args.optInt("max_seconds", MAX_WAIT_SECONDS), MAX_WAIT_SECONDS));
                return waitAndDescribe(seconds);
            }

            case "get_build_log": {
                String mode = args.optString("mode", "errors");
                if (mode.equals("tail")) return buildService.getLogTail(Math.max(1, Math.min(args.optInt("lines", 40), 200)));
                if (mode.equals("all")) {
                    String all = buildService.getLog();
                    int start = Math.max(0, Math.min(args.optInt("offset", 0), all.length()));
                    String chunk = all.substring(Math.max(start, Math.max(0, all.length() - MAX_LOG_CHARS)));
                    return chunk + "\n[state=" + buildService.getState() + " log_length=" + all.length() + "]";
                }
                String errors = buildService.getErrorSummary(ERROR_LINES);
                return errors.isEmpty() ? "No error lines in the log. state=" + buildService.getState() : errors;
            }

            case "write_file": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                if (!args.has("content")) throw new IllegalArgumentException("Missing 'content'.");
                boolean existed = files.write(project, path, args.optString("content", ""), args.optBoolean("overwrite", false));
                ProjectChangeBus.changed(project, !existed);
                return (existed ? "Replaced " : "Created ") + path + gradleNote(project, path);
            }

            case "edit_file": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                int count = files.edit(project, path, args.optString("old_text", ""), args.optString("new_text", ""), args.optBoolean("replace_all", false));
                ProjectChangeBus.changed(project, false);
                return "Edited " + path + " (" + count + " replacement" + (count == 1 ? "" : "s") + ")" + gradleNote(project, path);
            }

            case "multi_edit": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                int count = files.multiEdit(project, path, parseEdits(args));
                ProjectChangeBus.changed(project, false);
                return "Edited " + path + " (" + count + " replacement" + (count == 1 ? "" : "s") + ", all applied)" + gradleNote(project, path);
            }

            case "replace_lines": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                if (!args.has("start_line") || !args.has("end_line") || !args.has("new_text")) throw new IllegalArgumentException("Missing 'start_line', 'end_line' or 'new_text'.");
                int n = files.replaceLines(project, path, args.optInt("start_line", 0), args.optInt("end_line", 0), args.optString("new_text", ""),
                    args.has("expected_old_text") ? args.optString("expected_old_text", "") : null);
                ProjectChangeBus.changed(project, false);
                return "Replaced " + n + " line" + (n == 1 ? "" : "s") + " in " + path + gradleNote(project, path);
            }

            case "insert_lines": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                if (!args.has("after_line") || !args.has("content")) throw new IllegalArgumentException("Missing 'after_line' or 'content'.");
                int n = files.insertLines(project, path, args.optInt("after_line", -1), args.optString("content", ""));
                ProjectChangeBus.changed(project, false);
                return "Inserted " + n + " line" + (n == 1 ? "" : "s") + " into " + path + gradleNote(project, path);
            }

            case "append_file": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                if (!args.has("content")) throw new IllegalArgumentException("Missing 'content'.");
                boolean existed = files.resolve(project, path).exists();
                long size = files.append(project, path, args.optString("content", ""));
                ProjectChangeBus.changed(project, !existed);
                return "Appended to " + path + " (now " + size + " bytes)" + gradleNote(project, path);
            }

            case "copy_path": {
                File project = files.projectDirectory(text(args, "project"));
                String from = required(args, "from");
                String to = required(args, "to");
                int n = files.copy(project, from, to);
                ProjectChangeBus.changed(project, true);
                return "Copied " + from + " -> " + to + " (" + n + " item" + (n == 1 ? "" : "s") + ")" + gradleNote(project, to);
            }

            case "create_directory": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                files.createDirectory(project, path);
                ProjectChangeBus.changed(project, true);
                return "Folder ready: " + path;
            }

            case "move_path": {
                File project = files.projectDirectory(text(args, "project"));
                String from = required(args, "from");
                String to = required(args, "to");
                files.move(project, from, to, args.optBoolean("overwrite", false));
                ProjectChangeBus.changed(project, true);
                return "Moved " + from + " -> " + to + gradleNote(project, to);
            }

            case "set_debug_mode": {
                if (debugSettings == null) throw new IllegalArgumentException("Debug Mode is not available here.");
                if (args.has("enabled")) debugSettings.setEnabled(args.optBoolean("enabled", false));
                boolean on = debugSettings.isEnabled();
                return "debug_mode=" + (on ? "ON" : "OFF")
                    + (on ? "\nThe user must press Build again: the next APK then carries a crash catcher that shows a full-screen log (with Copy) when the app crashes. "
                        + "Ask the user to turn it off before sharing the app."
                        : "\nThe next build will not contain the crash catcher.");
            }

            case "delete_path": {
                File project = files.projectDirectory(text(args, "project"));
                String path = required(args, "path");
                int removed = files.delete(project, path);
                ProjectChangeBus.changed(project, true);
                return "Deleted " + path + " (" + removed + " item" + (removed == 1 ? "" : "s") + ")";
            }

            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    /** Waits (bounded) and returns the shortest answer that still tells the client what to do next. */
    private String waitAndDescribe(int seconds) throws IOException {
        ProjectBuildService.State state;
        try { state = buildService.awaitFinish(seconds * 1000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Wait was interrupted."); }
        String elapsed = formatDuration(buildService.getElapsedSeconds());
        switch (state) {
            case RUNNING: {
                String last = buildService.getLastLogLine();
                return "state=RUNNING elapsed=" + elapsed + (last.isEmpty() ? "" : " last=\"" + shorten(last, 120) + "\"")
                    + "\nStill building. Call wait_for_build again. Do not call other tools meanwhile.";
            }
            case SUCCESS: {
                File apk = buildService.getLastApk();
                return "state=SUCCESS elapsed=" + elapsed + (apk == null ? "" : " apk=" + apk.getName() + " (" + apk.length() + " bytes)")
                    + "\nThe APK is on the phone. Tell the user to save it from the app.";
            }
            case IDLE:
                return "state=IDLE. No build has been started.";
            default: {
                String errors = buildService.getErrorSummary(ERROR_LINES);
                return "state=" + state + " elapsed=" + elapsed + "\nmessage=" + shorten(buildService.getLastMessage(), 300)
                    + (errors.isEmpty() ? "\n" + buildService.getLogTail(15) : "\n" + errors);
            }
        }
    }

    private String describeToolchain() {
        StringBuilder text = new StringBuilder();
        text.append(McpSyntaxChecker.languageSupport(nativeLibraryDirectory, com.example.studiomobile.build.DeviceAbi.supportedAbis())).append('\n');
        File platforms = new File(toolsDirectory, "platforms");
        List<Integer> apis = new ArrayList<>();
        File[] entries = platforms.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                File jar = new File(entry, "android.jar");
                String name = entry.getName();
                if (entry.isDirectory() && jar.isFile() && jar.length() > 0 && name.startsWith("android-")) {
                    try { apis.add(Integer.parseInt(name.substring("android-".length()))); } catch (NumberFormatException ignored) { }
                }
            }
        }
        java.util.Collections.sort(apis);
        if (apis.isEmpty()) {
            text.append("Installed Android platforms: none found. Builds will fail until an android.jar is installed.\n");
        } else {
            text.append("Installed Android platforms (compileSdk must be one of these exactly):\n");
            for (int api : apis) text.append("- API ").append(api).append(" = Android ").append(com.example.studiomobile.LanguageUi.androidName(api)).append('\n');
        }
        text.append("Highest API Studio Mobile supports: ").append(McpLimits.MAX_ANDROID_API).append(" (Android ").append(McpLimits.MAX_ANDROID_VERSION).append(")\n");
        text.append("Java language level: ").append(McpLimits.JAVA_LEVEL).append('\n');
        text.append("kotlin-stdlib: ").append(new File(toolsDirectory, "kotlin-stdlib.jar").length() > 0 ? "installed" : "missing").append('\n');
        text.append("No shell and no desktop tools exist here. Code is never run by you: check_syntax only parses or compiles one file, and only a build started by the user in the app compiles the project. Use check_local_libs to verify the libraries are intact.");
        return text.toString();
    }

    private static String formatDuration(long seconds) {
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) + "s";
    }

    private static String shorten(String text, int max) {
        String clean = text == null ? "" : text.replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private String describeBuildStatus() {
        StringBuilder text = new StringBuilder();
        text.append("state=").append(buildService.getState());
        text.append("\njob_id=").append(buildService.getActiveJobId());
        if (!buildService.getProjectName().isEmpty()) text.append("\nproject=").append(buildService.getProjectName());
        if (!buildService.getLastMessage().isEmpty()) text.append("\nmessage=").append(buildService.getLastMessage());
        File apk = buildService.getLastApk();
        if (apk != null) text.append("\napk=").append(apk.getName()).append(" (").append(apk.length()).append(" bytes, stays on the phone)");
        text.append("\nlog_length=").append(buildService.getLogLength());
        return text.toString();
    }

    private static List<ProjectFileOperations.Edit> parseEdits(JSONObject args) {
        Object raw = args.opt("edits");
        JSONArray array = null;
        try {
            if (raw instanceof JSONArray) array = (JSONArray) raw;
            else if (raw instanceof String) array = new JSONArray((String) raw);
        } catch (JSONException ignored) { /* reported below */ }
        if (array == null || array.length() == 0) throw new IllegalArgumentException("'edits' must be a non-empty array of {old_text, new_text, replace_all?}.");
        List<ProjectFileOperations.Edit> edits = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || !item.has("old_text") || !item.has("new_text"))
                throw new IllegalArgumentException("edits[" + i + "] needs 'old_text' and 'new_text'.");
            edits.add(new ProjectFileOperations.Edit(item.optString("old_text", ""), item.optString("new_text", ""), item.optBoolean("replace_all", false)));
        }
        return edits;
    }

    private String gradleNote(File project, String path) {
        return McpLimits.isGradleFile(path) ? McpLimits.checkAfterGradleEdit(project) : "";
    }

    // ------------------------------------------------------------------ small helpers

    private static String text(JSONObject args, String key) { return args.optString(key, ""); }

    private static String required(JSONObject args, String key) {
        String value = args.optString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("Missing '" + key + "'.");
        return value;
    }

    private static JSONObject result(String text, boolean isError) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject block = new JSONObject();
        put(block, "type", "text");
        put(block, "text", text);
        content.put(block);
        put(result, "content", content);
        put(result, "isError", isError);
        return result;
    }

    static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    // ------------------------------------------------------------------ schema builder

    private static SchemaBuilder schema() { return new SchemaBuilder(); }

    private static final class SchemaBuilder {
        private final JSONObject properties = new JSONObject();
        private final JSONArray required = new JSONArray();

        SchemaBuilder string(String name, String description, boolean isRequired) { return property(name, "string", description, isRequired); }
        SchemaBuilder integer(String name, String description, boolean isRequired) { return property(name, "integer", description, isRequired); }
        SchemaBuilder bool(String name, String description, boolean isRequired) { return property(name, "boolean", description, isRequired); }

        SchemaBuilder editsArray(String name, String description) {
            JSONObject item = new JSONObject();
            put(item, "type", "object");
            JSONObject itemProperties = new JSONObject();
            JSONObject oldText = new JSONObject(); put(oldText, "type", "string"); put(oldText, "description", "Exact text to replace."); put(itemProperties, "old_text", oldText);
            JSONObject newText = new JSONObject(); put(newText, "type", "string"); put(newText, "description", "Replacement text (may be empty)."); put(itemProperties, "new_text", newText);
            JSONObject all = new JSONObject(); put(all, "type", "boolean"); put(all, "description", "Replace every occurrence. Default false."); put(itemProperties, "replace_all", all);
            put(item, "properties", itemProperties);
            JSONArray itemRequired = new JSONArray(); itemRequired.put("old_text"); itemRequired.put("new_text");
            put(item, "required", itemRequired);
            JSONObject property = new JSONObject();
            put(property, "type", "array");
            put(property, "description", description);
            put(property, "items", item);
            put(properties, name, property);
            required.put(name);
            return this;
        }

        private SchemaBuilder property(String name, String type, String description, boolean isRequired) {
            JSONObject property = new JSONObject();
            put(property, "type", type);
            put(property, "description", description);
            put(properties, name, property);
            if (isRequired) required.put(name);
            return this;
        }

        JSONObject build() {
            JSONObject schema = new JSONObject();
            put(schema, "type", "object");
            put(schema, "properties", properties);
            if (required.length() > 0) put(schema, "required", required);
            return schema;
        }
    }
}
