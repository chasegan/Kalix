package com.kalix.ide.linter.schema;

import java.util.regex.Pattern;

/**
 * Defines a data type in the linter schema with pattern matching capabilities.
 */
public class DataType {
    public String name;
    public String pattern;
    public String parse;
    public Pattern compiledPattern;

    public boolean matches(String value) {
        return compiledPattern != null && compiledPattern.matcher(value).matches();
    }
}