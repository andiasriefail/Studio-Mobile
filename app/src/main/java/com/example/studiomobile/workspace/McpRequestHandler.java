package com.example.studiomobile.workspace;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Speaks MCP (JSON-RPC 2.0) for Studio Mobile, independent of how the bytes travel.
 *
 * <p>The relay, a local HTTP listener or a self-test can all pass the request body to
 * {@link #handle(String)} and send back whatever it returns. A {@code null} return means the
 * message needs no answer (a notification), so the transport should reply with an empty HTTP 202.
 * Only the {@code tools} capability is offered; resources and prompts are not advertised.
 */
public final class McpRequestHandler {

    /** Protocol revisions this server can speak, newest first. The tool features used here exist in all of them. */
    private static final List<String> SUPPORTED_VERSIONS = Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final McpToolRegistry registry;
    private final McpSettings settings;
    private final String appVersion;

    public McpRequestHandler(McpToolRegistry registry, McpSettings settings, String appVersion) {
        this.registry = registry;
        this.settings = settings;
        this.appVersion = appVersion;
    }

    /** Handles one request body. Returns the JSON response text, or null when no response is due. */
    public String handle(String body) {
        if (body == null || body.trim().isEmpty()) return errorResponse(null, INVALID_REQUEST, "Empty request body.").toString();
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                // Older clients may batch. Answer each message and return an array.
                JSONArray requests = new JSONArray(trimmed);
                JSONArray responses = new JSONArray();
                for (int i = 0; i < requests.length(); i++) {
                    JSONObject response = handleMessage(requests.optJSONObject(i));
                    if (response != null) responses.put(response);
                }
                return responses.length() == 0 ? null : responses.toString();
            }
            JSONObject response = handleMessage(new JSONObject(trimmed));
            return response == null ? null : response.toString();
        } catch (JSONException error) {
            return errorResponse(null, PARSE_ERROR, "Invalid JSON: " + error.getMessage()).toString();
        }
    }

    private JSONObject handleMessage(JSONObject message) {
        if (message == null) return errorResponse(null, INVALID_REQUEST, "Request must be a JSON object.");
        Object id = message.opt("id");
        boolean isNotification = !message.has("id");
        String method = message.optString("method", "");

        // A message without a method is a response to something we sent. We never send requests, so ignore it.
        if (method.isEmpty()) return isNotification ? null : errorResponse(id, INVALID_REQUEST, "Missing 'method'.");

        try {
            switch (method) {
                case "initialize":
                    return successResponse(id, initializeResult(message.optJSONObject("params")));
                case "ping":
                    return isNotification ? null : successResponse(id, new JSONObject());
                case "tools/list": {
                    JSONObject result = new JSONObject();
                    result.put("tools", registry.listTools());
                    return successResponse(id, result);
                }
                case "tools/call":
                    return handleToolCall(id, message.optJSONObject("params"));
                default:
                    if (method.startsWith("notifications/") || isNotification) return null;
                    return errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method);
            }
        } catch (JSONException | RuntimeException error) {
            return isNotification ? null : errorResponse(id, INTERNAL_ERROR, "Internal error: " + error.getMessage());
        }
    }

    private JSONObject initializeResult(JSONObject params) throws JSONException {
        String requested = params == null ? "" : params.optString("protocolVersion", "");
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);

        JSONObject tools = new JSONObject();
        tools.put("listChanged", false);
        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", tools);

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "studio-mobile");
        serverInfo.put("title", "Studio Mobile");
        serverInfo.put("version", appVersion);

        JSONObject result = new JSONObject();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", McpLimits.instructions(settings.isReadOnly()));
        return result;
    }

    private JSONObject handleToolCall(Object id, JSONObject params) throws JSONException {
        if (params == null) return errorResponse(id, INVALID_PARAMS, "Missing params.");
        String name = params.optString("name", "");
        if (name.isEmpty()) return errorResponse(id, INVALID_PARAMS, "Missing tool name.");
        if (!registry.exists(name)) return errorResponse(id, INVALID_PARAMS, "Unknown tool: " + name);
        return successResponse(id, registry.call(name, params.optJSONObject("arguments")));
    }

    // ------------------------------------------------------------------ JSON-RPC envelopes

    private static JSONObject successResponse(Object id, JSONObject result) {
        JSONObject response = new JSONObject();
        try {
            response.put("jsonrpc", "2.0");
            response.put("id", id == null ? JSONObject.NULL : id);
            response.put("result", result);
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return response;
    }

    private static JSONObject errorResponse(Object id, int code, String message) {
        JSONObject response = new JSONObject();
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message);
            response.put("jsonrpc", "2.0");
            response.put("id", id == null ? JSONObject.NULL : id);
            response.put("error", error);
        } catch (JSONException exception) { throw new IllegalStateException(exception); }
        return response;
    }
}
