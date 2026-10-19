package com.hafnium.stackaugmentor.runtime.config;

import java.util.List;

/** The receiver members an {@code [augment.receiver]} entry names. */
public sealed interface ReceiverSpec {

    /** One field or no-argument method, shown as one value. */
    sealed interface MemberSpec extends ReceiverSpec {

        String memberName();
    }

    record FieldSpec(String memberName) implements MemberSpec {
    }

    record MethodSpec(String memberName) implements MemberSpec {
    }

    /** A list of fields and methods, e.g. {@code "tenant, id()"}: one value each, in this order. */
    record MemberList(List<MemberSpec> members) implements ReceiverSpec {

        public MemberList {
            members = List.copyOf(members);
        }
    }

    /** {@code "-"}: no receiver members for the methods its key names, even if a later entry would name some. Parameter values are not affected. */
    record Excluded() implements ReceiverSpec {
    }
}
