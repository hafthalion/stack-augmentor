package com.hafnium.stackaugmentor.runtime.config;

import java.util.List;

/** The receiver id sources an {@code [augment.receiver]} entry names. */
public sealed interface IdSpec {

    /** One field or no-argument method, giving one receiver id. */
    sealed interface MemberSpec extends IdSpec {

        String memberName();
    }

    record FieldSpec(String memberName) implements MemberSpec {
    }

    record MethodSpec(String memberName) implements MemberSpec {
    }

    /** A list of fields and methods, e.g. {@code "tenant, id()"}: one receiver id each, in this order. */
    record MemberList(List<MemberSpec> members) implements IdSpec {

        public MemberList {
            members = List.copyOf(members);
        }
    }

    /**
     * The {@code @AugmentedReceiver} members of the class, one receiver member each: for the methods that no
     * {@code [augment.receiver]} entry matches, where {@code [augment.annotations]} selects the class.
     */
    record Annotations() implements IdSpec {
    }

    /** {@code "-"}: no receiver id for the methods its key names, even if a later entry would give one. Parameter ids are not affected. */
    record Excluded() implements IdSpec {
    }
}
