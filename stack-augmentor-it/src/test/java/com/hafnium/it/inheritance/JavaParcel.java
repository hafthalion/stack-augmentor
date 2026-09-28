package com.hafnium.it.inheritance;

/** Entry "code": implements JavaLabeled. */
public final class JavaParcel implements JavaLabeled {

    private final String code;

    public JavaParcel(String code) {
        this.code = code;
    }

    @Override
    public String label() {
        return "java-parcel-" + code;
    }
}
