package com.kalix.ide.editor;

import com.kalix.ide.linter.parsing.AnnotationKey;
import com.kalix.ide.linter.parsing.IniContinuation;
import com.kalix.ide.linter.parsing.IniSyntax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;

/**
 * Finds the folds in Kalix INI text: regions, sections and multi-line values,
 * nested in that order. {@link #ranges(String)} is the whole grammar and a pure
 * function of the text; {@link #getFolds} only turns its line ranges into the
 * library's {@link Fold}s.
 *
 * <p>A fold starts at the line that stays visible when it is collapsed. Blank lines
 * trailing a fold stay outside it, so the gap between folds keeps showing. A section
 * or value also leaves its trailing comments outside, since a comment block above a
 * header conventionally introduces what sits below it, the reading
 * {@code NodeSectionLocator} takes for insertion; a region's own line is that
 * introduction, so a region takes its comments with it. A fold of one line is not a
 * fold.</p>
 * <ul>
 *   <li>A <em>region</em> starts at a {@code #@region = Title} line (ADR-0010 §6.3)
 *       and runs to the last non-blank line before the next region line or the end
 *       of the file.</li>
 *   <li>A <em>section</em> starts at a {@code [header]} line and runs to its last
 *       line of code before the next header or region line.</li>
 *   <li>A <em>value</em> starts at a flush-left line of code (a property, or a bare
 *       entry in {@code [data]} or {@code [outputs]}) and runs to its last line of
 *       code among the continuation lines that follow it, by
 *       {@link IniContinuation}'s rule.</li>
 * </ul>
 * <p>The parser is lenient where the linter is strict: a region line inside a
 * section still folds, it simply ends that section's fold, so folds never
 * overlap.</p>
 */
public final class KalixIniFoldParser implements FoldParser {

    /** The fold type of a region, above the library's own so "collapse all comments" leaves regions alone. */
    public static final int REGION_FOLD_TYPE = FoldType.FOLD_TYPE_USER_DEFINED_MIN;

    /** The three kinds of fold, outermost first. */
    public enum Kind {
        REGION, SECTION, VALUE;

        /** The library's type for this kind of fold. */
        int foldType() {
            return switch (this) {
                case REGION -> REGION_FOLD_TYPE;
                case SECTION, VALUE -> FoldType.CODE;
            };
        }
    }

    /**
     * A foldable run of lines, {@code firstLine} to {@code lastLine} inclusive,
     * zero-based. The first line stays visible when the fold is collapsed.
     */
    public record Range(Kind kind, int firstLine, int lastLine) {
    }

    /**
     * Every fold in {@code text}, in order of first line. A range that starts
     * inside another lies wholly within it.
     */
    public static List<Range> ranges(String text) {
        String[] lines = IniSyntax.splitLines(text);
        List<Range> ranges = new ArrayList<>();
        OpenFold region = new OpenFold(Kind.REGION);
        OpenFold section = new OpenFold(Kind.SECTION);
        OpenFold value = new OpenFold(Kind.VALUE);
        int lastCode = -1;
        int lastNonBlank = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean code = !IniSyntax.isBlankOrComment(line);
            if (IniSyntax.isSectionHeaderLine(line)) {
                value.close(ranges, lastCode);
                section.close(ranges, lastCode);
                section.open(i);
            } else if (isRegionLine(line)) {
                value.close(ranges, lastCode);
                section.close(ranges, lastCode);
                region.close(ranges, lastNonBlank);
                region.open(i);
            } else if (IniContinuation.isContinuationLine(line)) {
                // Continues whatever is open; one without code (whitespace, or an
                // indented comment) is skipped, as the engine skips it.
            } else if (code) {
                value.close(ranges, lastCode);
                value.open(i);
            } else {
                // A blank line or a flush-left comment ends a value and nothing else.
                value.close(ranges, lastCode);
            }
            if (code) {
                lastCode = i;
            }
            if (!line.isBlank()) {
                lastNonBlank = i;
            }
        }
        value.close(ranges, lastCode);
        section.close(ranges, lastCode);
        region.close(ranges, lastNonBlank);

        // Folds close inner first; the reader wants them outer first.
        ranges.sort(Comparator.comparingInt(Range::firstLine));
        return ranges;
    }

    private static boolean isRegionLine(String line) {
        IniSyntax.Annotation annotation = IniSyntax.annotation(line);
        return annotation != null && AnnotationKey.of(annotation.key()) == AnnotationKey.REGION;
    }

    /** A fold being scanned: where it opened, if it is open. */
    private static final class OpenFold {
        private final Kind kind;
        private int first = -1;

        OpenFold(Kind kind) {
            this.kind = kind;
        }

        void open(int line) {
            first = line;
        }

        /** Emits the fold, ending at {@code last}, if it is open and that is past its first line; then forgets it. */
        void close(List<Range> into, int last) {
            if (first >= 0 && last > first) {
                into.add(new Range(kind, first, last));
            }
            first = -1;
        }
    }

    @Override
    public List<Fold> getFolds(RSyntaxTextArea textArea) {
        List<Fold> folds = new ArrayList<>();
        Fold parent = null;
        try {
            for (Range range : ranges(textArea.getText())) {
                while (parent != null && parent.getEndLine() < range.firstLine()) {
                    parent = parent.getParent();
                }
                int type = range.kind().foldType();
                int start = textArea.getLineStartOffset(range.firstLine());
                Fold fold = parent == null ? new Fold(type, textArea, start) : parent.createChild(type, start);
                fold.setEndOffset(textArea.getLineStartOffset(range.lastLine()));
                if (parent == null) {
                    folds.add(fold);
                }
                parent = fold;
            }
        } catch (BadLocationException e) {
            // ranges() and the line offsets come from the same text, on the EDT.
            throw new IllegalStateException("fold range outside the document", e);
        }
        return folds;
    }
}
