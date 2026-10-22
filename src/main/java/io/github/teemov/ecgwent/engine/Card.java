package io.github.teemov.ecgwent.engine;

/** One physical card in a match. The uid is unique within the match and is how clients refer to it. */
public final class Card {
    public final int uid;
    public final CardDef def;

    Card(int uid, CardDef def) {
        this.uid = uid;
        this.def = def;
    }

    @Override public String toString() { return def.name + "#" + uid; }
}
