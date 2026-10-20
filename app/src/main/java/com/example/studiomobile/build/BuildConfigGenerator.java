package com.example.studiomobile.build;

import java.util.Map;

/**
 * Produces the single {@code <namespace>.BuildConfig} source file that AGP would generate for the debug variant.
 * Pure Java: no Android classes, no file access.
 */
public final class BuildConfigGenerator {
    private BuildConfigGenerator() { }

    /**
     * @param applicationId installed package (application modules only; ignored for libraries)
     * @return Java source for {@code namespace + ".BuildConfig"}
     */
    public static String generate(ProjectModules.Module module, String applicationId) {
        if (module.namespace == null) throw new IllegalArgumentException("Module " + module.path + " has no namespace/package; BuildConfig needs one.");
        boolean application = module.kind == ProjectModules.Kind.ANDROID_APPLICATION;
        StringBuilder out = new StringBuilder();
        out.append("/**\n * Automatically generated file. DO NOT MODIFY\n */\n");
        out.append("package ").append(module.namespace).append(";\n\n");
        out.append("public final class BuildConfig {\n");
        out.append("  public static final boolean DEBUG = Boolean.parseBoolean(\"true\");\n");
        if (application) out.append("  public static final String APPLICATION_ID = ").append(literal(applicationId == null ? module.namespace : applicationId)).append(";\n");
        else out.append("  public static final String LIBRARY_PACKAGE_NAME = ").append(literal(module.namespace)).append(";\n");
        out.append("  public static final String BUILD_TYPE = \"debug\";\n");
        if (application) {
            out.append("  public static final int VERSION_CODE = ").append(module.versionCode).append(";\n");
            out.append("  public static final String VERSION_NAME = ").append(literal(module.versionName == null ? "" : module.versionName)).append(";\n");
        }
        if (!module.buildConfigFields.isEmpty()) out.append("  // Fields from build.gradle (defaultConfig, debug)\n");
        for (Map.Entry<String, String[]> field : module.buildConfigFields.entrySet()) {
            String name = field.getKey();
            if (isBuiltIn(name, application)) continue; // AGP rejects redefinition; keep the generated value
            out.append("  public static final ").append(field.getValue()[0]).append(' ').append(name).append(" = ").append(field.getValue()[1]).append(";\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static boolean isBuiltIn(String name, boolean application) {
        return name.equals("DEBUG") || name.equals("BUILD_TYPE") || name.equals("APPLICATION_ID") || name.equals("LIBRARY_PACKAGE_NAME")
            || (application && (name.equals("VERSION_CODE") || name.equals("VERSION_NAME")));
    }

    static String literal(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
