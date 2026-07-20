package com.kalix.ide.linter.validators;

import com.kalix.ide.language.ExpressionLanguage;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.FnRegistry;
import com.kalix.ide.linter.model.ValidationContext;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.*;

/**
 * Validates function expressions used in model parameters.
 * Supports these types of inputs:
 * - Data references: "data.evap", "data.field.subfield"
 * - Constant references: "const.pi", "const.node_1_demand_levels.high"
 * - Node output references: "node.node13_inflow.ds_1"
 * - This references: "this.dsflow", "this.volume" (shorthand for current node outputs)
 * - Sim references: "sim.year", "sim.month", "sim.day", "sim.day_of_year", "sim.step",
 *   and the calendar-boundary flags "sim.new_day", "sim.new_month", "sim.new_year"
 * - Table lookups: "table.rating(x)" (1D), "table.pump(col_key, row_key)" (2D)
 * - User-defined function calls: "fn.net_demand(x, y)" (validated against the [fn] section)
 * - Var references: "var.accounting.headroom" (a series written by a [var.*] block)
 * - Constant expressions: "5.0", "2 + 3"
 * - Complex functions: "if(data.temp > 20, 10.0, 5.0) * 1.2"
 * - Program blocks: "{ x = data.a * 2; assert(x >= 0); x + 1 }" - ';'-terminated
 *   statements (local assignments and asserts) followed by a bare result expression
 *
 * The known-function set and sim-variable set derive from the single Java
 * language definition ({@link ExpressionLanguage}), which mirrors the engine's
 * BuiltinFunction enum (src/functions/functions.rs).
 *
 * Performance target: < 10ms per expression validation
 */
public class FunctionExpressionValidator {

    // Known functions with their argument counts (negative = variadic with
    // minimum argument count of -value). Derived from ExpressionLanguage.
    private static final Map<String, Integer> KNOWN_FUNCTIONS = ExpressionLanguage.functionArities();

    // Fast-path recognisers, compiled once: these run per expression per validation
    // pass, and String.matches would recompile each pattern every call.
    private static final java.util.regex.Pattern NUMBER_FAST_PATTERN =
        java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");
    private static final java.util.regex.Pattern DATA_REF_FAST_PATTERN =
        java.util.regex.Pattern.compile("^data\\.[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*(\\[.*?\\])?$");
    private static final java.util.regex.Pattern CONST_REF_FAST_PATTERN =
        java.util.regex.Pattern.compile("^const\\.[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    private static final java.util.regex.Pattern NODE_REF_FAST_PATTERN =
        java.util.regex.Pattern.compile("^node\\.[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_]*(\\[.*?\\])?$");
    private static final java.util.regex.Pattern THIS_REF_FAST_PATTERN =
        java.util.regex.Pattern.compile("^this\\.[a-zA-Z_][a-zA-Z0-9_]*(\\[.*?\\])?$");

    // Known simulation variables. Derived from ExpressionLanguage.
    private static final Set<String> KNOWN_SIM_VARIABLES = ExpressionLanguage.simVariableNames();

    /**
     * Validate a function expression without model context. Convenience for callers
     * (and tests) that only need syntax-level validation - node and 'this' references
     * cannot be checked without a context.
     */
    public List<String> validate(String expression) {
        return validate(expression, ValidationContext.empty());
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

        return performValidation(expression.trim(), context);
    }

    private List<String> performValidation(String expression, ValidationContext context) {
        List<String> errors = new ArrayList<>();

        // Fast path: empty expression
        if (expression.isEmpty()) {
            errors.add("Expression is empty");
            return errors;
        }

        // Program block: '{ statements; result }' - the block form of a value.
        // Only legal as the entire value, so the first character decides.
        if (expression.startsWith("{")) {
            try {
                Tokenizer tokenizer = new Tokenizer(expression);
                Parser parser = new Parser(tokenizer, context);
                parser.parseProgram(errors);
                if (errors.isEmpty() && parser.current.type != TokenType.EOF) {
                    errors.add("Unexpected tokens after closing '}': '" + parser.current.value + "'");
                }
            } catch (ParseException e) {
                errors.add(e.getMessage());
            } catch (Exception e) {
                errors.add("Failed to parse program: " + e.getMessage());
            }
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

    /**
     * Validate a [fn] definition body: an expression or a { } block whose
     * bare names resolve against the function's parameters (plus any locals
     * it assigns). 'this.' is late-bound to the calling node, so it cannot
     * be checked here and is allowed through.
     *
     * @param body   the body text as written (expression or block)
     * @param params the signature's parameter names, in order
     */
    public List<String> validateFnBody(String body, List<String> params, ValidationContext context) {
        List<String> errors = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            errors.add("Function body is empty");
            return errors;
        }
        if (context == null) {
            context = ValidationContext.empty();
        }
        String trimmed = body.trim();
        try {
            Tokenizer tokenizer = new Tokenizer(trimmed);
            Parser parser = new Parser(tokenizer, context);
            parser.allowLateThis = true;
            parser.programLocals = new HashSet<>();
            for (String p : params) {
                parser.programLocals.add(p.toLowerCase());
            }
            if (trimmed.startsWith("{")) {
                parser.parseProgram(errors);
            } else {
                parser.parseExpression(errors);
            }
            // Guard with errors.isEmpty() so a parse that stopped early (e.g. a
            // program statement with no effect) doesn't stack a trailing-token
            // error on top of the real diagnostic.
            if (errors.isEmpty() && parser.current.type != TokenType.EOF) {
                errors.add("Unexpected tokens after expression: '" + parser.current.value + "'");
            }
        } catch (ParseException e) {
            errors.add(e.getMessage());
        } catch (Exception e) {
            errors.add("Failed to parse function body: " + e.getMessage());
        }
        return errors;
    }

    /**
     * Collect the lowercased bare names of every {@code fn.*} call in a body, by
     * walking it with the expression Tokenizer and picking out {@code FN_REF}
     * tokens. Used by the {@code [fn]} recursion check.
     *
     * <p>Tokenizing rather than regex-scanning raw text makes the scan
     * case-correct ({@code fn.B} yields {@code "b"}) and immune to phantom
     * matches inside other references: {@code data.fn.a} lexes as a single
     * {@code DATA_REF} token, never an {@code FN_REF}, so it produces no edge.</p>
     */
    static List<String> collectFnCallees(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        try {
            Tokenizer tokenizer = new Tokenizer(body);
            for (Token tok = tokenizer.nextToken(); tok.type != TokenType.EOF; tok = tokenizer.nextToken()) {
                if (tok.type == TokenType.FN_REF) {
                    String bare = tok.value.substring(3).toLowerCase(); // strip "fn."
                    // Skip malformed dotted names (e.g. fn.a.b) and empty tails:
                    // they match no definition, so they cannot be a real edge.
                    if (!bare.isEmpty() && bare.indexOf('.') < 0) {
                        out.add(bare);
                    }
                }
            }
        } catch (ParseException e) {
            // A malformed body throws mid-scan; its own body validation reports
            // that error. Return the edges collected before the throw.
        }
        return out;
    }

    // Fast path checks using simple regex
    private static boolean isSimpleNumber(String s) {
        return NUMBER_FAST_PATTERN.matcher(s).matches();
    }

    private static boolean isSimpleDataReference(String s) {
        // Matches: data.xxx or data.xxx.yyy.zzz (dots and underscores allowed)
        // Optional square brackets at the end: data.xxx[anything]
        return DATA_REF_FAST_PATTERN.matcher(s).matches();
    }

    private static boolean isSimpleConstantReference(String s) {
        // Matches: const.xxx or const.xxx.yyy.zzz (dots and underscores allowed)
        return CONST_REF_FAST_PATTERN.matcher(s).matches();
    }

    private static boolean isSimpleNodeReference(String s) {
        // Matches: node.xxx.yyy (node.nodename.property)
        // Optional square brackets at the end: node.xxx.yyy[anything]
        return NODE_REF_FAST_PATTERN.matcher(s).matches();
    }

    private static boolean isSimpleSimReference(String s) {
        return KNOWN_SIM_VARIABLES.contains(s);
    }

    private static boolean isSimpleThisReference(String s) {
        // Matches: this.xxx (this.property)
        // Optional square brackets at the end: this.xxx[anything]
        return THIS_REF_FAST_PATTERN.matcher(s).matches();
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
        NUMBER, IDENT, DATA_REF, CONST_REF, NODE_REF, THIS_REF, SIM_REF, TABLE_REF, FN_REF, VAR_REF,
        ACC_REF, RAS_REF,
        OPERATOR, LPAREN, RPAREN, LBRACE, RBRACE, SEMICOLON, COMMA, EOF
    }

    /** Fields published per account (`acc.<account>.<field>`). */
    private static final java.util.Set<String> ACCOUNT_FIELDS =
        java.util.Set.of("opening_balance", "closing_balance", "debits", "size");

    /** Fields published per account group — the same, less the static ones. */
    private static final java.util.Set<String> ACCOUNT_GROUP_FIELDS =
        java.util.Set.of("opening_balance", "closing_balance", "debits");

    /** Fields published per resource allocation system (`ras.<name>.<field>`). */
    private static final java.util.Set<String> RAS_FIELDS = java.util.Set.of("fired");

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
            if (ch == '{') {
                pos++;
                return new Token(TokenType.LBRACE, "{", pos - 1);
            }
            if (ch == '}') {
                pos++;
                return new Token(TokenType.RBRACE, "}", pos - 1);
            }
            if (ch == ';') {
                pos++;
                return new Token(TokenType.SEMICOLON, ";", pos - 1);
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

        private Token readIdentOrDataRef() throws ParseException {
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

            // Check if this is a constant reference (starts with "const.")
            if (firstSegment.equals("const") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.CONST_REF);
            }

            // Check if this is a node reference (starts with "node.")
            if (firstSegment.equals("node") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.NODE_REF);
            }

            // Check if this is an account reference (starts with "acc.")
            if (firstSegment.equals("acc") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.ACC_REF);
            }

            // Check if this is a RAS reference (starts with "ras.")
            if (firstSegment.equals("ras") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.RAS_REF);
            }

            // Check if this is a table reference (starts with "table.")
            if (firstSegment.equals("table") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.TABLE_REF);
            }

            // Check if this is a user-defined function reference (starts with "fn.")
            if (firstSegment.equals("fn") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.FN_REF);
            }

            // Check if this is a var reference (starts with "var.") - a series
            // written by a [var.*] block; supports the offset bracket syntax.
            if (firstSegment.equals("var") && pos < input.length() && input.charAt(pos) == '.') {
                return readDottedReference(start, firstSegment, TokenType.VAR_REF);
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
        private Token readDottedReference(int start, String prefix, TokenType tokenType) throws ParseException {
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

            // Check for optional square brackets (data, node, var, account and RAS references)
            if ((tokenType == TokenType.DATA_REF || tokenType == TokenType.NODE_REF || tokenType == TokenType.VAR_REF
                    || tokenType == TokenType.ACC_REF || tokenType == TokenType.RAS_REF) &&
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
                } else {
                    // No closing bracket: the scan consumed the rest of the input, so
                    // "caught as error later" never happened - the next token was EOF
                    // and expressions like data.rain[unclosed validated clean.
                    throw new ParseException(
                        "Unterminated '[' in reference at position " + bracketStart);
                }
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
        private final List<Token> lookaheadBuffer = new ArrayList<>();

        /** Non-null while validating a program block or fn body: the bare
         *  local/parameter names (lowercased) assigned so far. Bare
         *  identifiers resolve against this set instead of erroring. */
        Set<String> programLocals;

        /** True when validating a [fn] body: 'this.' is late-bound to the
         *  calling node, so context checks are suppressed. */
        boolean allowLateThis;

        Parser(Tokenizer tokenizer, ValidationContext context) throws ParseException {
            this.tokenizer = tokenizer;
            this.context = context;
            this.current = tokenizer.nextToken();
        }

        private void advance() throws ParseException {
            if (!lookaheadBuffer.isEmpty()) {
                current = lookaheadBuffer.remove(0);
            } else {
                current = tokenizer.nextToken();
            }
        }

        /** One-token lookahead (statement-shape decisions: 'name =', 'assert('). */
        private Token peek() throws ParseException {
            return peekAt(0);
        }

        /** N-token lookahead; peekAt(0) is the token after `current`. Only
         *  the signed-literal check needs depth 1. */
        private Token peekAt(int i) throws ParseException {
            while (lookaheadBuffer.size() <= i) {
                lookaheadBuffer.add(tokenizer.nextToken());
            }
            return lookaheadBuffer.get(i);
        }

        private void expect(TokenType type, List<String> errors) throws ParseException {
            if (current.type != type) {
                throw new ParseException("Expected " + type + " but got " + current.type + " ('" + current.value + "')");
            }
            advance();
        }

        private static final String NO_RESULT_MSG =
            "Program has no result value: the final line must be a bare expression without ';' (its value is the block's value)";

        // Program := '{' Statement* ResultExpression '}'
        // Statement := IDENT '=' Expression ';' | 'assert' '(' Expression ')' ';'
        void parseProgram(List<String> errors) throws ParseException {
            if (current.type != TokenType.LBRACE) {
                errors.add("A program block must start with '{'");
                return;
            }
            advance();
            if (programLocals == null) {
                programLocals = new HashSet<>();
            }

            while (true) {
                if (current.type == TokenType.EOF) {
                    errors.add("Unclosed program block: missing '}'");
                    return;
                }
                if (current.type == TokenType.RBRACE) {
                    // '}' with no bare final expression (empty block, or the
                    // last statement was terminated/an assignment/an assert).
                    errors.add(NO_RESULT_MSG);
                    advance();
                    return;
                }

                // assert(cond); - statement form only
                if (current.type == TokenType.IDENT && current.value.equalsIgnoreCase("assert")
                        && peek().type == TokenType.LPAREN) {
                    advance(); // assert
                    advance(); // (
                    parseExpression(errors);
                    if (current.type != TokenType.RPAREN) {
                        errors.add("Expected ')' after assert condition");
                        return;
                    }
                    advance();
                    if (!consumeStatementTerminator(errors, "assert(...)")) {
                        return;
                    }
                    continue;
                }

                // name = expression; - local assignment (bare names only)
                if (current.type == TokenType.IDENT && peek().type == TokenType.OPERATOR
                        && peek().value.equals("=")) {
                    String name = current.value;
                    String lower = name.toLowerCase();
                    // The language owns the bare names: a local may not shadow ANY
                    // reserved tier — builtin, stateful builtin, or keyword. Name
                    // the tier exactly as the engine's local-assignment guard does
                    // (src/functions/parser.rs, reserved_name_kind).
                    String tier = ExpressionLanguage.reservedTier(lower);
                    if (tier != null) {
                        errors.add("Cannot use " + tier + " name '" + lower + "' as a local variable");
                    }
                    advance(); // name
                    advance(); // =
                    parseExpression(errors);
                    programLocals.add(lower);
                    if (!consumeStatementTerminator(errors, "assignment to '" + name + "'")) {
                        return;
                    }
                    continue;
                }

                // dotted = ... - assigning to a model reference
                if (isReferenceToken(current.type) && peek().type == TokenType.OPERATOR
                        && peek().value.equals("=")) {
                    errors.add("Cannot assign to '" + current.value
                            + "': dotted names are model references; local variables are bare names");
                    advance();
                    advance();
                    parseExpression(errors);
                    if (!consumeStatementTerminator(errors, "assignment")) {
                        return;
                    }
                    continue;
                }

                // Otherwise: the final (result) expression, which must be bare.
                parseExpression(errors);
                if (current.type == TokenType.RBRACE) {
                    advance();
                    return; // the block's value
                }
                if (current.type == TokenType.SEMICOLON) {
                    advance();
                    if (current.type == TokenType.RBRACE) {
                        errors.add(NO_RESULT_MSG);
                        advance();
                        return;
                    }
                    errors.add("Statement has no effect: expected 'name = expression;', 'assert(...);', or the final result expression (no ';')");
                    // Callers guard their trailing-token check with errors.isEmpty(),
                    // so leaving tokens unconsumed here cannot stack a second error.
                    return;
                }
                if (current.type == TokenType.EOF) {
                    // Block ended mid-result-expression: the common typo is a
                    // missing closing brace, so say that, not "unexpected ''".
                    errors.add("Unclosed program block: missing '}'");
                    return;
                }
                errors.add("Unexpected token in program: '" + current.value + "'");
                return;
            }
        }

        /** After a statement: ';' continues; '}' means the modeller terminated
         *  the final line (the no-result error); anything else is malformed. */
        private boolean consumeStatementTerminator(List<String> errors, String what) throws ParseException {
            if (current.type == TokenType.SEMICOLON) {
                advance();
                return true;
            }
            if (current.type == TokenType.RBRACE) {
                errors.add(NO_RESULT_MSG);
                advance();
                return false;
            }
            errors.add("Expected ';' after " + what);
            return false;
        }

        private static boolean isReferenceToken(TokenType t) {
            return t == TokenType.DATA_REF || t == TokenType.CONST_REF || t == TokenType.NODE_REF
                    || t == TokenType.THIS_REF || t == TokenType.SIM_REF || t == TokenType.VAR_REF
                    || t == TokenType.ACC_REF || t == TokenType.RAS_REF
                    || t == TokenType.TABLE_REF || t == TokenType.FN_REF;
        }

        // Expression := OrExpression
        void parseExpression(List<String> errors) throws ParseException {
            parseOrExpression(errors);
        }

        // OrExpression := AndExpression ( '||' AndExpression )*
        private void parseOrExpression(List<String> errors) throws ParseException {
            parseAndExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("||") || current.value.equals("|"))) {

                // Logical OR is '||'; single '|' is invalid syntax
                if (current.value.equals("|")) {
                    errors.add("Invalid operator '|' - use '||' for logical OR");
                }

                advance();
                parseAndExpression(errors);
            }
        }

        // AndExpression := ComparisonExpression ( '&&' ComparisonExpression )*
        private void parseAndExpression(List<String> errors) throws ParseException {
            parseComparisonExpression(errors);

            while (current.type == TokenType.OPERATOR &&
                   (current.value.equals("&&") || current.value.equals("&"))) {

                // Logical AND is '&&'; single '&' is invalid syntax
                if (current.value.equals("&")) {
                    errors.add("Invalid operator '&' - use '&&' for logical AND");
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

                // Division by a zero constant is intentionally not flagged: writing "x / 0" to
                // manufacture a NaN (e.g. to mark values for omission in later statistical analysis)
                // is a legitimate modelling idiom, not a mistake.
                advance();

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
            } else if (current.type == TokenType.TABLE_REF) {
                // Table lookup call: table.<name>(args)
                parseTableCall(errors);
            } else if (current.type == TokenType.FN_REF) {
                // User-defined function call: fn.<name>(args)
                parseFnCall(errors);
            } else if (current.type == TokenType.VAR_REF) {
                validateVarReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.ACC_REF) {
                validateAccReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.RAS_REF) {
                validateRasReference(current.value, errors);
                advance();
            } else if (current.type == TokenType.IDENT) {
                // Function call - or, inside a program/fn body, a bare local
                if (peek().type == TokenType.LPAREN) {
                    parseFunctionCall(errors);
                } else if (programLocals != null) {
                    String lower = current.value.toLowerCase();
                    if (!programLocals.contains(lower)) {
                        errors.add("Local variable '" + current.value
                                + "' is used before it is assigned (locals must be assigned above their first use; model references need a namespace prefix like data. or node.)");
                    }
                    advance();
                } else {
                    // Plain-expression context: a bare identifier can only be a
                    // (mis)spelled function call.
                    parseFunctionCall(errors);
                }
            } else if (current.type == TokenType.LPAREN) {
                advance();
                parseExpression(errors);
                expect(TokenType.RPAREN, errors);
            } else {
                throw new ParseException("Expected number, data reference, constant reference, node reference, this reference, sim reference, table lookup, function, or '(' but got " + current.type);
            }
        }

        // TableCall := TABLE_REF '(' ArgumentList ')'
        private void parseTableCall(List<String> errors) throws ParseException {
            String tableRef = current.value; // e.g. "table.rating"

            // Malformed reference checks BEFORE advancing - advance() can throw
            // on the character that made the reference malformed (e.g. the second
            // dot of "table..rating"), and the specific message must land first.
            // A null tableName suppresses the existence/arity checks below.
            String tableName = null;
            if (tableRef.contains("..")) {
                errors.add("Malformed table reference: '" + tableRef + "' (consecutive dots)");
            } else if (tableRef.endsWith(".")) {
                errors.add("Incomplete table reference: '" + tableRef + "'");
            } else {
                tableName = tableRef.substring(6); // "table.".length() == 6
                if (tableName.contains(".")) {
                    errors.add("Invalid table reference: '" + tableRef + "' (table names cannot contain dots)");
                    tableName = null;
                }
            }
            advance();

            // A table reference is only meaningful as a call - a bare reference
            // has no value (the engine rejects it at model load too).
            if (current.type != TokenType.LPAREN) {
                errors.add("Table reference '" + tableRef + "' must be called with arguments, e.g. "
                        + tableRef + "(x)");
                return;
            }
            advance(); // consume '('

            int argCount = 0;
            if (current.type != TokenType.RPAREN) {
                argCount = parseArgumentList(null, errors);
            }
            expect(TokenType.RPAREN, errors);

            // Validate existence and arity against the model when available
            if (tableName != null && context.getModel() != null) {
                INIModelParser.Section tableSection =
                        context.getModel().getSections().get("table." + tableName.toLowerCase());
                if (tableSection == null) {
                    errors.add("Unknown table: '" + tableRef + "' (no [table." + tableName.toLowerCase()
                            + "] section is defined)");
                } else {
                    int expectedArgs = tableArity(tableSection);
                    if (argCount != expectedArgs) {
                        errors.add(expectedArgs == 1
                                ? "1D lookup table '" + tableRef + "' expects 1 argument, but got " + argCount
                                : "2D lookup table '" + tableRef + "' expects 2 arguments (column key, row key), but got " + argCount);
                    }
                }
            }
        }

        /**
         * Argument count implied by a [table.*] section: n_cols > 2 means a 2D
         * table (column key + row key), otherwise 1D. Mirrors the engine's rule.
         */
        private int tableArity(INIModelParser.Section tableSection) {
            INIModelParser.Property nCols = tableSection.getProperties().get("n_cols");
            if (nCols != null) {
                try {
                    if (Integer.parseInt(nCols.getValue().trim()) > 2) {
                        return 2;
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid n_cols is reported by the table section validator;
                    // fall through to the 1D default here.
                }
            }
            return 1;
        }

        // FnCall := FN_REF '(' ArgumentList? ')'
        private void parseFnCall(List<String> errors) throws ParseException {
            String fnRef = current.value; // e.g. "fn.net_demand"

            String fnName = null;
            if (fnRef.endsWith(".")) {
                errors.add("Incomplete function reference: '" + fnRef + "'");
            } else {
                fnName = fnRef.substring(3); // "fn.".length() == 3
                if (fnName.contains(".")) {
                    errors.add("Invalid function reference: '" + fnRef + "' (function names cannot contain dots)");
                    fnName = null;
                }
            }
            advance();

            if (current.type != TokenType.LPAREN) {
                errors.add("Function reference '" + fnRef + "' must be called with parentheses, e.g. "
                        + fnRef + "(...)");
                return;
            }
            advance(); // consume '('

            int argCount = 0;
            if (current.type != TokenType.RPAREN) {
                argCount = parseArgumentList(null, errors);
            }
            expect(TokenType.RPAREN, errors);

            // Validate existence and arity against the shared [fn] registry.
            if (fnName != null && context.getModel() != null) {
                Integer expectedArgs = context.getFnRegistry().arity(fnName.toLowerCase());
                if (expectedArgs == null) {
                    errors.add("Unknown function '" + fnRef + "' (no matching definition in the [fn] section)");
                } else if (argCount != expectedArgs) {
                    errors.add("Function '" + fnRef + "' expects " + expectedArgs
                            + " argument" + (expectedArgs == 1 ? "" : "s") + ", but got " + argCount);
                }
            }
        }

        // An account reference is a plain series reference:
        // acc.<account or group>.<field>, optionally with the offset bracket
        // syntax. The name may be an account or an account group (the acc.
        // namespace is flat), so the field set accepted is the union; the
        // engine resolves which it is at load. Forward offsets are rejected
        // (account state is computed during the run).
        private void validateAccReference(String accRef, List<String> errors) {
            String refWithoutBrackets = accRef.replaceFirst("\\[.*?\\]$", "");

            if (refWithoutBrackets.endsWith(".")) {
                errors.add("Malformed account reference: '" + accRef + "' (trailing dot)");
                return;
            }
            String[] segments = refWithoutBrackets.split("\\.");
            if (segments.length != 3) {
                errors.add("Invalid account reference: '" + accRef
                        + "' (expected acc.<account or group>.<field>, e.g. acc.smith.opening_balance)");
                return;
            }
            // A name that matches an [acc.<name>] section header is a group, so
            // the aggregate field set applies; anything else is an account name.
            boolean isGroup = context.getModel() != null
                    && context.getModel().getSections().containsKey("acc." + segments[1].toLowerCase());
            java.util.Set<String> allowed = isGroup ? ACCOUNT_GROUP_FIELDS : ACCOUNT_FIELDS;

            if (!allowed.contains(segments[2].toLowerCase())) {
                java.util.List<String> known = new java.util.ArrayList<>(allowed);
                java.util.Collections.sort(known);
                errors.add("Unknown field for " + (isGroup ? "account group" : "account") + ": '"
                        + accRef + "' (expected one of: " + String.join(", ", known) + ")");
                return;
            }
            rejectForwardOffset(accRef, errors);
        }

        // A RAS reference is a plain series reference: ras.<name>.<field>.
        private void validateRasReference(String rasRef, List<String> errors) {
            String refWithoutBrackets = rasRef.replaceFirst("\\[.*?\\]$", "");

            if (refWithoutBrackets.endsWith(".")) {
                errors.add("Malformed RAS reference: '" + rasRef + "' (trailing dot)");
                return;
            }
            String[] segments = refWithoutBrackets.split("\\.");
            if (segments.length != 3) {
                errors.add("Invalid RAS reference: '" + rasRef
                        + "' (expected ras.<name>.<field>, e.g. ras.gs_rollover.fired)");
                return;
            }
            if (!RAS_FIELDS.contains(segments[2].toLowerCase())) {
                java.util.List<String> known = new java.util.ArrayList<>(RAS_FIELDS);
                java.util.Collections.sort(known);
                errors.add("Unknown RAS field: '" + rasRef + "' (expected one of: "
                        + String.join(", ", known) + ")");
                return;
            }
            rejectForwardOffset(rasRef, errors);
        }

        // Computed series have no future values, so a positive offset is an error.
        private void rejectForwardOffset(String ref, List<String> errors) {
            int bracket = ref.indexOf('[');
            if (bracket < 0) {
                return;
            }
            String inside = ref.substring(bracket + 1, ref.length() - 1).trim();
            int comma = inside.indexOf(',');
            String offsetPart = (comma >= 0 ? inside.substring(0, comma) : inside).trim();
            try {
                if (Integer.parseInt(offsetPart) > 0) {
                    errors.add("Forward lookup not supported for computed series: '" + ref + "'");
                }
            } catch (NumberFormatException ignored) {
                // Non-integer offsets are caught by the engine's offset grammar.
            }
        }

        // A var reference is a plain series reference: var.<block>.<key>,
        // optionally with the offset bracket syntax. Forward offsets are
        // rejected (var values are computed during the run).
        private void validateVarReference(String varRef, List<String> errors) {
            String refWithoutBrackets = varRef.replaceFirst("\\[.*?\\]$", "");

            if (refWithoutBrackets.endsWith(".")) {
                errors.add("Malformed var reference: '" + varRef + "' (trailing dot)");
                return;
            }
            String[] segments = refWithoutBrackets.split("\\.");
            if (segments.length != 3) {
                errors.add("Invalid var reference: '" + varRef
                        + "' (expected var.<block>.<name>, e.g. var.accounting.headroom)");
                return;
            }

            // Forward offsets: computed series have no future values.
            int bracket = varRef.indexOf('[');
            if (bracket >= 0) {
                String inside = varRef.substring(bracket + 1, varRef.length() - 1).trim();
                int comma = inside.indexOf(',');
                String offsetPart = (comma >= 0 ? inside.substring(0, comma) : inside).trim();
                try {
                    if (Integer.parseInt(offsetPart) > 0) {
                        errors.add("Forward lookup not supported for computed series: '" + varRef + "'");
                    }
                } catch (NumberFormatException ignored) {
                    // Non-integer offsets are caught by the engine's offset grammar.
                }
            }

            // Validate against the model when available: the block section and key.
            // Shared with the [outputs] path (ValidationUtils.checkVarReference):
            // case-insensitive on block and key, and the 'phase' key is excluded.
            if (context.getModel() != null) {
                String sectionName = "var." + segments[1].toLowerCase();
                switch (ValidationUtils.checkVarReference(segments[1], segments[2], context.getModel())) {
                    case UNKNOWN_BLOCK -> errors.add("Unknown var block: '" + varRef
                            + "' (no [" + sectionName + "] section is defined)");
                    case UNKNOWN_KEY -> errors.add("Unknown var: '" + varRef
                            + "' (no '" + segments[2] + "' in [" + sectionName + "])");
                    case OK -> { /* resolved */ }
                }
            }
        }

        // FunctionCall := IDENT '(' ArgumentList? ')'
        private void parseFunctionCall(List<String> errors) throws ParseException {
            String funcName = current.value.toLowerCase();

            // assert is a statement, not a function - it only appears at
            // statement position inside a { } block.
            if (funcName.equals("assert")) {
                errors.add("'assert' is a statement, not a function: write it as its own line inside a { } block, e.g. assert(x > 0);");
            } else if (!KNOWN_FUNCTIONS.containsKey(funcName)) {
                errors.add("Unknown function: '" + funcName + "'" + suggestFunction(funcName));
            }

            advance();
            expect(TokenType.LPAREN, errors);

            List<Double> argLiterals = new ArrayList<>();
            int argCount = 0;
            if (current.type != TokenType.RPAREN) {
                argCount = parseArgumentList(argLiterals, errors);
            }

            expect(TokenType.RPAREN, errors);

            // Validate argument count (negative = variadic with minimum of -value)
            if (KNOWN_FUNCTIONS.containsKey(funcName)) {
                int expectedCount = KNOWN_FUNCTIONS.get(funcName);
                if (expectedCount >= 0 && argCount != expectedCount) {
                    errors.add("Function '" + funcName + "' expects " + expectedCount +
                               " argument" + (expectedCount == 1 ? "" : "s") + ", but got " + argCount);
                } else if (expectedCount < 0 && argCount < -expectedCount) {
                    int minimum = -expectedCount;
                    errors.add("Function '" + funcName + "' expects at least " + minimum +
                               " argument" + (minimum == 1 ? "" : "s") + ", but got " + argCount);
                }
            }

            // moving_*(x, n, default): the window (arg 2) and default (arg 3) must
            // be load-time literals — the engine sizes and pre-fills the window
            // state at model load, and does no constant folding (constant_arg
            // matches only a lone Constant node). The window must be a positive
            // integer. *_since functions carry no such constraint.
            if (ExpressionLanguage.isMovingWindowFunction(funcName) && argCount == 3) {
                Double window = argLiterals.get(1);
                if (window == null) {
                    errors.add(funcName + "'s window (2nd argument) must be a constant"
                            + " — state is sized at model load");
                } else if (window != Math.floor(window) || window < 1) {
                    errors.add(funcName + "'s window (2nd argument) must be a positive integer, but got "
                            + trimNumber(window));
                }
                if (argLiterals.get(2) == null) {
                    errors.add(funcName + "'s default (3rd argument) must be a constant"
                            + " — state is sized at model load");
                }
            }
        }

        /** Render a literal argument value without a needless trailing ".0". */
        private static String trimNumber(double v) {
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                return Long.toString((long) v);
            }
            return Double.toString(v);
        }

        // ArgumentList := Expression ( ',' Expression )*
        //
        // When outLiterals is non-null it is filled with one entry per argument:
        // the numeric value if that argument was a bare numeric literal, else
        // null. A fresh list is captured per call site, so nested calls don't
        // clobber it. "Bare literal" is judged BEFORE descending into the
        // argument (an optionally signed NUMBER token immediately followed by
        // ',' or ')'), mirroring the engine's constant_arg: the engine folds
        // unary +/- over a numeric literal at parse, so signed literals are
        // Constants; anything else lowers to a non-Constant it rejects.
        private int parseArgumentList(List<Double> outLiterals, List<String> errors) throws ParseException {
            int count = 1;
            if (outLiterals != null) {
                outLiterals.add(bareLiteralValue());
            }
            parseExpression(errors);

            while (current.type == TokenType.COMMA) {
                advance();
                if (outLiterals != null) {
                    outLiterals.add(bareLiteralValue());
                }
                parseExpression(errors);
                count++;
            }

            return count;
        }

        /**
         * If the current token starts a (possibly signed) numeric literal
         * argument — an optional leading '+'/'-' then a single NUMBER token
         * immediately at the argument boundary (',' or ')') — return its
         * value; otherwise null. The engine folds unary +/- over a numeric
         * literal at parse (parser.rs, July 2026), so a signed literal IS a
         * load-time Constant and a moving_* default of -1 is legal.
         */
        private Double bareLiteralValue() throws ParseException {
            double sign = 1.0;
            int offset = 0;
            if (current.type == TokenType.OPERATOR
                    && (current.value.equals("-") || current.value.equals("+"))) {
                // Peek only reaches one token ahead, so a signed literal is
                // judged when the sign token is current and the NUMBER next.
                if (peek().type != TokenType.NUMBER) {
                    return null;
                }
                sign = current.value.equals("-") ? -1.0 : 1.0;
                offset = 1;
            }
            Token numberToken = offset == 0 ? current : peek();
            if (numberToken.type != TokenType.NUMBER) {
                return null;
            }
            // The literal must be the WHOLE argument: the token after the
            // number is the argument boundary (',' or ')'). For a signed
            // literal that's two tokens ahead of `current`.
            TokenType next = peekAt(offset).type;
            if (next == TokenType.COMMA || next == TokenType.RPAREN) {
                try {
                    return sign * Double.parseDouble(numberToken.value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
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
            if (constRef.equals("const") || constRef.equals("const.")) {
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
                errors.add("Unknown sim variable: '" + simRef + "'. Valid options are: sim.year, sim.month, sim.day, sim.day_of_year, sim.step, sim.new_day, sim.new_month, sim.new_year");
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

            // Inside a [fn] body, 'this.' is late-bound to the calling node:
            // nothing can be checked until a call site exists.
            if (allowLateThis) {
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
            // Common typos and suggestions. 'avg' and 'log' are deliberately
            // not functions - the engine's spellings are 'mean' (the specific
            // statistic, not the family) and the explicit 'ln'/'log10'.
            Map<String, String> suggestions = Map.ofEntries(
                Map.entry("maximum", "max"),
                Map.entry("minimum", "min"),
                Map.entry("average", "mean"),
                Map.entry("avg", "mean"),
                Map.entry("square_root", "sqrt"),
                Map.entry("logarithm", "ln"),
                Map.entry("log", "ln"),
                Map.entry("power", "pow"),
                // Temporal functions: 'moving' (fixed window), never 'running'
                // (cumulative-since-start, which is sum_since's job); windows
                // are step-based, not day-based.
                Map.entry("running_mean", "moving_mean"),
                Map.entry("running_sum", "moving_sum"),
                Map.entry("rolling_mean", "moving_mean"),
                Map.entry("rolling_sum", "moving_sum"),
                Map.entry("days_since", "steps_since")
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
