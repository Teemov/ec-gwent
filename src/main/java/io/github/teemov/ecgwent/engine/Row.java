package io.github.teemov.ecgwent.engine;

/** The three combat rows on each side of the board. */
public enum Row {
    CLOSE, RANGED, SIEGE;

    public static Row parse(String s) {
        if (s == null) return null;
        switch (s) {
            case "close": return CLOSE;
            case "ranged": return RANGED;
            case "siege": return SIEGE;
            default: return null;
        }
    }

    public String id() { return name().toLowerCase(); }
}
