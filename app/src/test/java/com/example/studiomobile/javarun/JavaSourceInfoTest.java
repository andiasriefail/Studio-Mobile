package com.example.studiomobile.javarun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class JavaSourceInfoTest {
    private static final String SOURCE = "// class Fake {}\n/* class Fake2 */\npackage com.acme.app;\nimport x;\n"
        + "public class Main { String s = \"class InString\"; static class Nested {} }\ninterface Helper {}\nenum Mode { A }\nrecord P(int x) {}\n";

    @Test public void packageIsReadAndCommentsAreIgnored() {
        assertEquals("com.acme.app", JavaSourceInfo.packageName(SOURCE));
        assertEquals("", JavaSourceInfo.packageName("class A {}"));
    }

    @Test public void candidatesListTheFileClassFirstAndIgnoreCommentsAndStrings() {
        List<String> names = JavaSourceInfo.candidates(SOURCE, "Main");
        assertEquals("com.acme.app.Main", names.get(0));
        assertTrue(names.containsAll(Arrays.asList("com.acme.app.Helper", "com.acme.app.Mode", "com.acme.app.P")));
        assertFalse(names.contains("com.acme.app.Fake") || names.contains("com.acme.app.Fake2") || names.contains("com.acme.app.InString"));
    }

    @Test public void aFileWithoutTypesIsTheImplicitClass() {
        assertEquals(Arrays.asList("Hello"), JavaSourceInfo.candidates("void main() { System.out.println(1); }", "Hello"));
    }

    @Test public void sourceRootFollowsThePackageFolders() {
        File file = new File("/p/src/main/java/com/acme/app/Main.java");
        assertEquals(new File("/p/src/main/java"), JavaSourceInfo.sourceRoot(file, "com.acme.app"));
        assertEquals(new File("/p/src/main/java/com/acme/app"), JavaSourceInfo.sourceRoot(file, "other.pkg"));
        assertEquals(new File("/p/src/main/java/com/acme/app"), JavaSourceInfo.sourceRoot(file, ""));
    }

    @Test public void kotlinPackageHasNoSemicolon() {
        assertEquals("com.k.app", JavaSourceInfo.packageName("package com.k.app\n\nfun main() {}"));
    }

    @Test public void kotlinTopLevelCodeLivesInTheFileFacade() {
        assertEquals(Arrays.asList("MainKt"), JavaSourceInfo.kotlinCandidates("fun main() { println(1) }", "Main"));
        assertEquals(Arrays.asList("a.b.Hello_worldKt"), JavaSourceInfo.kotlinCandidates("package a.b\nfun main() {}", "hello-world"));
    }

    @Test public void kotlinCandidatesFollowJvmNameThenFacadeThenDeclaredTypes() {
        String source = "@file:JvmName(\"Entry\")\npackage p\n// object Fake\nobject App { @JvmStatic fun main(a: Array<String>) {} }\ndata class D(val x: Int)\n";
        assertEquals(Arrays.asList("p.Entry", "p.MainKt", "p.App", "p.D"), JavaSourceInfo.kotlinCandidates(source, "Main"));
    }
}
