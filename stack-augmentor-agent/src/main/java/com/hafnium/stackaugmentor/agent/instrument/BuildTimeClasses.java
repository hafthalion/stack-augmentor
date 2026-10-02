package com.hafnium.stackaugmentor.agent.instrument;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import com.hafnium.stackaugmentor.runtime.Log;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

import java.lang.instrument.IllegalClassFormatException;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Arrays;

/**
 * Leaves the classes alone that the build plugin has already instrumented: their advice calls {@link Dispatch}, which
 * reaches the agent's handler too, and a second advice would break them (a constructor's handlers then fail
 * verification). They are recognized by a reference to {@link Dispatch} in their constant pool, which no application
 * class has otherwise.
 */
final class BuildTimeClasses extends ResettableClassFileTransformer.WithDelegation {

    private static final byte[] DISPATCH = Dispatch.class.getName().replace('.', '/').getBytes(StandardCharsets.UTF_8);

    BuildTimeClasses(ResettableClassFileTransformer classFileTransformer) {
        super(classFileTransformer);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return skip(className, classfileBuffer)
                ? null
                : classFileTransformer.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return skip(className, classfileBuffer)
                ? null
                : classFileTransformer.transform(module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    private static boolean skip(String className, byte[] classFile) {
        if (!referencesDispatch(classFile)) {
            return false;
        }
        if (Log.isDebug()) {
            Log.debug(() -> "not instrumenting " + (className == null ? "a class" : className.replace('/', '.'))
                    + ": already instrumented at build time");
        }
        return true;
    }

    /** Whether the class file's constant pool has the internal name of {@link Dispatch}. False for a malformed one. */
    static boolean referencesDispatch(byte[] classFile) {
        try {
            int count = u2(classFile, 8);
            int offset = 10;
            for (int index = 1; index < count; index++) {
                int tag = classFile[offset] & 0xFF;
                switch (tag) {
                    case 1 -> { // Utf8
                        int length = u2(classFile, offset + 1);
                        int start = offset + 3;
                        if (Arrays.equals(classFile, start, start + length, DISPATCH, 0, DISPATCH.length)) {
                            return true;
                        }
                        offset = start + length;
                    }
                    case 7, 8, 16, 19, 20 -> offset += 3; // Class, String, MethodType, Module, Package
                    case 15 -> offset += 4; // MethodHandle
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5; // Integer, Float, refs, NameAndType, (Invoke)Dynamic
                    case 5, 6 -> { // Long, Double: two entries
                        offset += 9;
                        index++;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            return false;
        } catch (IndexOutOfBoundsException e) {
            return false;
        }
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }
}
