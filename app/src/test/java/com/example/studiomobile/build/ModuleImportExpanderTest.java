package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ModuleImportExpanderTest {
    private static Map<String, Set<String>> index() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("java.io", names("File", "InputStream"));
        map.put("java.util", names("List", "Map", "Date"));
        map.put("java.util.function", names("Function"));
        map.put("java.util.stream", names("Stream", "Collectors"));
        map.put("java.util.logging", names("Logger"));
        map.put("java.sql", names("Connection", "Date"));
        map.put("javax.xml.parsers", names("DocumentBuilder"));
        return map;
    }

    private static Set<String> names(String... values) { return new HashSet<>(Arrays.asList(values)); }

    @Test public void importsOnlyThePackagesWhoseClassesTheFileUses() {
        String result = ModuleImportExpander.expand(
            "package a;\nimport module java.base;\nclass A { List<String> a; Function<String, String> f; }\n", index());
        assertEquals("package a;\nimport java.util.*; import java.util.function.*;\nclass A { List<String> a; Function<String, String> f; }\n", result);
    }

    @Test public void keepsLineNumbersWhenTheStatementSpansLines() {
        String source = "import\n  module\n  java.base\n  ;\nclass A { List<String> a; }\n";
        String result = ModuleImportExpander.expand(source, index());
        assertEquals(source.split("\n", -1).length, result.split("\n", -1).length);
        assertTrue(result.contains("import java.util.*;"));
        assertTrue(result.endsWith("class A { List<String> a; }\n"));
    }

    @Test public void sqlModuleIncludesTheModulesItRequiresTransitively() {
        String result = ModuleImportExpander.expand("import module java.sql;\nclass A { Connection c; Logger l; DocumentBuilder d; }", index());
        assertTrue(result.contains("import java.sql.*;"));
        assertTrue(result.contains("import java.util.logging.*;"));
        assertTrue(result.contains("import javax.xml.parsers.*;"));
    }

    @Test public void aNameInTwoPackagesImportsBothSoItStaysAmbiguousLikeTheRealThing() {
        String result = ModuleImportExpander.expand("import module java.base;\nimport module java.sql;\nclass A { Date d; }", index());
        assertTrue(result.contains("import java.util.*;"));
        assertTrue(result.contains("import java.sql.*;"));
    }

    @Test public void twoModuleImportsDoNotRepeatAPackage() {
        String result = ModuleImportExpander.expand("import module java.sql;\nimport module java.logging;\nclass A { Logger l; }\n", index());
        assertEquals(1, result.split("import java.util.logging\\.\\*;", -1).length - 1);
    }

    @Test public void leavesUnknownModulesAlone() {
        String source = "import module java.desktop;\nclass A { }\n";
        assertSame(source, ModuleImportExpander.expand(source, index()));
    }

    @Test public void ignoresModuleImportsInCommentsAndStrings() {
        String source = "// import module java.base;\n/* import module java.base; */\nclass A { String s = \"import module java.base;\"; String t = \"\"\"\n  import module java.base;\n  \"\"\"; }\n";
        assertSame(source, ModuleImportExpander.expand(source, index()));
    }

    @Test public void ordinaryImportsAndIdentifiersNamedModuleAreUntouched() {
        String source = "import java.util.List;\nimport static java.lang.Math.max;\nclass A { int module = 1; }\n";
        assertSame(source, ModuleImportExpander.expand(source, index()));
    }

    @Test public void nothingUsedLeavesAnEmptyLine() {
        String result = ModuleImportExpander.expand("import module java.base;\nclass A { }\n", index());
        assertEquals("\nclass A { }\n", result);
    }
}
