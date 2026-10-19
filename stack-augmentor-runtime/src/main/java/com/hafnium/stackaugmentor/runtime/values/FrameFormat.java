package com.hafnium.stackaugmentor.runtime.values;

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.ConfigException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Renders frames from the {@code frameFormat}, {@code receiverFormat} and {@code paramsFormat} templates.
 * All templates are parsed and validated once, when the format is created.
 */
public final class FrameFormat {

    private static final List<String> FRAME_PLACEHOLDERS = List.of("class", "simpleClass", "method", "receiver", "params");
    private static final List<String> VALUE_PLACEHOLDERS = List.of("name", "value");
    private static final String REPEAT = "...";
    private static final String DEFAULT_SEPARATOR = ",";

    private final List<Token> declaringClassPart;
    private final List<Token> methodPart;
    private final ValuesTemplate receiver;
    private final ValuesTemplate params;

    private FrameFormat(List<Token> declaringClassPart, List<Token> methodPart, ValuesTemplate receiver, ValuesTemplate params) {
        this.declaringClassPart = declaringClassPart;
        this.methodPart = methodPart;
        this.receiver = receiver;
        this.params = params;
    }

    /** A replacement element whose {@code toString()} shows the values. */
    public StackTraceElement rewrite(StackTraceElement element, List<NamedValue> receiverValues, List<NamedValue> paramValues) {
        UnaryOperator<String> values = values(element, receiverValues, paramValues);
        String declaringClass = render(declaringClassPart, values);
        String method = render(methodPart, values);
        String prefix = prefixOf(element);
        String loaderName = element.getClassLoaderName();
        String loader = loaderName != null && !loaderName.isEmpty() && prefix.startsWith(loaderName + "/") ? loaderName : null;
        String rest = loader != null ? prefix.substring(loader.length() + 1) : prefix;
        String moduleName = element.getModuleName();
        String module = moduleName != null && !moduleName.isEmpty() && rest.startsWith(moduleName) ? moduleName : null;
        String moduleVersion = element.getModuleVersion();
        String version = moduleVersion != null && module != null && rest.startsWith(module + "@" + moduleVersion) ? moduleVersion : null;
        return new StackTraceElement(loader, module, version, declaringClass, method, element.getFileName(), element.getLineNumber());
    }

    private UnaryOperator<String> values(StackTraceElement element, List<NamedValue> receiverValues, List<NamedValue> paramValues) {
        return name -> switch (name) {
            case "class" -> element.getClassName();
            case "simpleClass" -> element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1);
            case "method" -> element.getMethodName();
            case "receiver" -> receiver.render(receiverValues);
            case "params" -> params.render(paramValues);
            default -> throw new IllegalStateException("unexpected placeholder " + name);
        };
    }

    private sealed interface Token {

        record Literal(String text) implements Token {
        }

        record Placeholder(String name) implements Token {
        }
    }

    /**
     * A {@code receiverFormat} or {@code paramsFormat}: a prefix, the item repeated for each value with a separator in
     * between, and a suffix. Empty without values.
     */
    private record ValuesTemplate(String prefix, List<Token> item, String separator, String suffix) {

        String render(List<NamedValue> values) {
            if (values.isEmpty()) {
                return "";
            }
            List<String> items = new ArrayList<>(values.size());
            for (NamedValue value : values) {
                items.add(FrameFormat.render(item, values(value)));
            }
            return prefix + String.join(separator, items) + suffix;
        }
    }

    public static FrameFormat create(AugmentorConfig config) {
        return create(config.frameFormat(), config.receiverFormat(), config.paramsFormat());
    }

    public static FrameFormat create(String frameFormat, String receiverFormat, String paramsFormat) {
        rejectParentheses(frameFormat, "frameFormat");
        rejectParentheses(receiverFormat, "receiverFormat");
        rejectParentheses(paramsFormat, "paramsFormat");
        // The JDK prints declaringClass + "." + methodName, so the template is split at ".$method" (or ".${method}").
        List<Token> frame = parse(frameFormat, FRAME_PLACEHOLDERS, "frameFormat");
        int method = -1;
        for (int i = 0; i < frame.size(); i++) {
            if (frame.get(i) instanceof Token.Placeholder placeholder && placeholder.name().equals("method")) {
                if (method >= 0) {
                    method = -1;
                    break;
                }
                method = i;
            }
        }
        if (method < 1 || !(frame.get(method - 1) instanceof Token.Literal before) || !before.text().endsWith(".")) {
            throw new ConfigException("frameFormat must contain '.$method' (or '.${method}') exactly once, because the JDK prints "
                    + "'<class>.<method>(<file>:<line>)'; was '" + frameFormat + "'");
        }
        List<Token> declaringClassPart = new ArrayList<>(frame.subList(0, method - 1));
        String beforeDot = before.text().substring(0, before.text().length() - 1);
        if (!beforeDot.isEmpty()) {
            declaringClassPart.add(new Token.Literal(beforeDot));
        }
        List<Token> methodPart = List.copyOf(frame.subList(method, frame.size()));
        return new FrameFormat(List.copyOf(declaringClassPart), methodPart, parseValues(receiverFormat, "receiverFormat"),
                parseValues(paramsFormat, "paramsFormat"));
    }

    /** IDEs find a frame's file and line by the parenthesised {@code (File.java:12)} that the JDK appends. */
    private static void rejectParentheses(String template, String key) {
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '(' || c == ')') {
                throw new ConfigException(key + " must not contain '" + c + "' (at position " + i + "), because IDEs find "
                        + "a frame's file by the '(File.java:12)' at its end; use e.g. '{' and '}' or '[' and ']'; was '"
                        + template + "'");
            }
        }
    }

    private static ValuesTemplate parseValues(String template, String key) {
        int repeat = template.lastIndexOf(REPEAT);
        if (repeat < 0) {
            List<Token> item = parse(template, VALUE_PLACEHOLDERS, key);
            requirePlaceholder(item, template, key);
            return new ValuesTemplate("", item, DEFAULT_SEPARATOR, "");
        }
        List<Token> head = parse(template.substring(0, repeat), VALUE_PLACEHOLDERS, key);
        List<Token> tail = parse(template.substring(repeat + REPEAT.length()), VALUE_PLACEHOLDERS, key);
        requirePlaceholder(head, template, key);
        if (tail.stream().anyMatch(token -> token instanceof Token.Placeholder)) {
            throw new ConfigException(key + " must not have placeholders after '" + REPEAT + "'; was '" + template + "'");
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < head.size(); i++) {
            if (head.get(i) instanceof Token.Placeholder) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        return new ValuesTemplate(
                literalText(head.subList(0, first)),
                List.copyOf(head.subList(first, last + 1)),
                literalText(head.subList(last + 1, head.size())),
                literalText(tail));
    }

    private static void requirePlaceholder(List<Token> tokens, String template, String key) {
        if (tokens.stream().noneMatch(token -> token instanceof Token.Placeholder)) {
            throw new ConfigException(key + " must contain $name or $value; was '" + template + "'");
        }
    }

    private static String literalText(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            text.append(((Token.Literal) token).text());
        }
        return text.toString();
    }

    /**
     * Splits a template into literals and placeholders: {@code $name}, where the name ends at the first character that
     * is not a letter or digit, or {@code ${name}}. Everything else, braces included, is literal; {@code $$} is a
     * literal {@code $}.
     */
    private static List<Token> parse(String template, List<String> allowed, String key) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '$') {
                literal.append(c);
                i++;
                continue;
            }
            if (template.startsWith("$$", i)) {
                literal.append('$');
                i += 2;
                continue;
            }
            String name;
            int end;
            if (template.startsWith("${", i)) {
                int close = template.indexOf('}', i + 2);
                if (close < 0) {
                    throw new ConfigException(key + " has an unclosed '${' at position " + i + ": '" + template + "'");
                }
                name = template.substring(i + 2, close);
                end = close + 1;
            } else {
                end = i + 1;
                while (end < template.length() && Character.isLetterOrDigit(template.charAt(end))) {
                    end++;
                }
                name = template.substring(i + 1, end);
            }
            if (!allowed.contains(name)) {
                String names = allowed.stream().map(it -> "$" + it).collect(Collectors.joining(", "));
                throw new ConfigException(
                        key + " uses unknown placeholder '$" + name + "' at position " + i
                                + "; allowed: " + names + " (write $$ for a literal $)");
            }
            flush(literal, tokens);
            tokens.add(new Token.Placeholder(name));
            i = end;
        }
        flush(literal, tokens);
        return tokens;
    }

    private static void flush(StringBuilder literal, List<Token> tokens) {
        if (!literal.isEmpty()) {
            tokens.add(new Token.Literal(literal.toString()));
        }
        literal.setLength(0);
    }

    private static String render(List<Token> tokens, UnaryOperator<String> values) {
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            switch (token) {
                case Token.Literal literal -> text.append(literal.text());
                case Token.Placeholder placeholder -> text.append(values.apply(placeholder.name()));
            }
        }
        return text.toString();
    }

    private static UnaryOperator<String> values(NamedValue value) {
        return name -> name.equals("name") ? value.name() : value.value();
    }

    /** The class loader / module part the JDK prints before the class name, e.g. {@code app//} or {@code java.base/}. */
    private static String prefixOf(StackTraceElement element) {
        String text = element.toString();
        int index = text.indexOf(element.getClassName() + "." + element.getMethodName() + "(");
        return index > 0 ? text.substring(0, index) : "";
    }
}
