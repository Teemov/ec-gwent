package io.github.teemov.ecgwent.engine;

import java.util.List;

/**
 * Serialises a match as one side sees it (JSON), for sending to that player's client.
 * The opponent's hand and both decks stay hidden.
 */
public final class View {
    private View() {}

    public static String of(Game g, int me, String opponentEntity, String result) {
        StringBuilder b = new StringBuilder("{");
        field(b, "phase", g.phase.name());
        field(b, "round", g.round);
        field(b, "myTurn", g.phase == Game.Phase.PLAY && g.current == me);
        field(b, "weather", weather(g));
        List<Card> wc = new java.util.ArrayList<>();
        for (Side s : g.sides) wc.addAll(s.weatherPlayed);
        b.append("\"weatherCards\":").append(cards(wc)).append(',');
        field(b, "opponentEntity", opponentEntity);
        if (g.winner != null) field(b, "winner", g.winner < 0 ? "draw" : (g.winner == me ? "me" : "them"));
        if (result != null) field(b, "result", result);
        b.append("\"me\":").append(side(g, g.sides[me], true)).append(',');
        b.append("\"them\":").append(side(g, g.sides[1 - me], false)).append(',');
        if (g.choice != null && g.choice.side == me) {
            b.append("\"choice\":{");
            field(b, "kind", g.choice.kind.name());
            field(b, "count", g.choice.count);
            b.append("\"options\":").append(cards(g.choice.options)).append('}').append(',');
        } else if (g.choice != null) {
            field(b, "waiting", true);
        }
        b.append("\"log\":[");
        int from = Math.max(0, g.log.size() - 6);
        boolean first = true;
        for (int i = from; i < g.log.size(); i++) {
            int s = g.logSide.get(i);
            if (s >= 0 && s != me) continue;
            if (!first) b.append(',');
            b.append(Json.quote(g.log.get(i)));
            first = false;
        }
        b.append("]}");
        return b.toString();
    }

    private static String weather(Game g) {
        StringBuilder w = new StringBuilder();
        for (Row r : g.weather) w.append(r.id()).append(' ');
        return w.toString().trim();
    }

    private static String side(Game g, Side s, boolean mine) {
        StringBuilder b = new StringBuilder("{");
        field(b, "name", s.name);
        field(b, "faction", s.faction);
        field(b, "leader", s.leader == null ? "" : s.leader.key);
        field(b, "leaderReady", !s.leaderUsed && !s.leaderBlocked);
        field(b, "leaderUsable", g.canUseLeader(s.index) && g.phase == Game.Phase.PLAY && g.current == s.index);
        field(b, "gems", s.gems);
        field(b, "passed", s.passed);
        field(b, "score", g.score(s));
        field(b, "handCount", s.hand.size());
        field(b, "deckCount", s.deck.size());
        field(b, "redrawsLeft", g.phase == Game.Phase.REDRAW && !s.redrawDone ? s.redrawsLeft : 0);
        b.append("\"grave\":").append(cards(s.grave)).append(',');
        b.append("\"rounds\":").append(s.roundScores.toString().replace(" ", "")).append(',');
        if (mine) b.append("\"hand\":").append(cards(s.hand)).append(',');
        b.append("\"rows\":{");
        boolean first = true;
        for (Row r : Row.values()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(r.id()).append("\":{");
            field(b, "score", g.rowScore(s, r));
            Card sp = s.special.get(r);
            field(b, "special", sp == null ? "" : sp.def.key);
            b.append("\"cards\":[");
            boolean f2 = true;
            for (Card c : s.rows.get(r)) {
                if (!f2) b.append(',');
                f2 = false;
                b.append("{\"uid\":").append(c.uid).append(",\"key\":").append(Json.quote(c.def.key))
                        .append(",\"strength\":").append(g.strength(s, r, c)).append('}');
            }
            b.append("]}");
        }
        b.append("}}");
        return b.toString();
    }

    private static String cards(List<Card> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(',');
            b.append("{\"uid\":").append(l.get(i).uid).append(",\"key\":").append(Json.quote(l.get(i).def.key)).append('}');
        }
        return b.append(']').toString();
    }

    private static void field(StringBuilder b, String k, Object v) {
        b.append(Json.quote(k)).append(':');
        if (v instanceof String) b.append(Json.quote((String) v));
        else b.append(v);
        b.append(',');
    }
}
