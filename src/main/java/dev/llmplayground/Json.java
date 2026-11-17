package dev.llmplayground;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        appendValue(result, value);
        return result.toString();
    }

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    private static void appendValue(StringBuilder result, Object value) {
        if (value == null) {
            result.append("null");
        } else if (value instanceof String text) {
            appendString(result, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            result.append(value);
        } else if (value instanceof Map<?, ?> map) {
            result.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    result.append(',');
                }
                appendString(result, key);
                result.append(':');
                appendValue(result, entry.getValue());
                first = false;
            }
            result.append('}');
        } else if (value instanceof Iterable<?> values) {
            result.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) {
                    result.append(',');
                }
                appendValue(result, item);
                first = false;
            }
            result.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
        }
    }

    private static void appendString(StringBuilder result, String value) {
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        result.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("Expected a value");
            }
            return switch (input.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            index++;
            Map<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(index) != '"') {
                    throw error("Expected an object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                values.put(key, readValue());
                skipWhitespace();
                if (consume('}')) {
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            index++;
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return values;
            }
            while (true) {
                values.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (atEnd()) {
                        throw error("Incomplete escape sequence");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(readUnicodeEscape());
                        default -> throw error("Invalid escape sequence");
                    }
                } else if (character < 0x20) {
                    throw error("Unescaped control character");
                } else {
                    value.append(character);
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = input.substring(index, index + 4);
            try {
                char decoded = (char) Integer.parseInt(hex, 16);
                index += 4;
                return decoded;
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Object readNumber() {
            int start = index;
            consume('-');
            if (!consume('0')) {
                readDigits();
            }
            if (consume('.')) {
                readDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                readDigits();
            }
            if (start == index) {
                throw error("Expected a value");
            }
            String number = input.substring(start, index);
            try {
                return Double.valueOf(number);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private void readDigits() {
            int start = index;
            while (!atEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("Expected a digit");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("Invalid value");
            }
            index += literal.length();
            return value;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean atEnd() {
            return index >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + index);
        }
    }
}
