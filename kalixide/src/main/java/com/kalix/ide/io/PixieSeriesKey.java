package com.kalix.ide.io;

import java.io.File;

/**
 * Identity of one series in a Pixie pair, as served by {@link PixieStore}: the
 * canonical {@code .pxt} file and the series' index within it (base 1, as the
 * {@code .pxt} writes it).
 *
 * <p>Deliberately not the series name. Names are file content and, once sanitised
 * for the Run Manager tree, a display label; per ADR-0003 §2.1 a label is never
 * the key. Callers map their own display refs to this key.
 *
 * @param pxtFile canonical {@code .pxt} file of the pair
 * @param index   the series' {@code .pxt} index
 */
public record PixieSeriesKey(File pxtFile, int index) {
}
