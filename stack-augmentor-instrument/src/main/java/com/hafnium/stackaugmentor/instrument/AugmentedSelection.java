package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.runtime.ids.IdResolver;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

import static com.hafnium.stackaugmentor.instrument.IdParameters.annotation;

/**
 * Which methods and constructors of a class the annotations augment, by {@code @Augmented}: the method's own, then the
 * type's, and without either all of them, unless another method or constructor of the type has {@code @Augmented}.
 */
final class AugmentedSelection {

    private record Selected(TypeDescription type, boolean selectsMethods) {
    }

    /** The last type's answer to "does a method select itself": it is asked for once per method of a type. */
    private volatile Selected last;

    /** Whether the annotations augment this method or constructor, which the type declares. */
    boolean augments(TypeDescription type, MethodDescription method) {
        Boolean own = flag(method.getDeclaredAnnotations());
        if (own != null) {
            return own;
        }
        Boolean onType = flag(type.getDeclaredAnnotations());
        if (onType != null) {
            return onType;
        }
        return !selectsMethods(type);
    }

    /** Whether a method or constructor of the type has {@code @Augmented} on, so that the others are not augmented. */
    private boolean selectsMethods(TypeDescription type) {
        Selected selected = last;
        if (selected == null || selected.type() != type) {
            boolean any = false;
            for (MethodDescription method : type.getDeclaredMethods()) {
                if (Boolean.TRUE.equals(flag(method.getDeclaredAnnotations()))) {
                    any = true;
                    break;
                }
            }
            selected = new Selected(type, any);
            last = selected;
        }
        return selected.selectsMethods();
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
