package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.studiomobile.build.dependency.VersionCatalog;
import java.util.Arrays;
import org.junit.Test;

public class VersionCatalogTest {
    private static VersionCatalog.Library only(String toml, String alias) {
        VersionCatalog.Library library = VersionCatalog.parse(toml).library(alias);
        assertNotNull("library " + alias, library);
        return library;
    }

    // ---- libraries (multi-line TOML, sub-tables, notations)

    @Test public void readsEveryLibraryNotation() {
        String toml = "[versions]\nappcompat = \"1.7.0\"\n[libraries]\n"
            + "a = \"g:a:1.0\"\n"
            + "b = \"g:b\"\n"
            + "c = { module = \"g:c\", version = \"2.0\" }\n"
            + "d = { group = \"g\", name = \"d\", version.ref = \"appcompat\" }\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertEquals("g:a:1.0", catalog.library("a").coordinates());
        assertEquals("g:b", catalog.library("b").coordinates());
        assertEquals("g:c:2.0", catalog.library("c").coordinates());
        assertEquals("g:d:1.7.0", catalog.library("d").coordinates());
    }

    @Test public void aliasSeparatorsAreInterchangeable() {
        VersionCatalog catalog = VersionCatalog.parse("[libraries]\nandroidx-core-ktx = \"a:b:1\"\n");
        assertNotNull(catalog.library("androidx.core.ktx"));
        assertNotNull(catalog.library("androidx_core_ktx"));
        assertNull(catalog.library("androidx.core"));
    }

    @Test public void multiLineInlineTablesAndSubTables() {
        String toml = "[versions]\nv = \"3.1\"\n[libraries]\n"
            + "multi = {\n    group = \"x.y\",   # comment\n    name = \"z\",\n    version.ref = \"v\",\n}\n"
            + "[libraries.sub-table]\nmodule = \"s:t\"\nversion = \"9\"\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertEquals("x.y:z:3.1", catalog.library("multi").coordinates());
        assertEquals("s:t:9", catalog.library("sub.table").coordinates());
    }

    @Test public void commentsAndCrlfAreIgnored() {
        assertEquals("a:b:1", only("# c\r\n[libraries]\r\nx = \"a:b:1\" # trailing\r\n", "x").coordinates());
    }

    @Test public void malformedFileGivesWarningNotException() {
        VersionCatalog catalog = VersionCatalog.parse("[libraries\nx = ");
        assertNull(catalog.library("x"));
        assertFalse(catalog.warnings().isEmpty());
    }

    @Test public void classifierAndUnknownRefAreProblems() {
        String toml = "[libraries]\na = \"g:a:1.0:sources\"\nb = { module = \"g:b\", version.ref = \"nope\" }\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertTrue(catalog.library("a").problem.contains("classifier"));
        assertTrue(catalog.library("b").problem.contains("nope"));
    }

    // ---- bundles

    @Test public void bundlesListTheirLibraries() {
        String toml = "[libraries]\nui-core = \"a:core:1\"\nui-widgets = \"a:widgets:1\"\n"
            + "[bundles]\ncompose-ui = [\n  \"ui-core\",   # first\n  \"ui_widgets\",\n]\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertEquals(Arrays.asList("ui.core", "ui.widgets"), catalog.bundle("compose.ui"));
        assertEquals(catalog.bundle("compose.ui"), catalog.bundle("compose-ui"));
        assertNull(catalog.bundle("missing"));
    }

    @Test public void bundleThatIsNotAListIsReported() {
        assertFalse(VersionCatalog.parse("[bundles]\nbad = \"x\"\n").warnings().isEmpty());
    }

    // ---- rich versions

    @Test public void strictlyRequireAndPreferWithFixedValues() {
        String toml = "[libraries]\n"
            + "s = { module = \"g:s\", version = { strictly = \"1.2.3\" } }\n"
            + "r = { module = \"g:r\", version = { require = \"2.0\" } }\n"
            + "p = { module = \"g:p\", version = { prefer = \"3.0\" } }\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertEquals("1.2.3", catalog.library("s").version);
        assertEquals("2.0", catalog.library("r").version);
        assertEquals("3.0", catalog.library("p").version);
    }

    @Test public void richVersionDefinedInVersionsTable() {
        String toml = "[versions]\nlib = { strictly = \"4.5\" }\n[libraries]\nx = { module = \"g:x\", version.ref = \"lib\" }\n";
        assertEquals("4.5", only(toml, "x").version);
    }

    @Test public void rangeNeedsAFixedPrefer() {
        String toml = "[libraries]\n"
            + "ok = { module = \"g:ok\", version = { strictly = \"[1.0,2.0)\", prefer = \"1.5\" } }\n"
            + "bad = { module = \"g:bad\", version = { require = \"[1.0,2.0)\" } }\n"
            + "text = \"g:text:[1.0,2.0)\"\n";
        VersionCatalog catalog = VersionCatalog.parse(toml);
        assertEquals("1.5", catalog.library("ok").version);
        assertNull(catalog.library("ok").problem);
        assertTrue(catalog.library("bad").problem.contains("range"));
        assertTrue(catalog.library("text").problem.contains("range"));
    }

    @Test public void dynamicVersionsAreProblems() {
        VersionCatalog catalog = VersionCatalog.parse("[libraries]\nx = \"g:x:1.+\"\ny = { module = \"g:y\", version = \"latest.release\" }\n");
        assertTrue(catalog.library("x").problem.contains("dynamic"));
        assertTrue(catalog.library("y").problem.contains("dynamic"));
    }

    @Test public void emptyVersionTableIsAProblem() {
        assertTrue(only("[libraries]\nx = { module = \"g:x\", version = {} }\n", "x").problem != null);
    }
}
