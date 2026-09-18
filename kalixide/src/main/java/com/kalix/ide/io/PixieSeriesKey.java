package com.kalix.ide.io;

import java.io.File;

/**
 * Identity of one series in a Pixie pair, as served by {@link PixieStore}: the
 * canonical {@code .pxt} file, the generation of the index it was read from, and the
 * series' index within it (base 1, as the {@code .pxt} writes it).
 *
 * <p>Deliberately not the series name. Names are file content and, once sanitised
 * for the Run Manager tree, a display label; per ADR-0003 §2.1 a label is never
 * the key. Callers map their own display refs to this key.
 *
 * <p>The generation pins the key to the index it came from. When a changed pair is
 * re-opened, its index is re-read under a new generation, and keys from the old one
 * go stale rather than silently resolving to whatever series now sits at their index.
 *
 * @param pxtFile    canonical {@code .pxt} file of the pair
 * @param generation the store's generation of the index this key was read from
 * @param index      the series' {@code .pxt} index
 */
public record PixieSeriesKey(File pxtFile, long generation, int index) {
}
