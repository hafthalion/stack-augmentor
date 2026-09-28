package com.hafnium.stackaugmentor.runtime;

/**
 * Java classes that inherit a getter as an interface default method: unlike Kotlin, javac does not compile the method
 * into the class.
 */
public final class JavaDefaults {

    private JavaDefaults() {
    }

    public interface Identified {
        default String getId() {
            return "id-7";
        }
    }

    public interface Tagged extends Identified {
    }

    public static class Order implements Tagged {
    }

    public static class RushOrder extends Order {
    }
}
