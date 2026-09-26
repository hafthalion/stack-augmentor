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
        // The JDK prints declaringClass + "." + methodName, so the template is split at ".{method}".
        int split = frameFormat.indexOf(".{method}");
        if (split < 0 || frameFormat.indexOf("{method}") != split + 1 || frameFormat.lastIndexOf("{method}") != split + 1) {
            throw new ConfigException(
                    "frameFormat must contain '.{method}' exactly once, "
                            + "because the JDK prints '<class>.<method>(<file>:<line>)'; was '" + frameFormat + "'");
        }
        List<Token> declaringClassPart = parse(frameFormat.substring(0, split), FRAME_PLACEHOLDERS, "frameFormat");
        List<Token> methodPart = parse(frameFormat.substring(split + 1), FRAME_PLACEHOLDERS, "frameFormat");
        List<Token> receiver = parseIdTemplate(receiverFormat, "receiverFormat");
        return new FrameFormat(declaringClassPart, methodPart, receiver, parseParams(paramsFormat), maxParams);
    }

    private static ParamsTemplate parseParams(String template) {
        int repeat = template.lastIndexOf(REPEAT);
        if (repeat < 0) {
            List<Token> item = parseIdTemplate(template, "paramsFormat");
            requirePlaceholder(item, template);
            return new ParamsTemplate("", item, DEFAULT_SEPARATOR, "");
        }
        List<Token> head = parseIdTemplate(template.substring(0, repeat), "paramsFormat");
        List<Token> tail = parseIdTemplate(template.substring(repeat + REPEAT.length()), "paramsFormat");
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
     * Splits a receiver or parameter template into literals and the placeholders {@code $name} and {@code $id}.
     * Everything else, braces included, is literal; {@code $$} is a literal {@code $}.
     */
    private static List<Token> parseIdTemplate(String template, String key) {
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
            int end = i + 1;
            while (end < template.length() && Character.isLetterOrDigit(template.charAt(end))) {
                end++;
            }
            String name = template.substring(i + 1, end);
            if (!ID_PLACEHOLDERS.contains(name)) {
                throw new ConfigException(
                        key + " uses unknown placeholder '$" + name + "' at position " + i
                                + "; allowed: $name, $id (write $$ for a literal $)");
            }
            flush(literal, tokens);
            tokens.add(new Token.Placeholder(name));
            i = end;
        }
        flush(literal, tokens);
        return tokens;
    }

    /** Splits the frame template into literals and {@code {placeholder}}s; doubled braces are literal braces. */
    private static List<Token> parse(String template, List<String> allowed, String key) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{' && template.startsWith("{{", i)) {
                literal.append('{');
                i++;
            } else if (c == '}' && template.startsWith("}}", i)) {
                literal.append('}');
                i++;
            } else if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) {
                    throw new ConfigException(key + " has an unclosed '{' at position " + i + ": '" + template + "'");
                }
                String name = template.substring(i + 1, end);
                if (!allowed.contains(name)) {
                    String names = allowed.stream().map(it -> "{" + it + "}").collect(Collectors.joining(", "));
                    throw new ConfigException(key + " uses unknown placeholder {" + name + "}; allowed: " + names);
                }
                flush(literal, tokens);
                tokens.add(new Token.Placeholder(name));
                i = end;
            } else if (c == '}') {
                throw new ConfigException(
                        key + " has an unmatched '}' at position " + i + " (write '}}' for a literal brace): '" + template + "'");
            } else {
                literal.append(c);
            }
            i++;
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
