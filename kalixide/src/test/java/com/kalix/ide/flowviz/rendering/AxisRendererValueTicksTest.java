package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link AxisRenderer#calculateValueTicks}: a LOG axis is labelled with numbers a
 * modeller reads as round (decades, 1-2-5, 1..9, or even data-space steps when zoomed
 * under a factor of two), never with 10^0.5 = 31.62; LINEAR keeps its even nice steps.
 */
class AxisRendererValueTicksTest {

    private static final long DAY = 86_400_000L;
    private static final int TALL = 400;   // targets 10 ticks
    private static final double EPS = 1e-9;

    private final AxisRenderer renderer = new AxisRenderer();

    private static ViewPort viewport(double min, double max, int plotHeight, YAxisScale scale) {
        return new ViewPort(0, DAY, min, max, 0, 0, 600, plotHeight, scale, XAxisType.TIME);
    }

    private List<Double> logTicks(double min, double max, int plotHeight) {
        return renderer.calculateValueTicks(viewport(min, max, plotHeight, YAxisScale.LOG));
    }

    private static void assertTicks(List<Double> expected, List<Double> actual) {
        assertTicks(expected, actual, "tick count");
    }

    private static void assertTicks(List<Double> expected, List<Double> actual, String why) {
        assertEquals(expected.size(), actual.size(), why + " for " + actual);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), Math.abs(expected.get(i)) * EPS, "tick " + i + " of " + actual);
        }
    }

    /** Whether {@code tick} is a whole mantissa (1..9) times a power of ten. */
    private static void assertRoundMantissa(double tick) {
        double mantissa = tick / Math.pow(10, Math.floor(Math.log10(tick)));
        assertEquals(Math.round(mantissa), mantissa, EPS, "tick " + tick + " is not a round number");
    }

    /** The reviewer's case: transformed [-0.135, 2.834] used to label 31.62 and 316.23. */
    @Test
    void logTicksAreNeverFractionalPowersOfTen() {
        List<Double> ticks = logTicks(Math.pow(10, -0.135), Math.pow(10, 2.834), TALL);
        assertTicks(List.of(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0), ticks);
    }

    @Test
    void logTicksWithinOneDecadeUseEveryInteger() {
        assertTicks(List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), logTicks(10, 100, TALL));
    }

    @Test
    void logTicksOverAFewDecadesUseOneTwoFive() {
        assertTicks(List.of(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0), logTicks(1, 1000, TALL));
    }

    @Test
    void logTicksOverManyDecadesStayOnDecades() {
        assertTicks(List.of(1.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6), logTicks(1, 1e6, TALL));
        assertTicks(List.of(1.0, 100.0, 1e4, 1e6, 1e8, 1e10, 1e12), logTicks(1, 1e12, TALL));
    }

    /** Under a factor of two even 1..9 gives too few ticks; log is near enough linear there. */
    @Test
    void logTicksUnderAFactorOfTwoFallBackToEvenDataSteps() {
        assertTicks(List.of(10.0, 12.0, 14.0, 16.0, 18.0, 20.0), logTicks(10, 20, TALL));
        assertTicks(List.of(10.0, 12.0, 14.0, 16.0, 18.0), logTicks(9.5, 19.5, TALL));
    }

    /** A tiny budget over a wide span keeps the two round ticks rather than an even step at 0. */
    @Test
    void logTicksOnATinyBudgetKeepTheRoundSetWhenEvenStepsDoNoBetter() {
        assertTicks(List.of(0.02, 0.05), logTicks(0.0123, 0.0616, 120));
    }

    /** A shorter plot has a smaller budget, so the same view coarsens rather than crowds. */
    @Test
    void logTicksCoarsenWithTheTickBudget() {
        assertTicks(List.of(10.0, 20.0, 50.0, 100.0), logTicks(10, 100, 200));
    }

    /** Whole decades would leave one label on a short plot over 1.78..56; 1-2-5 is taken over budget. */
    @Test
    void logTicksGoOneDenserRatherThanLeaveASingleLabel() {
        assertTicks(List.of(2.0, 5.0, 10.0, 20.0, 50.0), logTicks(1.78, 56, 120));
        assertTicks(List.of(1.0, 10.0), logTicks(0.4, 79, 120), "two decade labels fit and suffice");
        assertTicks(List.of(100.0, 1e4, 1e6), logTicks(3.7, 3.7e6, 120), "coarse steps still count from 1");
    }

    @Test
    void logTicksAreRoundAtEveryZoomAndHeight() {
        double[] starts = {0.0123, 0.5, 1, 3.7, 47, 1e5};
        double[] decades = {0.7, 1, 1.5, 2, 2.5, 3, 4, 6, 9};
        int[] heights = {120, 250, 400, 900};
        for (double start : starts) {
            for (double span : decades) {
                for (int height : heights) {
                    List<Double> ticks = logTicks(start, start * Math.pow(10, span), height);
                    int budget = Math.max(3, Math.min(10, height / 40));
                    assertTrue(ticks.size() >= 2, "too few ticks: " + ticks);
                    // One placement over budget is allowed only when the fitting one left a single
                    // label; with no rung more than 2.5x the last that is six ticks at most
                    assertTrue(ticks.size() <= Math.max(budget + 2, 6), "too many ticks for " + height + "px: " + ticks);
                    ticks.forEach(AxisRendererValueTicksTest::assertRoundMantissa);
                }
            }
        }
    }

    /** A LOG viewport whose minimum is not positive falls back to a six-decade floor; still round. */
    @Test
    void logTicksSurviveAnInvalidMinimum() {
        List<Double> ticks = logTicks(0, 500, TALL);
        assertTrue(ticks.size() >= 3, ticks.toString());
        ticks.forEach(AxisRendererValueTicksTest::assertRoundMantissa);
    }

    /** An accumulating loop never advanced once the interval fell under half an ulp: the EDT hung. */
    @Test
    void ticksOnASubUlpSpanTerminate() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<Double> ticks = renderer.calculateValueTicks(viewport(1.0, Math.nextUp(1.0), TALL, YAxisScale.LINEAR));
            assertTrue(ticks.size() <= 4 * 10 + 1, "bounded by the tick budget: " + ticks.size());
            assertTrue(logTicks(1.0, Math.nextUp(1.0), TALL).size() <= 4 * 10 + 1);
            assertTrue(renderer.calculateValueTicks(viewport(Double.MIN_VALUE, 3 * Double.MIN_VALUE, TALL, YAxisScale.LINEAR)).isEmpty(),
                "a span below the doubles' floor yields no interval and no ticks");
        });
    }

    /** Padding can overflow a bound to infinity; the axis goes empty rather than looping to Integer.MAX_VALUE. */
    @Test
    void ticksOnANonFiniteBoundAreEmpty() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (YAxisScale scale : YAxisScale.values()) {
                assertTrue(renderer.calculateValueTicks(viewport(1, Double.POSITIVE_INFINITY, TALL, scale)).isEmpty(), scale.toString());
            }
            assertTrue(renderer.calculateValueTicks(viewport(Double.NEGATIVE_INFINITY, 1, TALL, YAxisScale.LINEAR)).isEmpty());
            // LOG cannot show a negative minimum at all, so -Inf takes the usual six-decade fallback
            assertFalse(renderer.calculateValueTicks(viewport(Double.NEGATIVE_INFINITY, 1, TALL, YAxisScale.LOG)).isEmpty());
        });
    }

    /** The numeric x-axis (double-mass) shares the counted placement, so a sub-ulp span cannot spin it either. */
    @Test
    void numericAxisTicksAreNiceAndTerminateOnASubUlpSpan() {
        long scale = 1_000_000L;   // PlotTypeTransformer.NUMERIC_SCALE
        ViewPort tenUnits = new ViewPort(0, 10 * scale, 0, 1, 0, 0, 600, TALL, YAxisScale.LINEAR, XAxisType.NUMERIC);
        assertEquals(List.of(0L, 2 * scale, 4 * scale, 6 * scale, 8 * scale, 10 * scale), renderer.calculateAxisInfo(tenUnits).timeTicks);

        long huge = 5_000_000_000L * scale;   // cumulative volume 5e9, one encoded unit wide
        ViewPort sliver = new ViewPort(huge, huge + 1, 0, 1, 0, 0, 600, TALL, YAxisScale.LINEAR, XAxisType.NUMERIC);
        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertTrue(renderer.calculateAxisInfo(sliver).timeTicks.size() <= 4 * 7 + 1));
    }

    private List<Double> symlogTicks(double min, double max, int plotHeight) {
        return renderer.calculateValueTicks(viewport(min, max, plotHeight, YAxisScale.SYMLOG));
    }

    /** The seam at +/-10 is ticked even when the coarse step is two decades. */
    @Test
    void symlogTicksAreRoundOnBothSidesAndAlwaysTickTheSeam() {
        assertTicks(List.of(-1e5, -1e3, -10.0, 0.0, 10.0, 1e3, 1e5), symlogTicks(-1e6, 1e6, TALL));
    }

    /** Auto-Y on data 0..500: the linear zone gets even steps, the log zone round mantissas. */
    @Test
    void symlogTicksSplitTheBudgetBetweenLinearAndLogRegions() {
        List<Double> ticks = symlogTicks(YAxisScale.SYMLOG.inverseTransform(-0.135), YAxisScale.SYMLOG.inverseTransform(2.834), TALL);
        assertTicks(List.of(0.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0), ticks);
    }

    @Test
    void symlogTicksInsideTheLinearRegionMatchLinear() {
        assertTicks(List.of(-4.0, -2.0, 0.0, 2.0, 4.0), symlogTicks(-5, 5, TALL));
    }

    /** A bound just past the seam gave the sliver a two-tick budget: 9.5 and 10 drawn on top of each other. */
    @Test
    void symlogTicksSkipARegionTooThinForALabel() {
        List<Double> ticks = symlogTicks(9.5, 1e6, TALL);
        assertEquals(10.0, ticks.get(0), 0.0, "the seam opens the axis: " + ticks);
        List<Double> mirrored = symlogTicks(-1e6, 10.5, TALL);
        assertEquals(10.0, mirrored.get(mirrored.size() - 1), 0.0, "the seam closes the axis: " + mirrored);
    }

    /** The seam tick is snapped to exactly +/-L whichever region produced it, so the grid can spot it by value. */
    @Test
    void symlogSeamTicksAreExact() {
        List<Double> ticks = symlogTicks(YAxisScale.SYMLOG.inverseTransform(-0.135), YAxisScale.SYMLOG.inverseTransform(2.834), TALL);
        assertTrue(ticks.contains(10.0), "exactly 10.0, not 9.999999999999998: " + ticks);
        assertTrue(symlogTicks(-1e6, 1e6, TALL).contains(-10.0));
    }

    /** The seam snap absorbs an ulp of step drift, not real ticks a hair from 10. */
    @Test
    void symlogSeamSnapLeavesTicksNearTheSeamAlone() {
        List<Double> ticks = symlogTicks(9.99999999, 10.00000001, TALL);
        assertEquals(1, ticks.stream().filter(t -> t == 10.0).count(), "one exact seam tick: " + ticks);
        assertTrue(ticks.size() >= 5, "the neighbouring ticks survive: " + ticks);
    }

    /** Dedup once ate neighbouring ticks within an absolute 1e-9; a view of tiny values must tick like Linear. */
    @Test
    void symlogTicksOnTinyValuesMatchLinear() {
        List<Double> linear = renderer.calculateValueTicks(viewport(0, 5e-9, TALL, YAxisScale.LINEAR));
        assertEquals(6, linear.size(), linear.toString());
        assertTicks(linear, symlogTicks(0, 5e-9, TALL));
    }

    /** pow(10, log10(97)) is not 97; an exact edge compare dropped the edge tick. */
    @Test
    void logTicksKeepATickSittingOnTheViewportEdge() {
        assertTicks(List.of(97.0, 98.0, 99.0, 100.0, 101.0, 102.0, 103.0), logTicks(97, 103, TALL));
        assertTicks(List.of(3.2, 3.25, 3.3, 3.35, 3.4), logTicks(3.2, 3.4, TALL));
    }

    /** Gating every region at 40 px left a short Symlog plot with markers but no labels at all. */
    @Test
    void symlogTicksOnAShortPlotStillLabelTheAxis() {
        assertTicks(List.of(5.0, 10.0, 20.0), symlogTicks(5, 20, 60));
        assertTicks(List.of(-1000.0, -100.0, -10.0, 0.0, 10.0, 100.0, 1000.0), symlogTicks(-1000, 1000, 100));
    }

    /** With the linear zone a pixel wide the seam pair would print 10 and -10 on top of each other. */
    @Test
    void symlogSeamPairCollapsesToZeroWhenTheLinearZoneIsSkipped() {
        assertTicks(List.of(-1e101, 0.0, 1e101), symlogTicks(-1e200, 1e200, 120));
    }

    /** Coarse steps go 2, 5, 10, 20...; a 2-5-20 ladder made "one denser" land four times over budget. */
    @Test
    void logCoarseStepsClimbByAtMostTwoAndAHalf() {
        assertTicks(List.of(1.0, 1e10, 1e20, 1e30), logTicks(1, 1e30, 200));
    }

    /** Even data steps are for narrow views only; over 15 decades they all crowd into the top one. */
    @Test
    void logEvenStepsAreNotTakenOverAWideView() {
        assertTicks(List.of(1.0, 1e5), logTicks(1.26e-5, 7.9e9, 200));
    }

    @Test
    void symlogTicksOnOneSideOnlyStayOnThatSide() {
        assertTicks(List.of(-1e6, -1e5, -1e4, -1e3, -100.0), symlogTicks(-1e6, -100, TALL));
    }

    @Test
    void linearTicksKeepTheirEvenNiceSteps() {
        List<Double> ticks = renderer.calculateValueTicks(viewport(0, 100, TALL, YAxisScale.LINEAR));
        assertTicks(List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), ticks);
    }
}
