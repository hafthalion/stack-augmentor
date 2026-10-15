package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

import static com.hafnium.stackaugmentor.instrument.IdParameters.annotation;

/**
 * Which methods and constructors of a class the annotations augment, by {@code @Augmented}: the method's own, else the
 * type's. Without either, the method is not augmented, and the other annotations are not looked for.
 */
final class AugmentedSelection {

    private AugmentedSelection() {
    }

    /** Whether the annotations augment this method or constructor, which the type declares. */
    static boolean augments(TypeDescription type, MethodDescription method) {
        Boolean own = flag(method.getDeclaredAnnotations());
        if (own != null) {
            return own;
        }
        Boolean onType = flag(type.getDeclaredAnnotations());
        return onType != null && onType;
    }

    /**
     * The value of {@code @Augmented} among these annotations, {@code null} without it. Read by name: the application
     * may load its own, older copy of the API, whose annotation has no value, which counts as on.
     */
    static Boolean flag(AnnotationList annotations) {
        AnnotationDescription augmented = annotation(annotations, IdResolver.AUGMENTED);
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
