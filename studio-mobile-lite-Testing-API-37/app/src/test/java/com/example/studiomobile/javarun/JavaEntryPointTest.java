package com.example.studiomobile.javarun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.Test;

public class JavaEntryPointTest {
    public static class StaticArgs { public static void main(String[] args) { System.out.println("static-args:" + args.length); } }
    public static class StaticNoArgs { static void main() { System.out.println("static-noargs"); } }
    public static class InstanceArgs { void main(String[] args) { System.out.println("instance-args"); } }
    public static class InstanceNoArgs { void main() { System.out.println("instance-noargs"); } }
    public static class Both { void main() { System.out.println("WRONG"); } public static void main(String[] args) { System.out.println("right"); } }
    public static class Private { private static void main(String[] args) { } }
    public static class NotVoid { public static int main(String[] args) { return 1; } }
    public static class Boom { public static void main(String[] args) { throw new IllegalStateException("kaboom"); } }

    private static String capture(Runnable body) {
        PrintStream oldOut = System.out, oldErr = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(bytes, true);
        System.setOut(stream); System.setErr(stream);
        try { body.run(); } finally { System.setOut(oldOut); System.setErr(oldErr); }
        return bytes.toString();
    }

    @Test public void privateAndNonVoidMainAreRejected() {
        assertNull(JavaEntryPoint.find(Private.class));
        assertNull(JavaEntryPoint.find(NotVoid.class));
    }

    @Test public void staticMainWithArgsIsPreferred() {
        assertTrue(Modifier.isStatic(JavaEntryPoint.find(Both.class).getModifiers()));
        assertNotNull(JavaEntryPoint.find(StaticNoArgs.class));
    }

    @Test public void everyLaunchFormRuns() {
        String out = capture(() -> {
            JavaEntryPoint.run(StaticArgs.class, new String[] {"x", "y"});
            JavaEntryPoint.run(StaticNoArgs.class, new String[0]);
            JavaEntryPoint.run(InstanceArgs.class, new String[0]);
            JavaEntryPoint.run(InstanceNoArgs.class, new String[0]);
            JavaEntryPoint.run(Both.class, new String[0]);
        });
        assertTrue(out.contains("static-args:2") && out.contains("static-noargs") && out.contains("instance-args") && out.contains("instance-noargs") && out.contains("right"));
        assertTrue(!out.contains("WRONG"));
    }

    @Test public void anExceptionGivesExitCodeOneAndIsPrintedLikeTheJvm() {
        int[] code = new int[1];
        String out = capture(() -> code[0] = JavaEntryPoint.run(Boom.class, new String[0]));
        assertEquals(1, code[0]);
        assertTrue(out.contains("Exception in thread \"main\" java.lang.IllegalStateException: kaboom"));
        assertEquals(0, JavaEntryPoint.run(StaticNoArgs.class, new String[0]));
    }

    @Test public void locateSkipsMissingClassesAndExplainsFailure() {
        ClassLoader loader = getClass().getClassLoader();
        String both = Both.class.getName();
        assertEquals(both, JavaEntryPoint.locate(loader, Arrays.asList("Nope", both)).getName());
        try {
            JavaEntryPoint.locate(loader, Arrays.asList("Nope", Private.class.getName()));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Nope: not found") && expected.getMessage().contains("no main method") && expected.getMessage().contains("void main()"));
        }
    }
}
