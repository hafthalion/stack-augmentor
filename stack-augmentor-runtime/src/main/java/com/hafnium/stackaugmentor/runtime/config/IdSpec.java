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

    /** A list of fields and methods, e.g. {@code ["tenant", "id()"]}: one receiver id each, in this order. */
    record MemberList(List<MemberSpec> members) implements IdSpec {

        public MemberList {
            members = List.copyOf(members);
        }
    }

    /**
     * {@code "@"}: the {@code @StackTraceId} members of the class, one receiver id each. The parameter annotations are
     * {@code [augment.params]}'s.
     */
    record Annotations() implements IdSpec {
    }

    /** {@code "-"}: no receiver id, even if a less specific entry would give one. Parameter ids are not affected. */
    record Excluded() implements IdSpec {
    }
}
