package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(expected.size(), actual.size(), "tick count for " + actual);
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
                    assertTrue(ticks.size() <= budget + 2, "too many ticks for " + height + "px: " + ticks);
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

    @Test
    void linearTicksKeepTheirEvenNiceSteps() {
        List<Double> ticks = renderer.calculateValueTicks(viewport(0, 100, TALL, YAxisScale.LINEAR));
        assertTicks(List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), ticks);
    }
}
