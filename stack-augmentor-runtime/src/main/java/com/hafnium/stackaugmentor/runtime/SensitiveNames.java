package com.hafnium.stackaugmentor.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tells from a parameter name whether its values are sensitive, for {@code "*#?"} and {@code "@#?"}. Names are split
 * into words at camel-case humps, digits, {@code _} and {@code $}; a name matches when a configured name equals one
 * of its words or several consecutive ones, ignoring case. So {@code "email"} matches {@code eMail}, {@code userEmail} and
 * {@code EMAIL_ADDRESS}, {@code "firstName"} matches {@code first_name}, but {@code "pin"} does not match
 * {@code shipping}.
 */
final class SensitiveNames {

    private final Set<String> names = new HashSet<>();

    SensitiveNames(List<String> sensitive) {
        for (String name : sensitive) {
            names.add(String.join("", words(name)));
        }
    }

    boolean matches(String parameterName) {
        List<String> words = words(parameterName);
        for (int from = 0; from < words.size(); from++) {
            StringBuilder joined = new StringBuilder();
            for (int to = from; to < words.size(); to++) {
                joined.append(words.get(to));
                if (names.contains(joined.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The lower-case words of a name: {@code userEMailAddress2} is {@code user, e, mail, address}. */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetter(c)) {
                flush(word, words);
                continue;
            }
            boolean hump = Character.isUpperCase(c) && i > 0 && (Character.isLowerCase(name.charAt(i - 1))
                    || i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)) && Character.isUpperCase(name.charAt(i - 1)));
            if (hump) {
                flush(word, words);
            }
            word.append(c);
        }
        flush(word, words);
        return words;
    }

    private static void flush(StringBuilder word, List<String> words) {
        if (!word.isEmpty()) {
            words.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }
}
