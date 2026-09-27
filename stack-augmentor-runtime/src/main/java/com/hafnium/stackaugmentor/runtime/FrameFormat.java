package com.hafnium.stackaugmentor.runtime;

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
    private static final List<String> ID_PLACEHOLDERS = List.of("name", "id");
    private static final String REPEAT = "...";
    private static final String DEFAULT_SEPARATOR = ",";
    /** The last item of a parameter list that was cut at maxParams. */
    private static final String OMITTED = "…";

    private final List<Token> declaringClassPart;
    private final List<Token> methodPart;
    private final List<Token> receiver;
    private final ParamsTemplate params;
    private final int maxParams;

    private FrameFormat(List<Token> declaringClassPart, List<Token> methodPart, List<Token> receiver, ParamsTemplate params,
                        int maxParams) {
        this.declaringClassPart = declaringClassPart;
        this.methodPart = methodPart;
        this.receiver = receiver;
        this.params = params;
        this.maxParams = maxParams;
    }

    /** The most parameter ids shown per frame. */
    public int maxParams() {
        return maxParams;
    }

    /**
     * A replacement element whose {@code toString()} shows the ids. Of the parameter ids, the first {@link #maxParams()}
     * are shown, followed by {@code …} if there are more.
     */
    public StackTraceElement rewrite(StackTraceElement element, NamedId receiverId, List<NamedId> paramIds) {
        if (paramIds.size() > maxParams) {
            return rewrite(element, receiverId, paramIds.subList(0, maxParams), paramIds.size() - maxParams);
        }
        return rewrite(element, receiverId, paramIds, 0);
    }

    /**
     * A replacement element whose {@code toString()} shows the ids, for parameter ids already cut to {@link #maxParams()}:
     * {@code omitted} is the number of parameters left out, shown as {@code …}.
     */
    public StackTraceElement rewrite(StackTraceElement element, NamedId receiverId, List<NamedId> paramIds, int omitted) {
        UnaryOperator<String> values = values(element, receiverId, paramIds, omitted);
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

    private UnaryOperator<String> values(StackTraceElement element, NamedId receiverId, List<NamedId> paramIds, int omitted) {
        return name -> switch (name) {
            case "class" -> element.getClassName();
            case "simpleClass" -> element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1);
            case "method" -> element.getMethodName();
            case "receiver" -> receiverId != null ? render(receiver, values(receiverId)) : "";
            case "params" -> params.render(paramIds, omitted);
            default -> throw new IllegalStateException("unexpected placeholder " + name);
        };
    }

    private sealed interface Token {

        record Literal(String text) implements Token {
        }

        record Placeholder(String name) implements Token {
        }
    }

    private record ParamsTemplate(String prefix, List<Token> item, String separator, String suffix) {

        String render(List<NamedId> ids, int omitted) {
            if (ids.isEmpty() && omitted == 0) {
                return "";
            }
            List<String> items = new ArrayList<>(ids.size() + 1);
            for (NamedId id : ids) {
                items.add(FrameFormat.render(item, values(id)));
            }
            if (omitted > 0) {
                items.add(OMITTED);
            }
            return prefix + String.join(separator, items) + suffix;
        }
    }

    public static FrameFormat create(AugmentorConfig config) {
        return create(config.frameFormat(), config.receiverFormat(), config.paramsFormat(), config.maxParams());
    }

    public static FrameFormat create(String frameFormat, String receiverFormat, String paramsFormat) {
        return create(frameFormat, receiverFormat, paramsFormat, AugmentorConfig.DEFAULT_MAX_PARAMS);
    }

    public static FrameFormat create(String frameFormat, String receiverFormat, String paramsFormat, int maxParams) {
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
            String hint = frameFormat.contains("{method}") && !frameFormat.contains("${method}") ? " (placeholders are written $class, $method, ...; braces are literal)" : "";
            throw new ConfigException("frameFormat must contain '.$method' (or '.${method}') exactly once, because the JDK prints "
                    + "'<class>.<method>(<file>:<line>)'; was '" + frameFormat + "'" + hint);
        }
        List<Token> declaringClassPart = new ArrayList<>(frame.subList(0, method - 1));
        String beforeDot = before.text().substring(0, before.text().length() - 1);
        if (!beforeDot.isEmpty()) {
            declaringClassPart.add(new Token.Literal(beforeDot));
        }
        List<Token> methodPart = List.copyOf(frame.subList(method, frame.size()));
        List<Token> receiver = parse(receiverFormat, ID_PLACEHOLDERS, "receiverFormat");
        return new FrameFormat(List.copyOf(declaringClassPart), methodPart, receiver, parseParams(paramsFormat), maxParams);
    }

    private static ParamsTemplate parseParams(String template) {
        int repeat = template.lastIndexOf(REPEAT);
        if (repeat < 0) {
            List<Token> item = parse(template, ID_PLACEHOLDERS, "paramsFormat");
            requirePlaceholder(item, template);
            return new ParamsTemplate("", item, DEFAULT_SEPARATOR, "");
        }
        List<Token> head = parse(template.substring(0, repeat), ID_PLACEHOLDERS, "paramsFormat");
        List<Token> tail = parse(template.substring(repeat + REPEAT.length()), ID_PLACEHOLDERS, "paramsFormat");
        requirePlaceholder(head, template);
        if (tail.stream().anyMatch(token -> token instanceof Token.Placeholder)) {
            throw new ConfigException("paramsFormat must not have placeholders after '" + REPEAT + "'; was '" + template + "'");
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
        return new ParamsTemplate(
                literalText(head.subList(0, first)),
                List.copyOf(head.subList(first, last + 1)),
                literalText(head.subList(last + 1, head.size())),
                literalText(tail));
    }

    private static void requirePlaceholder(List<Token> tokens, String template) {
        if (tokens.stream().noneMatch(token -> token instanceof Token.Placeholder)) {
            throw new ConfigException("paramsFormat must contain $name or $id; was '" + template + "'");
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

    private static UnaryOperator<String> values(NamedId id) {
        return name -> name.equals("name") ? id.name() : id.id();
    }

    /** The class loader / module part the JDK prints before the class name, e.g. {@code app//} or {@code java.base/}. */
    private static String prefixOf(StackTraceElement element) {
        String text = element.toString();
        int index = text.indexOf(element.getClassName() + "." + element.getMethodName() + "(");
        return index > 0 ? text.substring(0, index) : "";
    }
}
