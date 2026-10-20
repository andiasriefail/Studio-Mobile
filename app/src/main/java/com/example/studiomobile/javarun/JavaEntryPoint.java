package com.example.studiomobile.javarun;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Finds and calls the entry point of a compiled class, with the launch rules of Java 25 (static or instance main, with or without args). */
public final class JavaEntryPoint {
    private JavaEntryPoint() { }

    /** @return the entry method of {@code type}, or null. Order: static main(String[]), static main(), instance main(String[]), instance main(). */
    public static Method find(Class<?> type) {
        Method found = pick(type, true, true);
        if (found == null) found = pick(type, true, false);
        if (found == null) found = pick(type, false, true);
        if (found == null) found = pick(type, false, false);
        return found;
    }

    private static Method pick(Class<?> type, boolean wantStatic, boolean withArgs) {
        try {
            Method method = withArgs ? type.getDeclaredMethod("main", String[].class) : type.getDeclaredMethod("main");
            if (Modifier.isStatic(method.getModifiers()) != wantStatic || Modifier.isPrivate(method.getModifiers()) || method.getReturnType() != void.class) return null;
            return method;
        } catch (NoSuchMethodException | LinkageError none) { return null; }
    }

    /** The first class in {@code names} that has an entry point. @throws IllegalStateException with every reason when none has. */
    public static Class<?> locate(ClassLoader loader, List<String> names) {
        StringBuilder problems = new StringBuilder();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                if (find(type) != null) return type;
                problems.append("\n  ").append(name).append(": no main method");
            } catch (ClassNotFoundException missing) {
                problems.append("\n  ").append(name).append(": not found in the compiled output");
            } catch (LinkageError broken) {
                problems.append("\n  ").append(name).append(": ").append(broken);
            }
        }
        throw new IllegalStateException("No class with a main method was found. Tried:" + problems
            + "\nA runnable class needs  public static void main(String[] args)  (or  void main()  in Java 25).");
    }

    /**
     * Calls the entry point on the calling thread. An exception thrown by the program is printed to System.err the way the JVM does.
     * @return 0 when main returned normally, 1 when it threw
     */
    public static int run(Class<?> type, String[] args) {
        Method method = find(type);
        if (method == null) { System.err.println("No main method in " + type.getName()); return 1; }
        try {
            method.setAccessible(true);
            Object target = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                target = constructor.newInstance();
            }
            if (method.getParameterCount() == 0) method.invoke(target); else method.invoke(target, (Object) args);
            return 0;
        } catch (InvocationTargetException failure) {
            System.err.print("Exception in thread \"main\" ");
            failure.getCause().printStackTrace();
            return 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            System.err.print("Exception in thread \"main\" ");
            failure.printStackTrace();
            return 1;
        }
    }
}
