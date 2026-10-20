package com.example.studiomobile.script;

import com.example.studiomobile.build.BuildLogger;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Runs JavaScript with Rhino inside the app process. This works on every device ABI (Rhino is plain Java).
 *
 * <p>Scripts get the standard ECMAScript objects plus {@code print()} and {@code console.log/info/warn/error()}. They get no access to Java
 * classes ({@code initSafeStandardObjects}, no {@code Packages}/{@code java}) so a script cannot touch the app's own code or data.
 * Rhino runs in interpreted mode (required on Android, there is no JVM bytecode loader) and checks the clock every few thousand
 * instructions, which is how timeouts and Stop work.
 */
public final class JavaScriptRunner {
    private static final class Stopped extends Error {
        Stopped(String message) { super(message, null, false, false); }
    }

    private volatile boolean cancelled;
    private volatile long deadlineNanos;

    public void cancel() { cancelled = true; }

    /** @return 0 on success, 1 when the script threw, -1 on timeout or Stop. */
    public int run(String source, String fileName, long timeoutSeconds, BuildLogger logger) {
        cancelled = false;
        deadlineNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        ContextFactory factory = new ContextFactory() {
            @Override protected Context makeContext() {
                Context context = super.makeContext();
                context.setOptimizationLevel(-1);
                context.setInstructionObserverThreshold(5000);
                context.setLanguageVersion(Context.VERSION_ES6);
                return context;
            }
            @Override protected void observeInstructionCount(Context context, int instructionCount) {
                if (cancelled) throw new Stopped("stopped");
                if (System.nanoTime() > deadlineNanos) throw new Stopped("timed out after " + timeoutSeconds + " s");
            }
        };
        try {
            return factory.call(context -> {
                ScriptableObject scope = context.initSafeStandardObjects();
                BaseFunction info = printer(logger, "");
                ScriptableObject.putProperty(scope, "print", info);
                ScriptableObject console = (ScriptableObject) context.newObject(scope);
                ScriptableObject.putProperty(console, "log", info);
                ScriptableObject.putProperty(console, "info", info);
                ScriptableObject.putProperty(console, "warn", printer(logger, "warning: "));
                ScriptableObject.putProperty(console, "error", printer(logger, "error: "));
                ScriptableObject.putProperty(scope, "console", console);
                try {
                    context.evaluateString(scope, source, fileName, 1, null);
                    return 0;
                } catch (RhinoException error) {
                    logger.onError(error.details() + "\n    at " + fileName + ":" + error.lineNumber() + (error.getScriptStackTrace().isEmpty() ? "" : "\n" + error.getScriptStackTrace().trim()), null);
                    return 1;
                }
            });
        } catch (Stopped stopped) {
            logger.onInfo("\n[" + stopped.getMessage() + "]");
            return -1;
        }
    }

    private static BaseFunction printer(BuildLogger logger, String prefix) {
        return new BaseFunction() {
            @Override public Object call(Context context, Scriptable scope, Scriptable thisObject, Object[] arguments) {
                StringBuilder line = new StringBuilder(prefix);
                for (int i = 0; i < arguments.length; i++) { if (i > 0) line.append(' '); line.append(Context.toString(arguments[i])); }
                logger.onInfo(line.toString());
                return Undefined.instance;
            }
        };
    }
}
