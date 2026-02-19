package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationContext;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates function expressions used in model parameters.
 * Supports seven types of inputs:
 * - Data references: "data.evap", "data.field.subfield"
 * - Constant references: "c.pi", "c.node_1_demand_levels.high"
 * - Node output references: "node.node13_inflow.ds_1"
 * - This references: "this.dsflow", "this.volume" (shorthand for current node outputs)
 * - Sim references: "sim.year", "sim.month", "sim.day", "sim.day_of_year", "sim.step"
 * - Constant expressions: "5.0", "2 + 3"
 * - Complex functions: "if(data.temp > 20, 10.0, 5.0) * 1.2"
 *
 * Performance target: < 10ms per expression validation
 */
public class FunctionExpressionValidator {

    // Cache validation results for performance
    private static final Map<String, List<String>> validationCache =
        new ConcurrentHashMap<>(100);
    private static final int MAX_CACHE_SIZE = 500;

    // Known functions with their argument counts (-1 = variable args, must be >= 2)
    private static final Map<String, Integer> KNOWN_FUNCTIONS = createFunctionMap();

    // Known simulation variables
    private static final Set<String> KNOWN_SIM_VARIABLES = Set.of(
        "sim.year", "sim.month", "sim.day", "sim.day_of_year", "sim.step"
    );

    private static Map<String, Integer> createFunctionMap() {
        Map<String, Integer> map = new HashMap<>();

        // Conditional
        map.put("if", 3);

        // Aggregation (variable args)
        map.put("max", -1);
        map.put("min", -1);
        map.put("sum", -1);
        map.put("mean", -1);

        // Single argument math
        map.put("abs", 1);
        map.put("sqrt", 1);
        map.put("sin", 1);
        map.put("cos", 1);
        map.put("tan", 1);
        map.put("asin", 1);
        map.put("acos", 1);
        map.put("atan", 1);
        map.put("sinh", 1);
        map.put("cosh", 1);
        map.put("tanh", 1);
        map.put("ln", 1);
        map.put("log", 1);
        map.put("log10", 1);
        map.put("exp", 1);
        map.put("ceil", 1);
        map.put("floor", 1);
        map.put("round", 1);

        // Two argument math
        map.put("pow", 2);
        map.put("atan2", 2);

        return Collections.unmodifiableMap(map);
    }

    /**
     * Validate a function expression and return a list of error messages.
     * Returns empty list if expression is valid.
     * Note: This method cannot validate node or 'this' references without context.
     *
     * @deprecated Use {@link #validate(String, ValidationContext)} instead
     */
    @Deprecated
    public List<String> validate(String expression) {
        return validate(expression, ValidationContext.empty());
    }

    /**
     * Validate a function expression with model context for node reference validation.
     * Returns empty list if expression is valid.
     *
     * @param expression The expression to validate
     * @param model The parsed model (optional, for node reference validation)
     * @param schema The linter schema (optional, for node reference validation)
     * @return List of error messages, empty if valid
     * @deprecated Use {@link #validate(String, ValidationContext)} instead
     */
    @Deprecated
    public List<String> validate(String expression, INIModelParser.ParsedModel model, LinterSchema schema) {
        ValidationContext context = ValidationContext.builder()
            .model(model)
            .schema(schema)
            .build();
        return validate(expression, context);
    }

    /**
     * Validate a function expression with full context.
     * Returns empty list if expression is valid.
     *
     * <p>The context enables validation of:</p>
     * <ul>
     *   <li>node.xxx.yyy references - validates node exists and output is allowed</li>
     *   <li>this.yyy references - validates output is allowed for current node type</li>
     * </ul>
     *
     * @param expression The expression to validate
     * @param context The validation context (use {@link ValidationContext#empty()} for basic validation)
     * @return List of error messages, empty if valid
     */
    public List<String> validate(String expression, ValidationContext context) {
        if (expression == null) {
            return List.of("Expression is null");
        }
        if (context == null) {
            context = ValidationContext.empty();
        }

        String trimmed = expression.trim();

        // Check cache first (only if no context, since validation may differ)
        if (!context.hasModelAndSchema() && !context.hasCurrentNode() && validationCache.containsKey(trimmed)) {
            return validationCache.get(trimmed);
        }

        List<String> errors = performValidation(trimmed, context);

        // Cache result (with size limit, only if no context)
        if (!context.hasModelAndSchema() && !context.hasCurrentNode() && validationCache.size() < MAX_CACHE_SIZE) {
            validationCache.put(trimmed, errors);
        }

        return errors;
    }

    /**
     * Clear the validation cache. Useful for testing or memory management.
     */
    public static void clearCache() {
        validationCache.clear();
    }

    private List<String> performValidation(String expression, ValidationContext context) {
        List<String> errors = new ArrayList<>();

        // Fast path: empty expression
        if (expression.isEmpty()) {
            errors.add("Expression is empty");
            return errors;
        }

        // Fast path: simple number
        if (isSimpleNumber(expression)) {
            return errors; // Valid
        }

        // Fast path: simple data reference
        if (isSimpleDataReference(expression)) {
            return errors; // Valid
        }

        // Fast path: simple constant reference
        if (isSimpleConstantReference(expression)) {
            return errors; // Valid
        }

        // Fast path: simple node reference
        if (isSimpleNodeReference(expression)) {
            // Validate node reference if model/schema available
            if (context.hasModelAndSchema()) {
                // Strip optional square brackets before validation
                String refWithoutBrackets = expression.replaceFirst("\\[.*?\\]$", "");
                String error = ValidationUtils.validateNodeReference(refWithoutBrackets, context.getModel(), context.getSchema());
                if (error != null) {
                    errors.add(error);
                }
            }
            return errors; // Valid format-wise
        }

        // Fast path: simple this reference
        if (isSimpleThisReference(expression)) {
            validateThisReference(expression, context, errors);
            return errors;
        }

        // Fast path: simple sim reference
        if (isSimpleSimReference(expression)) {
            return errors; // Valid
        }

        // Complex expression - tokenize and parse
        try {
            Tokenizer tokenizer = new Tokenizer(expression);
            Parser parser = new Parser(tokenizer, context);
            parser.parseExpression(errors);

            // Check for trailing tokens
            if (parser.current.type != TokenType.EOF) {
                errors.add("Unexpected tokens after expression: '" + parser.current.value + "'");
            }

        } catch (ParseException e) {
            errors.add(e.getMessage());
        } catch (Exception e) {
            errors.add("Failed to parse expression: " + e.getMessage());
        }

        return errors;
    }

    // Fast path checks using simple regex
    private static boolean isSimpleNumber(String s) {
        return s.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");
    }

    private static boolean isSimpleDataReference(String s) {
        // Matches: data.xxx or data.xxx.yyy.zzz (dots and underscores allowed)
        // Optional square brackets at the end: data.xxx[anything]
        return s.matches("^data\\.[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*(\\[.*?\\])?$");
    }

    private static boolean isSimpleConstantReference(String s) {
        // Matches: c.xxx or c.xxx.yyy.zzz (dots and underscores allowed)
        return s.matches("^c\\.[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    }

    private static boolean isSimpleNodeReference(String s) {
        // Matches: node.xxx.yyy (node.nodename.property)
        // Optional square brackets at the end: node.xxx.yyy[anything]
        return s.matches("^node\\.[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_]*(\\[.*?\\])?$");
    }

    private static boolean isSimpleSimReference(String s) {
        return KNOWN_SIM_VARIABLES.contains(s);
    }

    private static boolean isSimpleThisReference(String s) {
        // Matches: this.xxx (this.property)
        // Optional square brackets at the end: this.xxx[anything]
        return s.matches("^this\\.[a-zA-Z_][a-zA-Z0-9_]*(\\[.*?\\])?$");
    }

    private void validateThisReference(String thisRef, ValidationContext context, List<String> errors) {
        // Strip optional square brackets for validation
        String refWithoutBrackets = thisRef.replaceFirst("\\[.*?\\]$", "");

        // Extract the output property (everything after "this.")
        String outputProperty = refWithoutBrackets.substring(5); // "this.".length() == 5

        // Check if we have current node context
        if (!context.hasCurrentNode()) {
            errors.add("Cannot use 'this' reference outside of node context: '" + thisRef + "'");
            return;
        }

        // Get allowed outputs for current node type
        Set<String> allowedOutputs = context.getCurrentNodeAllowedOutputs();

        // If no schema or node type definition, we can't validate further
        if (allowedOutputs.isEmpty()) {
            // No validation possible - allow it
            return;
        }

        // Check if the output property is allowed
        if (!allowedOutputs.contains(outputProperty)) {
            String nodeType = context.getCurrentNodeType();
            errors.add("Output property '" + outputProperty + "' is not allowed for node type '" + nodeType +
                      "'. Allowed outputs: " + allowedOutputs);
        }
    }

    // ==================== Tokenizer ====================

    enum TokenType {
        NUMBER, IDENT, DATA_REF, CONST_REF, NODE_REF, THIS_REF, SIM_REF, OPERATOR, LPAREN, RPAREN, COMMA, EOF
    }

    static class Token {
        TokenType type;
        String value;
        int position;

        Token(TokenType type, String value, int position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }

        @Override
        public String toString() {
            return type + "(" + value + ")";
        }
    }

    static class Tokenizer {
        private final String input;
        private int pos = 0;

        Tokenizer(String input) {
            this.input = input;
        }

        Token nextToken() throws ParseException {
            skipWhitespace();

            if (pos >= input.length()) {
                return new Token(TokenType.EOF, "", pos);
            }

            char ch = input.charAt(pos);

            // Numbers (including decimals and scientific notation)
            if (Character.isDigit(ch) || (ch == '.' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                return readNumber();
            }

            // Identifiers and data references
            if (Character.isLetter(ch) || ch == '_') {
                return readIdentOrDataRef();
            }

            // Operators (support multi-char operators)
            if (isOperatorChar(ch)) {
                return readOperator();
            }

            // Punctuation
            if (ch == '(') {
                pos++;
                return new Token(TokenType.LPAREN, "(", pos - 1);
            }
            if (ch == ')') {
                pos++;
                return new Token(TokenType.RPAREN, ")", pos - 1);
            }
            if (ch == ',') {
                pos++;
                return new Token(TokenType.COMMA, ",", pos - 1);
            }

            throw new ParseException("Unexpected character at position " + pos + ": '" + ch + "'");
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private Token readNumber() throws ParseException {
            int start = pos;

            // Integer part
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }

            // Decimal part
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }

            // Scientific notation
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }

            String value = input.substring(start, pos);

            // Validate it's actually a valid number
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid number format: '" + value + "'");
            }

            return new Token(TokenType.NUMBER, value, start);
        }

        private Token readIdentOrDataRef() {
            int start = pos;

            // Read first segment (letters, digits, underscores)
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }

            String firstSegment = input.substring(start, pos);

            // Check if this is a data reference (starts with "data.")
            if (firstSegment.equals("data") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.DATA_REF);
            }

            // Check if this is a constant reference (starts with "c.")
            if (firstSegment.equals("c") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.CONST_REF);
            }

            // Check if this is a node reference (starts with "node.")
            if (firstSegment.equals("node") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.NODE_REF);
            }

            // Check if this is a sim reference (starts with "sim.")
            if (firstSegment.equals("sim") && pos < input.length() && input.charAt(pos) == '.') {
                return readSimReference(start);
            }

            // Check if this is a 'this' reference (starts with "this.")
            if (firstSegment.equals("this") && pos < input.length() && input.charAt(pos) == '.') {
                return readThisReference(start);
            }

            // Regular identifier (function name or variable)
            return new Token(TokenType.IDENT, firstSegment, start);
        }

        // Extract the common dotted reference reading logic
        private Token readDottedReference(int start, String prefix, TokenType tokenType) {
            StringBuilder sb = new StringBuilder(prefix);

            while (pos < input.length() && input.charAt(pos) == '.') {
                sb.append('.');
                pos++;

                // Check for consecutive dots or trailing dot
                if (pos >= input.length() || !Character.isLetterOrDigit(input.charAt(pos)) && input.charAt(pos) != '_') {
                    break; // Will be caught as malformed
                }

                int segStart = pos;
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                    pos++;
                }
                sb.append(input, segStart, pos);
            }

            // Check for optional square brackets (only for data and node references)
            if ((tokenType == TokenType.DATA_REF || tokenType == TokenType.NODE_REF) &&
                pos < input.length() && input.charAt(pos) == '[') {
                int bracketStart = pos;
                pos++; // consume '['

                // Find matching closing bracket
                while (pos < input.length() && input.charAt(pos) != ']') {
                    pos++;
                }

                if (pos < input.length() && input.charAt(pos) == ']') {
                    pos++; // consume ']'
                    sb.append(input, bracketStart, pos);
                }
                // If no closing bracket found, leave as-is (will be caught as error later)
            }

            return new Token(tokenType, sb.toString(), start);
        }

        // Read a sim reference (sim.year, sim.month, etc.)
        private Token readSimReference(int start) {
            StringBuilder sb = new StringBuilder("sim");

            // Consume the dot
            sb.append('.');
            pos++;

            // Read the variable name
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                sb.append(input.charAt(pos));
                pos++;
            }

            return new Token(TokenType.SIM_REF, sb.toString(), start);
        }

        // Read a 'this' reference (this.dsflow, this.volume, etc.)
        private Token readThisReference(int start) {
            StringBuilder sb = new StringBuilder("this");

            // Consume the dot
            sb.append('.');
            pos++;

            // Read the property name
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                sb.append(input.charAt(pos));
                pos++;
            }

            // Check for optional square brackets
            if (pos < input.length() && input.charAt(pos) == '[') {
                int bracketStart = pos;
                pos++; // consume '['

                // Find matching closing bracket
                while (pos < input.length() && input.charAt(pos) != ']') {
                    pos++;
                }

                if (pos < input.length() && input.charAt(pos) == ']') {
                    pos++; // consume ']'
                    sb.append(input, bracketStart, pos);
                }
            }

            return new Token(TokenType.THIS_REF, sb.toString(), start);
        }

        private boolean isOperatorChar(char ch) {
            return "+-*/%^<>=!&|".indexOf(ch) >= 0;
        }

        private Token readOperator() {
            int start = pos;
            char ch = input.charAt(pos);
            pos++;

            // Check for two-character operators
            if (pos < input.length()) {
                char next = input.charAt(pos);
                String twoChar = "" + ch + next;

                // Valid two-char operators: ==, !=, <=, >=, &&, ||, **
                if (twoChar.equals("==") || twoChar.equals("!=") ||
                    twoChar.equals("<=") || twoChar.equals(">=") ||
                    twoChar.equals("&&") || twoChar.equals("||") ||
                    twoChar.equals("**")) {
                    pos++;
                    return new Token(TokenType.OPERATOR, twoChar, start);
                }
            }

            return new Token(TokenType.OPERATOR, String.valueOf(ch), start);
        }
    }

    // ==================== Parser ====================

    static class Parser {
        private final Tokenizer tokenizer;
        private final ValidationContext context;
        Token current;

        Parser(Tokenizer tokenizer, ValidationContext context) throws ParseException {
            this.tokenizer = tokenizer;
            this.context = context;
            this.current = tokenizer.nextToken();
        }

        private void advance() throws ParseException {
            current = tokenizer.nextToken();
        }

        private void expect(TokenType type, List<String> errors) throws ParseException {
            if (current.type != type) {
                throw new ParseException("Expected " + type + " but got " + current.type + " ('" + current.value + "')");
            }
            advance();
        }

        // Expression := OrExpression
        void parseExpression(List<String> errors) throws ParseException {
            parseOrExpression(errors);
        }

        // OrExpression := AndExpression ( ('|' | '||') AndExpression )*
        private void parseOrExpression(List<String> errors) throws ParseException {
            parseAndExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("|") || current.value.equals("||"))) {

                // Check for C-style || operator
                if (current.value.equals("||")) {
                    errors.add("Invalid operator '||' - use '|' for logical OR");
                }

                advance();
                parseAndExpression(errors);
            }
        }

        // AndExpression := ComparisonExpression ( ('&' | '&&') ComparisonExpression )*
        private void parseAndExpression(List<String> errors) throws ParseException {
            parseComparisonExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("&") || current.value.equals("&&"))) {

                // Check for C-style && operator
                if (current.value.equals("&&")) {
                    errors.add("Invalid operator '&&' - use '&' for logical AND");
                }

                advance();
                parseComparisonExpression(errors);
            }
        }

        // ComparisonExpression := AdditiveExpression ( ('==' | '!=' | '<' | '<=' | '>' | '>=') AdditiveExpression )*
        private void parseComparisonExpression(List<String> errors) throws ParseException {
            parseAdditiveExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   isComparisonOp(current.value)) {

                // Check for single = (common mistake)
                if (current.value.equals("=")) {
                    errors.add("Invalid operator '=' - use '==' for equality comparison");
                    advance();
                    parseAdditiveExpression(errors);
                    continue;
                }

                advance();
                parseAdditiveExpression(errors);
            }
        }

        private boolean isComparisonOp(String op) {
            return op.equals("==") || op.equals("!=") || op.equals("<") ||
                   op.equals("<=") || op.equals(">") || op.equals(">=") || op.equals("=");
        }

        // AdditiveExpression := MultiplicativeExpression ( ('+' | '-') MultiplicativeExpression )*
        private void parseAdditiveExpression(List<String> errors) throws ParseException {
            parseMultiplicativeExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("+") || current.value.equals("-"))) {
                advance();
                parseMultiplicativeExpression(errors);
            }
        }

        // MultiplicativeExpression := PowerExpression ( ('*' | '/' | '%') PowerExpression )*
        private void parseMultiplicativeExpression(List<String> errors) throws ParseException {
            parsePowerExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("*") || current.value.equals("/") || current.value.equals("%"))) {

                String op = current.value;
                advance();

                // Check for division by zero constant
                if (op.equals("/") && current.type == TokenType.NUMBER && current.value.equals("0")) {
                    errors.add("Warning: Division by zero constant");
                }

                parsePowerExpression(errors);
            }
        }

        // PowerExpression := UnaryExpression ( ('^' | '**') UnaryExpression )*
        private void parsePowerExpression(List<String> errors) throws ParseException {
            parseUnaryExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("^") || current.value.equals("**"))) {
                advance();
                parseUnaryExpression(errors);
            }
        }

        // UnaryExpression := ('+' | '-' | '!')? PrimaryExpression
        private void parseUnaryExpression(List<String> errors) throws ParseException {
            if (current.type == TokenType.OPERATOR &&
                (current.value.equals("+") || current.value.equals("-") || current.value.equals("!"))) {
                advance();
            }
            parsePrimaryExpression(errors);
        }

        // PrimaryExpression := Number | DataRef | ConstRef | NodeRef | FunctionCall | '(' Expression ')'
        private void parsePrimaryExpression(List<String> errors) throws ParseException {
            if (current.type == TokenType.NUMBER) {
                advance();
            } else if (current.type == TokenType.DATA_REF) {
                validateDataReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.CONST_REF) {
                validateConstantReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.NODE_REF) {
                validateNodeReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.SIM_REF) {
                validateSimReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.THIS_REF) {
                validateThisRef(current.value, errors);
                advance();
            } else if (current.type == TokenType.IDENT) {
                // Function call
                parseFunctionCall(errors);
            } else if (current.type == TokenType.LPAREN) {
                advance();
                parseExpression(errors);
                expect(TokenType.RPAREN, errors);
            } else {
                throw new ParseException("Expected number, data reference, constant reference, node reference, this reference, sim reference, function, or '(' but got " + current.type);
            }
        }

        // FunctionCall := IDENT '(' ArgumentList? ')'
        private void parseFunctionCall(List<String> errors) throws ParseException {
            String funcName = current.value.toLowerCase();

            // Check if function is known
            if (!KNOWN_FUNCTIONS.containsKey(funcName)) {
                errors.add("Unknown function: '" + funcName + "'" + suggestFunction(funcName));
            }

            advance();
            expect(TokenType.LPAREN, errors);

            int argCount = 0;
            if (current.type != TokenType.RPAREN) {
                argCount = parseArgumentList(errors);
            }

            expect(TokenType.RPAREN, errors);

            // Validate argument count
            if (KNOWN_FUNCTIONS.containsKey(funcName)) {
                int expectedCount = KNOWN_FUNCTIONS.get(funcName);
                if (expectedCount >= 0 && argCount != expectedCount) {
                    errors.add("Function '" + funcName + "' expects " + expectedCount +
                               " argument" + (expectedCount == 1 ? "" : "s") + ", but got " + argCount);
                } else if (expectedCount == -1 && argCount < 2) {
                    errors.add("Function '" + funcName + "' expects at least 2 arguments, but got " + argCount);
                }
            }
        }

        // ArgumentList := Expression ( ',' Expression )*
        private int parseArgumentList(List<String> errors) throws ParseException {
            int count = 1;
            parseExpression(errors);

            while (current.type == TokenType.COMMA) {
                advance();
                parseExpression(errors);
                count++;
            }

            return count;
        }

        private void validateDataReference(String dataRef, List<String> errors) {
            // Strip optional square brackets for validation
            String refWithoutBrackets = dataRef.replaceFirst("\\[.*?\\]$", "");

            // Check for malformed data references
            if (refWithoutBrackets.contains("..")) {
                errors.add("Malformed data reference: '" + dataRef + "' (consecutive dots)");
            }
            if (refWithoutBrackets.endsWith(".")) {
                errors.add("Malformed data reference: '" + dataRef + "' (trailing dot)");
            }
            if (refWithoutBrackets.equals("data") || refWithoutBrackets.equals("data.")) {
                errors.add("Incomplete data reference: '" + dataRef + "'");
            }
        }

        private void validateConstantReference(String constRef, List<String> errors) {
            // Check for malformed constant references
            if (constRef.contains("..")) {
                errors.add("Malformed constant reference: '" + constRef + "' (consecutive dots)");
            }
            if (constRef.endsWith(".")) {
                errors.add("Malformed constant reference: '" + constRef + "' (trailing dot)");
            }
            if (constRef.equals("c") || constRef.equals("c.")) {
                errors.add("Incomplete constant reference: '" + constRef + "'");
            }
        }

        private void validateNodeReference(String nodeRef, List<String> errors) {
            // Strip optional square brackets for validation
            String refWithoutBrackets = nodeRef.replaceFirst("\\[.*?\\]$", "");

            // Check for malformed node references
            if (refWithoutBrackets.contains("..")) {
                errors.add("Malformed node reference: '" + nodeRef + "' (consecutive dots)");
                return;
            }
            if (refWithoutBrackets.endsWith(".")) {
                errors.add("Malformed node reference: '" + nodeRef + "' (trailing dot)");
                return;
            }
            if (refWithoutBrackets.equals("node") || refWithoutBrackets.equals("node.")) {
                errors.add("Incomplete node reference: '" + nodeRef + "'");
                return;
            }

            // Validate against model if available
            if (context.hasModelAndSchema()) {
                String error = ValidationUtils.validateNodeReference(refWithoutBrackets, context.getModel(), context.getSchema());
                if (error != null) {
                    errors.add(error);
                }
            }
        }

        private void validateSimReference(String simRef, List<String> errors) {
            // Check if the sim reference is one of the known variables
            if (!KNOWN_SIM_VARIABLES.contains(simRef)) {
                errors.add("Unknown sim variable: '" + simRef + "'. Valid options are: sim.year, sim.month, sim.day, sim.day_of_year, sim.step");
            }
        }

        private void validateThisRef(String thisRef, List<String> errors) {
            // Strip optional square brackets for validation
            String refWithoutBrackets = thisRef.replaceFirst("\\[.*?\\]$", "");

            // Check for malformed this references
            if (refWithoutBrackets.equals("this") || refWithoutBrackets.equals("this.")) {
                errors.add("Incomplete this reference: '" + thisRef + "'");
                return;
            }

            // Extract the output property (everything after "this.")
            String outputProperty = refWithoutBrackets.substring(5); // "this.".length() == 5

            // Check if we have current node context
            if (!context.hasCurrentNode()) {
                errors.add("Cannot use 'this' reference outside of node context: '" + thisRef + "'");
                return;
            }

            // Get allowed outputs for current node type
            Set<String> allowedOutputs = context.getCurrentNodeAllowedOutputs();

            // If no schema or node type definition, we can't validate further
            if (allowedOutputs.isEmpty()) {
                return; // No validation possible - allow it
            }

            // Check if the output property is allowed
            if (!allowedOutputs.contains(outputProperty)) {
                String nodeType = context.getCurrentNodeType();
                errors.add("Output property '" + outputProperty + "' is not allowed for node type '" + nodeType +
                          "'. Allowed outputs: " + allowedOutputs);
            }
        }

        private String suggestFunction(String funcName) {
            // Common typos and suggestions
            Map<String, String> suggestions = Map.of(
                "maximum", "max",
                "minimum", "min",
                "average", "mean",
                "square_root", "sqrt",
                "logarithm", "log",
                "power", "pow"
            );

            if (suggestions.containsKey(funcName)) {
                return " (did you mean '" + suggestions.get(funcName) + "'?)";
            }

            return "";
        }
    }

    // ==================== Exception ====================

    static class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }
}
