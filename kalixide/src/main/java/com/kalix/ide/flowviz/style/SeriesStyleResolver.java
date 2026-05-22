package com.kalix.ide.flowviz.style;

import com.kalix.ide.flowviz.data.SeriesRef;

/**
 * Resolves a {@link SeriesRef} to the {@link LineStyle} it should be drawn with.
 *
 * <p>This is the late-binding seam between series identity and visual style — the
 * direct analogue of {@code LabelResolver}, which projects a ref to its display
 * label. The renderer, legend, and coordinate overlay all consult a resolver at
 * paint time rather than caching a colour, so a palette edit or palette switch
 * propagates with nothing more than a repaint.</p>
 *
 * <p>Implementations must never return {@code null}.</p>
 *
 * @see PaletteSeriesStyleResolver
 * @see MapStyleResolver
 */
@FunctionalInterface
public interface SeriesStyleResolver {

    /** The style for {@code ref}; never {@code null}. */
    LineStyle styleFor(SeriesRef ref);
}
