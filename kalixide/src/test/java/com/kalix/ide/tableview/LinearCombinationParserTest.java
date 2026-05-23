package com.kalix.ide.tableview;

import com.kalix.ide.tableview.LinearCombinationParser.Term;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearCombinationParserTest {

    private static final double EPSILON = 1e-12;

    private static void assertTerm(Term t, double coef, String dataRef) {
        assertEquals(coef, t.coefficient, EPSILON);
        assertEquals(dataRef, t.dataRef);
    }

    // --- parse: valid cases -------------------------------------------------

    @Test
    void singleTerm_withCoefficient() {
        List<Term> terms = LinearCombinationParser.parse("0.5 * data.foo");
        assertNotNull(terms);
        assertEquals(1, terms.size());
        assertTerm(terms.get(0), 0.5, "data.foo");
    }

    @Test
    void singleTerm_withoutCoefficient_impliesOne() {
        List<Term> terms = LinearCombinationParser.parse("data.foo");
        assertNotNull(terms);
        assertEquals(1, terms.size());
        assertTerm(terms.get(0), 1.0, "data.foo");
    }

    @Test
    void threeTerms_withPlusSeparators() {
        List<Term> terms = LinearCombinationParser.parse(
                "0.1371563839 * data.31032_csv.by_name.daily_rain"
                + " + 0.5095107995 * data.31075_csv.by_name.daily_rain"
                + " + 0.5975703828 * data.31125_csv.by_name.daily_rain");
        assertNotNull(terms);
        assertEquals(3, terms.size());
        assertTerm(terms.get(0), 0.1371563839, "data.31032_csv.by_name.daily_rain");
        assertTerm(terms.get(1), 0.5095107995, "data.31075_csv.by_name.daily_rain");
        assertTerm(terms.get(2), 0.5975703828, "data.31125_csv.by_name.daily_rain");
    }

    @Test
    void mixedSigns_negativeViaMinusSeparator() {
        List<Term> terms = LinearCombinationParser.parse("0.5 * data.foo - 0.3 * data.bar");
        assertNotNull(terms);
        assertEquals(2, terms.size());
        assertTerm(terms.get(0),  0.5, "data.foo");
        assertTerm(terms.get(1), -0.3, "data.bar");
    }

    @Test
    void leadingNegative() {
        List<Term> terms = LinearCombinationParser.parse("-0.5 * data.foo + 0.3 * data.bar");
        assertNotNull(terms);
        assertEquals(2, terms.size());
        assertTerm(terms.get(0), -0.5, "data.foo");
        assertTerm(terms.get(1),  0.3, "data.bar");
    }

    @Test
    void leadingPlus_isAllowed() {
        List<Term> terms = LinearCombinationParser.parse("+0.5 * data.foo");
        assertNotNull(terms);
        assertEquals(1, terms.size());
        assertTerm(terms.get(0), 0.5, "data.foo");
    }

    @Test
    void implicitCoefficient_mixedWithExplicit() {
        List<Term> terms = LinearCombinationParser.parse("data.foo + 0.5 * data.bar");
        assertNotNull(terms);
        assertEquals(2, terms.size());
        assertTerm(terms.get(0), 1.0, "data.foo");
        assertTerm(terms.get(1), 0.5, "data.bar");
    }

    @Test
    void dataRefWithBracketSuffix_isAccepted() {
        List<Term> terms = LinearCombinationParser.parse(
                "0.137 * data.31032_csv.by_name.daily_rain[-1,0]"
                + " + 0.510 * data.31075_csv.by_name.daily_rain[-2,0]");
        assertNotNull(terms);
        assertEquals(2, terms.size());
        assertTerm(terms.get(0), 0.137, "data.31032_csv.by_name.daily_rain[-1,0]");
        assertTerm(terms.get(1), 0.510, "data.31075_csv.by_name.daily_rain[-2,0]");
    }

    @Test
    void scientificNotation_inCoefficient() {
        List<Term> terms = LinearCombinationParser.parse("1.5e-3 * data.foo");
        assertNotNull(terms);
        assertEquals(1, terms.size());
        assertTerm(terms.get(0), 0.0015, "data.foo");
    }

    @Test
    void whitespaceVariations() {
        List<Term> terms = LinearCombinationParser.parse("0.5*data.foo+0.3*data.bar");
        assertNotNull(terms);
        assertEquals(2, terms.size());
        assertTerm(terms.get(0), 0.5, "data.foo");
        assertTerm(terms.get(1), 0.3, "data.bar");
    }

    @Test
    void trailingInlineComment_isIgnored() {
        List<Term> terms = LinearCombinationParser.parse("0.5 * data.foo # weighting");
        assertNotNull(terms);
        assertEquals(1, terms.size());
        assertTerm(terms.get(0), 0.5, "data.foo");
    }

    @Test
    void multiLineLikeWhitespace_parsesAsJoined() {
        // The parser does not itself read continuations; callers pass a
        // pre-joined string. This test verifies whitespace between terms
        // (which is how a join leaves the input) is tolerated.
        List<Term> terms = LinearCombinationParser.parse("0.5 * data.foo   +   0.3 * data.bar");
        assertNotNull(terms);
        assertEquals(2, terms.size());
    }

    // --- parse: invalid cases ----------------------------------------------

    @Test
    void pureConstant_isRejected() {
        assertNull(LinearCombinationParser.parse("4"));
        assertNull(LinearCombinationParser.parse("0.5"));
    }

    @Test
    void constantPlusDataRef_isRejected() {
        // "4 + data.foo" - the 4 isn't a coefficient of anything.
        assertNull(LinearCombinationParser.parse("4 + data.foo"));
    }

    @Test
    void numberWithoutStar_isRejected() {
        assertNull(LinearCombinationParser.parse("0.5 data.foo"));
    }

    @Test
    void emptyOrBlank_isRejected() {
        assertNull(LinearCombinationParser.parse(""));
        assertNull(LinearCombinationParser.parse("   "));
        assertNull(LinearCombinationParser.parse(null));
    }

    @Test
    void trailingOperator_isRejected() {
        assertNull(LinearCombinationParser.parse("0.5 * data.foo +"));
    }

    @Test
    void unterminatedBracket_isRejected() {
        assertNull(LinearCombinationParser.parse("0.5 * data.foo[1,2"));
    }

    @Test
    void unknownCharacter_isRejected() {
        assertNull(LinearCombinationParser.parse("0.5 * data.foo / data.bar"));
        assertNull(LinearCombinationParser.parse("0.5 ^ 2 * data.foo"));
    }

    @Test
    void nonDataIdentifier_isRejected() {
        // Identifiers must begin with "data." - other identifiers aren't refs.
        assertNull(LinearCombinationParser.parse("0.5 * foo.bar"));
    }

    // --- canParse -----------------------------------------------------------

    @Test
    void canParse_matchesParse() {
        assertTrue(LinearCombinationParser.canParse("data.foo"));
        assertTrue(LinearCombinationParser.canParse("0.5 * data.foo - 0.3 * data.bar"));
        assertFalse(LinearCombinationParser.canParse("4 + data.foo"));
        assertFalse(LinearCombinationParser.canParse(""));
        assertFalse(LinearCombinationParser.canParse(null));
    }

    // --- formatInline -------------------------------------------------------

    @Test
    void formatInline_singleTerm() {
        List<Term> terms = List.of(new Term(0.5, "data.foo"));
        assertEquals("0.5 * data.foo", LinearCombinationParser.formatInline(terms));
    }

    @Test
    void formatInline_multipleTerms_withSigns() {
        List<Term> terms = List.of(
                new Term(0.5, "data.foo"),
                new Term(-0.3, "data.bar"),
                new Term(0.2, "data.baz")
        );
        assertEquals("0.5 * data.foo - 0.3 * data.bar + 0.2 * data.baz",
                LinearCombinationParser.formatInline(terms));
    }

    @Test
    void formatInline_leadingNegative() {
        List<Term> terms = List.of(
                new Term(-0.5, "data.foo"),
                new Term(0.3, "data.bar")
        );
        assertEquals("-0.5 * data.foo + 0.3 * data.bar",
                LinearCombinationParser.formatInline(terms));
    }

    @Test
    void formatInline_integerCoefficientDropsTrailingZero() {
        List<Term> terms = List.of(new Term(2.0, "data.foo"));
        assertEquals("2 * data.foo", LinearCombinationParser.formatInline(terms));
    }

    // --- formatMultiLine ----------------------------------------------------

    @Test
    void formatMultiLine_indentsContinuationLines() {
        List<Term> terms = List.of(
                new Term(0.5, "data.foo"),
                new Term(0.3, "data.bar"),
                new Term(-0.2, "data.baz")
        );
        // continuationIndent = 7 spaces ("rain = ".length())
        String result = LinearCombinationParser.formatMultiLine(terms, 7);
        String expected =
                "0.5 * data.foo +\n"
              + "       0.3 * data.bar -\n"
              + "       0.2 * data.baz";
        assertEquals(expected, result);
    }

    @Test
    void formatMultiLine_zeroIndent() {
        List<Term> terms = List.of(
                new Term(0.5, "data.foo"),
                new Term(0.3, "data.bar")
        );
        assertEquals("0.5 * data.foo +\n0.3 * data.bar",
                LinearCombinationParser.formatMultiLine(terms, 0));
    }

    // --- Round-trip ---------------------------------------------------------

    @Test
    void roundTrip_inline_preservesSemantics() {
        String input =
                "0.1371563839 * data.31032_csv.by_name.daily_rain"
                + " + 0.5095107995 * data.31075_csv.by_name.daily_rain"
                + " + 0.5975703828 * data.31125_csv.by_name.daily_rain";
        List<Term> first = LinearCombinationParser.parse(input);
        String formatted = LinearCombinationParser.formatInline(first);
        List<Term> roundTrip = LinearCombinationParser.parse(formatted);

        assertNotNull(roundTrip);
        assertEquals(first.size(), roundTrip.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).coefficient, roundTrip.get(i).coefficient, EPSILON);
            assertEquals(first.get(i).dataRef, roundTrip.get(i).dataRef);
        }
    }

    @Test
    void roundTrip_multiLine_parsesBack() {
        List<Term> original = List.of(
                new Term(0.5, "data.foo[-1,0]"),
                new Term(-0.3, "data.bar")
        );
        String multi = LinearCombinationParser.formatMultiLine(original, 7);
        List<Term> reparsed = LinearCombinationParser.parse(multi);
        assertNotNull(reparsed);
        assertEquals(2, reparsed.size());
        assertTerm(reparsed.get(0),  0.5, "data.foo[-1,0]");
        assertTerm(reparsed.get(1), -0.3, "data.bar");
    }
}
