package peergos.android.calendar;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * An event's own colour, which iCalendar writes as a CSS3 colour name (RFC 7986 COLOR) and
 * the contract holds as an ARGB int.
 *
 * A calendar app offers the colours a sync adapter lists in the Colors table, under a key,
 * so the mirror lists a palette of CSS names: one picked on the phone goes back to Peergos as
 * its name. A file can name any of the CSS colours, and one outside the palette still shows
 * in its own colour; it just has no key, and goes back as the CSS name nearest it, which for
 * a colour that came from a name is that same name.
 */
final class Colours {

    private Colours() {}

    /** The colours offered on the phone, by name. */
    static final List<String> PALETTE = List.of("tomato", "orange", "gold", "yellowgreen", "seagreen", "turquoise",
            "dodgerblue", "royalblue", "mediumpurple", "orchid", "hotpink", "slategray");

    private static final Map<String, Integer> NAMED = new LinkedHashMap<>();
    private static final Map<Integer, String> BY_RGB = new HashMap<>();
    static {
        Object[] pairs = {
            "aliceblue", 0xf0f8ff, "antiquewhite", 0xfaebd7, "aqua", 0x00ffff, "aquamarine", 0x7fffd4,
            "azure", 0xf0ffff, "beige", 0xf5f5dc, "bisque", 0xffe4c4, "black", 0x000000,
            "blanchedalmond", 0xffebcd, "blue", 0x0000ff, "blueviolet", 0x8a2be2, "brown", 0xa52a2a,
            "burlywood", 0xdeb887, "cadetblue", 0x5f9ea0, "chartreuse", 0x7fff00, "chocolate", 0xd2691e,
            "coral", 0xff7f50, "cornflowerblue", 0x6495ed, "cornsilk", 0xfff8dc, "crimson", 0xdc143c,
            "cyan", 0x00ffff, "darkblue", 0x00008b, "darkcyan", 0x008b8b, "darkgoldenrod", 0xb8860b,
            "darkgray", 0xa9a9a9, "darkgreen", 0x006400, "darkgrey", 0xa9a9a9, "darkkhaki", 0xbdb76b,
            "darkmagenta", 0x8b008b, "darkolivegreen", 0x556b2f, "darkorange", 0xff8c00, "darkorchid", 0x9932cc,
            "darkred", 0x8b0000, "darksalmon", 0xe9967a, "darkseagreen", 0x8fbc8f, "darkslateblue", 0x483d8b,
            "darkslategray", 0x2f4f4f, "darkslategrey", 0x2f4f4f, "darkturquoise", 0x00ced1,
            "darkviolet", 0x9400d3, "deeppink", 0xff1493, "deepskyblue", 0x00bfff, "dimgray", 0x696969,
            "dimgrey", 0x696969, "dodgerblue", 0x1e90ff, "firebrick", 0xb22222, "floralwhite", 0xfffaf0,
            "forestgreen", 0x228b22, "fuchsia", 0xff00ff, "gainsboro", 0xdcdcdc, "ghostwhite", 0xf8f8ff,
            "gold", 0xffd700, "goldenrod", 0xdaa520, "gray", 0x808080, "green", 0x008000, "greenyellow", 0xadff2f,
            "grey", 0x808080, "honeydew", 0xf0fff0, "hotpink", 0xff69b4, "indianred", 0xcd5c5c, "indigo", 0x4b0082,
            "ivory", 0xfffff0, "khaki", 0xf0e68c, "lavender", 0xe6e6fa, "lavenderblush", 0xfff0f5,
            "lawngreen", 0x7cfc00, "lemonchiffon", 0xfffacd, "lightblue", 0xadd8e6, "lightcoral", 0xf08080,
            "lightcyan", 0xe0ffff, "lightgoldenrodyellow", 0xfafad2, "lightgray", 0xd3d3d3, "lightgreen", 0x90ee90,
            "lightgrey", 0xd3d3d3, "lightpink", 0xffb6c1, "lightsalmon", 0xffa07a, "lightseagreen", 0x20b2aa,
            "lightskyblue", 0x87cefa, "lightslategray", 0x778899, "lightslategrey", 0x778899,
            "lightsteelblue", 0xb0c4de, "lightyellow", 0xffffe0, "lime", 0x00ff00, "limegreen", 0x32cd32,
            "linen", 0xfaf0e6, "magenta", 0xff00ff, "maroon", 0x800000, "mediumaquamarine", 0x66cdaa,
            "mediumblue", 0x0000cd, "mediumorchid", 0xba55d3, "mediumpurple", 0x9370db, "mediumseagreen", 0x3cb371,
            "mediumslateblue", 0x7b68ee, "mediumspringgreen", 0x00fa9a, "mediumturquoise", 0x48d1cc,
            "mediumvioletred", 0xc71585, "midnightblue", 0x191970, "mintcream", 0xf5fffa, "mistyrose", 0xffe4e1,
            "moccasin", 0xffe4b5, "navajowhite", 0xffdead, "navy", 0x000080, "oldlace", 0xfdf5e6,
            "olive", 0x808000, "olivedrab", 0x6b8e23, "orange", 0xffa500, "orangered", 0xff4500,
            "orchid", 0xda70d6, "palegoldenrod", 0xeee8aa, "palegreen", 0x98fb98, "paleturquoise", 0xafeeee,
            "palevioletred", 0xdb7093, "papayawhip", 0xffefd5, "peachpuff", 0xffdab9, "peru", 0xcd853f,
            "pink", 0xffc0cb, "plum", 0xdda0dd, "powderblue", 0xb0e0e6, "purple", 0x800080, "red", 0xff0000,
            "rosybrown", 0xbc8f8f, "royalblue", 0x4169e1, "saddlebrown", 0x8b4513, "salmon", 0xfa8072,
            "sandybrown", 0xf4a460, "seagreen", 0x2e8b57, "seashell", 0xfff5ee, "sienna", 0xa0522d,
            "silver", 0xc0c0c0, "skyblue", 0x87ceeb, "slateblue", 0x6a5acd, "slategray", 0x708090,
            "slategrey", 0x708090, "snow", 0xfffafa, "springgreen", 0x00ff7f, "steelblue", 0x4682b4,
            "tan", 0xd2b48c, "teal", 0x008080, "thistle", 0xd8bfd8, "tomato", 0xff6347, "turquoise", 0x40e0d0,
            "violet", 0xee82ee, "wheat", 0xf5deb3, "white", 0xffffff, "whitesmoke", 0xf5f5f5, "yellow", 0xffff00,
            "yellowgreen", 0x9acd32
        };
        for (int i = 0; i < pairs.length; i += 2) {
            NAMED.put((String) pairs[i], (Integer) pairs[i + 1]);
            BY_RGB.putIfAbsent((Integer) pairs[i + 1], (String) pairs[i]);
        }
    }

    /** The ARGB colour a COLOR value names: a CSS name, or a #rrggbb some clients write instead. */
    static Optional<Integer> parse(String value) {
        if (value == null)
            return Optional.empty();
        String v = value.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(v);
        if (named != null)
            return Optional.of(0xff000000 | named);
        if (v.length() == 7 && v.charAt(0) == '#') {
            try {
                return Optional.of(0xff000000 | Integer.parseInt(v.substring(1), 16));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The CSS name for a colour: its own if it has one, else the nearest. */
    static String nameOf(int argb) {
        int rgb = argb & 0xffffff;
        String exact = BY_RGB.get(rgb);
        if (exact != null)
            return exact;
        String nearest = null;
        long best = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> named : NAMED.entrySet()) {
            int other = named.getValue();
            long dr = ((rgb >> 16) & 0xff) - ((other >> 16) & 0xff);
            long dg = ((rgb >> 8) & 0xff) - ((other >> 8) & 0xff);
            long db = (rgb & 0xff) - (other & 0xff);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < best) {
                best = distance;
                nearest = named.getKey();
            }
        }
        return nearest;
    }

    /** The key a colour is listed under in the Colors table, if it is in the palette. */
    static Optional<String> paletteKey(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return PALETTE.contains(v) ? Optional.of(v) : Optional.empty();
    }
}
