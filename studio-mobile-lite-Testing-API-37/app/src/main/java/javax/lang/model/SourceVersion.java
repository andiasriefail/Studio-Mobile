package javax.lang.model;

/**
 * Minimal stand-in for javax.lang.model.SourceVersion.
 *
 * Android's runtime does not ship the javax.lang.model package, but the Eclipse
 * compiler (ECJ) references it. Without this class the in-process compiler fails with
 * "NoClassDefFoundError: Failed resolution of: Ljavax/lang/model/SourceVersion;".
 * This only exists inside the Studio Mobile app itself; it never ends up in the APKs
 * that Studio Mobile builds for users.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7,
    RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14,
    RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19, RELEASE_20, RELEASE_21;

    public static SourceVersion latest() { return RELEASE_21; }

    public static SourceVersion latestSupported() { return RELEASE_17; }

    public static boolean isIdentifier(CharSequence name) {
        String id = String.valueOf(name);
        if (id.isEmpty() || !Character.isJavaIdentifierStart(id.codePointAt(0))) return false;
        for (int i = Character.charCount(id.codePointAt(0)); i < id.length(); ) {
            int cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        String id = String.valueOf(name);
        for (String part : id.split("\\.", -1)) {
            if (!isIdentifier(part) || isKeyword(part)) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        switch (String.valueOf(s)) {
            case "abstract": case "assert": case "boolean": case "break": case "byte": case "case":
            case "catch": case "char": case "class": case "const": case "continue": case "default":
            case "do": case "double": case "else": case "enum": case "extends": case "final":
            case "finally": case "float": case "for": case "goto": case "if": case "implements":
            case "import": case "instanceof": case "int": case "interface": case "long": case "native":
            case "new": case "package": case "private": case "protected": case "public": case "return":
            case "short": case "static": case "strictfp": case "super": case "switch":
            case "synchronized": case "this": case "throw": case "throws": case "transient":
            case "try": case "void": case "volatile": case "while": case "_":
            case "true": case "false": case "null":
                return true;
            default:
                return false;
        }
    }
  }
  
