package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.values.ValueResolver;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

import static com.hafnium.stackaugmentor.instrument.ParamSelection.annotation;

/**
 * Which methods and constructors of a class the annotations augment, by {@code @Augmented}. The type decides first: a
 * type without it is ignored, and its methods are not looked at. Under {@code @Augmented} on the type, all methods and
 * constructors are augmented except those with {@code @Augmented(false)}; under {@code @Augmented(false)}, only those
 * with {@code @Augmented}. A Kotlin file class, which cannot carry the annotation, counts as {@code @Augmented(false)},
 * so that its top-level functions take {@code @Augmented} of their own.
 */
final class AugmentedSelection {

    private static final String KOTLIN_METADATA = "kotlin.Metadata";
    /** {@code kotlin.Metadata.k} of a file class and of a part of a multi-file class, which hold top-level functions. */
    private static final int KOTLIN_FILE = 2;
    private static final int KOTLIN_MULTI_FILE_PART = 5;

    private AugmentedSelection() {
    }

    /** Whether the annotations augment this method or constructor, which the type declares. */
    static boolean augments(TypeDescription type, MethodDescription method) {
        Boolean onType = onType(type);
        if (onType == null) {
            return false;
        }
        Boolean own = flag(method.getDeclaredAnnotations());
        return own != null ? own : onType;
    }

    /**
     * The type's {@code @Augmented}, {@code false} for a Kotlin file class, {@code null} without it: then the type is
     * ignored.
     */
    static Boolean onType(TypeDescription type) {
        Boolean flag = flag(type.getDeclaredAnnotations());
        if (flag != null) {
            return flag;
        }
        return isKotlinFileClass(type) ? Boolean.FALSE : null;
    }

    private static boolean isKotlinFileClass(TypeDescription type) {
        AnnotationDescription metadata = annotation(type.getDeclaredAnnotations(), KOTLIN_METADATA);
        if (metadata == null) {
            return false;
        }
        for (MethodDescription.InDefinedShape property : metadata.getAnnotationType().getDeclaredMethods()) {
            if (property.getName().equals("k")) {
                try {
                    Object kind = metadata.getValue(property).resolve();
                    return kind instanceof Integer k && (k == KOTLIN_FILE || k == KOTLIN_MULTI_FILE_PART);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * The value of {@code @Augmented} among these annotations, {@code null} without it. Read by name: the application
     * may load its own, older copy of the API, whose annotation has no value, which counts as on.
     */
    static Boolean flag(AnnotationList annotations) {
        AnnotationDescription augmented = annotation(annotations, ValueResolver.AUGMENTED);
        if (augmented == null) {
            return null;
        }
        for (MethodDescription.InDefinedShape property : augmented.getAnnotationType().getDeclaredMethods()) {
            if (property.getName().equals("value")) {
                try {
                    return !Boolean.FALSE.equals(augmented.getValue(property).resolve());
                } catch (RuntimeException e) {
                    return true;
                }
            }
        }
        return true;
    }
}
