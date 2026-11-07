package dev.iredsc.hadron.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Where a test is written, {@code HelloTests.java:12} (its first line), read from the class file's line table, so a
 * report points at every test, passing ones too, without anyone opening the source to find it.
 */
final class TestSource {
    private TestSource() {}

    /** The file and first line of {@code method}'s body, or null if the class was compiled without lines. */
    static String of(Class<?> type, Method method) {
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            String wanted = method.getName() + Type.getMethodDescriptor(method);
            String[] file = {null};
            Map<String, Integer> first = new HashMap<>();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    file[0] = source;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    if (!(name + descriptor).equals(wanted)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLineNumber(int line, Label start) {
                            first.merge(wanted, line, Math::min);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
            Integer line = first.get(wanted);
            if (line == null) return null;
            return (file[0] != null ? file[0] : type.getSimpleName() + ".java") + ":" + line;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
