package peergos.android.contacts;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import peergos.server.webdav.caldav.ICal;

/**
 * Writes the vCard properties the platform contacts provider can express.
 *
 * Edits are applied by patching the stored card rather than re-serialising it from the
 * data rows. A raw contact only carries what {@link VCardTranslator} maps, so rebuilding
 * from it would quietly drop the photo, IMPP addresses, CATEGORIES, X- properties and
 * anything else the web app or a CardDAV client put there — a round trip through the phone
 * would strip them. Patching leaves everything it does not recognise alone.
 *
 * Unlike an iCalendar property a vCard one repeats: a contact has several numbers, all
 * called TEL. So a patch replaces whole property *names* rather than single lines, which
 * is why it is given the names it manages as well as the lines to write.
 */
public final class VCardWriter {

    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC);

    private VCardWriter() {}

    /** One content line, split so a patch can drop a property whatever its parameters. */
    public static final class Line {
        final String name;
        final String rest;

        Line(String name, String rest) {
            this.name = name;
            this.rest = rest;
        }

        @Override
        public String toString() {
            return name + rest;
        }
    }

    public static Line text(String name, String value) {
        return new Line(name, ":" + escape(value));
    }

    /** A property with a TYPE parameter, which is how a work number differs from a home one. */
    public static Line typed(String name, String type, String value) {
        return type.isEmpty() ? text(name, value)
                : new Line(name, ";TYPE=" + type + ":" + escape(value));
    }

    /** A compound value, as N and ADR are: components joined by unescaped semicolons. */
    public static Line structured(String name, List<String> components) {
        return new Line(name, ":" + join(components));
    }

    public static Line typedStructured(String name, String type, List<String> components) {
        return type.isEmpty() ? structured(name, components)
                : new Line(name, ";TYPE=" + type + ":" + join(components));
    }

    public static Line raw(String name, String rest) {
        return new Line(name, ":" + rest);
    }

    public static Line timestamp(String name, long millis) {
        return new Line(name, ":" + UTC_STAMP.format(Instant.ofEpochMilli(millis)));
    }

    /**
     * Rewrites the properties named in {@code managed} inside the first VCARD, dropping
     * every line they had and putting the given ones where the first of them was, so an
     * edited card keeps its property order. Everything not named survives untouched.
     */
    public static String patch(String vcf, List<Line> lines, Collection<String> managed) {
        List<String> out = new ArrayList<>();
        boolean inCard = false;
        boolean done = false;
        boolean written = false;
        for (String line : ICal.unfold(vcf)) {
            String name = nameOf(line);
            if (! done && name.equals("BEGIN") && valueOf(line).equalsIgnoreCase("VCARD")) {
                inCard = true;
                out.add(line);
                continue;
            }
            if (inCard && name.equals("END") && valueOf(line).equalsIgnoreCase("VCARD")) {
                // A card that had none of these properties gets them at the end, which is
                // also where a card written from scratch would have them.
                if (! written)
                    lines.forEach(l -> out.add(l.toString()));
                out.add(line);
                inCard = false;
                done = true;
                continue;
            }
            if (inCard && managed.contains(name)) {
                if (! written) {
                    lines.forEach(l -> out.add(l.toString()));
                    written = true;
                }
                continue;
            }
            out.add(line);
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /** A whole VCARD for a contact that does not exist in Peergos yet. */
    public static String create(String uid, List<Line> properties) {
        List<String> out = new ArrayList<>();
        out.add("BEGIN:VCARD");
        // 3.0 rather than 4.0: it is what Android's own exporter and most CardDAV clients
        // read, and nothing mapped here needs anything 4.0 added.
        out.add("VERSION:3.0");
        out.add("PRODID:-//Peergos//Android//EN");
        out.add(text("UID", uid).toString());
        for (Line line : properties)
            out.add(line.toString());
        out.add(timestamp("REV", System.currentTimeMillis()).toString());
        out.add("END:VCARD");
        return String.join("\r\n", out) + "\r\n";
    }

    private static String join(List<String> components) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0)
                joined.append(';');
            joined.append(escape(components.get(i)));
        }
        return joined.toString();
    }

    private static String nameOf(String line) {
        int end = line.length();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ':' || c == ';') {
                end = i;
                break;
            }
        }
        String name = line.substring(0, end).trim().toUpperCase(Locale.ROOT);
        // "item1.EMAIL" is the EMAIL property, and a patch that missed the group prefix
        // would leave the old value behind beside the new one.
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String valueOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    /** RFC 6350 escaping, which also escapes the separators of a structured value. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case ';': out.append("\\;"); break;
                case ',': out.append("\\,"); break;
                case '\n': out.append("\\n"); break;
                case '\r': break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
