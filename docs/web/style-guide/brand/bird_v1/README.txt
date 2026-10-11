Bird mark
=========

web/bird.svg             For the website. Takes its colour from the CSS `color`
                         of its surroundings when inlined in the HTML; renders
                         black when used as <img src>. Size it with CSS.
svg/bird-black.svg       Fixed black, for design tools.
svg/bird-white.svg       Fixed white, for dark backgrounds.
svg/bird-centreline.svg  The pen strokes as editable lines of uniform width.
png/                     Transparent PNGs, black and white, 512 to 2048 px tall.
print/bird.pdf           Vector PDF.

All files are cropped tight to the drawing; add spacing in the layout.

Inline use:
    <span style="color: #1a1a1a; width: 6rem; display: inline-block">
      ...contents of web/bird.svg...
    </span>
