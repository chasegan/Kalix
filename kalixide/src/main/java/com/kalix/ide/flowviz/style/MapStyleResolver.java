package com.kalix.ide.flowviz.style;

import com.kalix.ide.flowviz.data.SeriesRef;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link SeriesStyleResolver} backed by an explicit {@code SeriesRef -> Color} map.
 *
 * <p>Used by plot surfaces that assign their own fixed colours rather than the
 * user palette — the standalone FlowViz window and the optimisation convergence
 * plot. Every colour is paired with {@link StrokeStyle#DEFAULT}, reproducing the
 * solid 1.5px line those plots have always drawn.</p>
 */
public final class MapStyleResolver implements SeriesStyleResolver {

    /** Style used for a ref absent from the map; only a defensive fallback. */
    private static final Color FALLBACK_COLOR = Color.GRAY;

    private final Map<SeriesRef, Color> colors;

    /** Copies {@code colors} defensively; later mutations of the argument are not seen. */
    public MapStyleResolver(Map<SeriesRef, Color> colors) {
        this.colors = new HashMap<>(colors);
    }

    @Override
    public LineStyle styleFor(SeriesRef ref) {
        return new LineStyle(colors.getOrDefault(ref, FALLBACK_COLOR), StrokeStyle.DEFAULT);
    }
}
