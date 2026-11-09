package edu.byu.chem.boltzmann.tools;

import java.util.List;

/**
 * Minimal JSON output for the boltzmann4d tools. Doubles use Double.toString, which
 * (since JDK 19) prints the shortest decimal that round-trips, so JavaScript parses
 * back exactly the same double.
 */
final class JsonWriter {
    private final StringBuilder out = new StringBuilder();
    private boolean needComma = false;

    JsonWriter beginObject() { separator(); out.append('{'); needComma = false; return this; }
    JsonWriter endObject() { out.append('}'); needComma = true; return this; }
    JsonWriter beginArray() { separator(); out.append('['); needComma = false; return this; }
    JsonWriter endArray() { out.append(']'); needComma = true; return this; }

    JsonWriter name(String name) {
        separator();
        string(name);
        out.append(':');
        needComma = false;
        return this;
    }

    JsonWriter value(String value) { separator(); string(value); needComma = true; return this; }
    JsonWriter value(long value) { separator(); out.append(value); needComma = true; return this; }
    JsonWriter value(boolean value) { separator(); out.append(value); needComma = true; return this; }

    JsonWriter value(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("JSON cannot hold " + value);
        separator();
        out.append(Double.toString(value));
        needComma = true;
        return this;
    }

    JsonWriter numbers(double[] values) {
        beginArray();
        for (double v : values) value(v);
        return endArray();
    }

    JsonWriter numbers(List<double[]> rows) {
        beginArray();
        for (double[] row : rows) for (double v : row) value(v);
        return endArray();
    }

    /** Starts a new line before the next value, to keep long files diff-friendly. */
    JsonWriter newline() { out.append('\n'); return this; }

    @Override public String toString() { return out.toString(); }

    private void separator() { if (needComma) out.append(','); }

    private void string(String s) {
        out.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
    }
}
