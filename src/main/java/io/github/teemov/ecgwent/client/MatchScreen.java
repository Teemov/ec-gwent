package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The match board, laid out like Gwent in The Witcher 3 and drawn as a wooden table:
 * leaders, player bars and the weather slot on the left; three rows per side with row scores and horn slots
 * in the middle and the hand below; graveyards and decks on the right.
 * The server is in charge; this screen shows its latest snapshot, animates what changed and sends the player's actions.
 */
public final class MatchScreen extends CanvasScreen {
    private static final String[] ROWS = {"close", "ranged", "siege"};
    private static final String[] ROW_NAMES = {"close", "ranged", "siege"};

    // ---------------------------------------------------------------- layout (canvas coordinates, 640×360)
    private static final int BOARD_X0 = 164, BOARD_X1 = 512;
    private static final int ROW_H = 44, OPP_Y = 6, MY_Y = 144;
    private static final int SCORE_CX = 177, SLOT_X = 191, CARDS_X0 = 228, CARDS_X1 = 508;
    private static final int CW = 30, CH = 40;
    private static final int HAND_X0 = 191, HAND_X1 = 508, HAND_Y = 282, HAND_CW = 32, HAND_CH = 44;
    private static final int LEADER_X = 46, OPP_LEADER_Y = 16, MY_LEADER_Y = 288, LW = 34, LH = 47;
    private static final int OPP_BAR_Y = 86, MY_BAR_Y = 222, BAR_H = 48;
    private static final int WEATHER_X = 40, WEATHER_Y = 146, WEATHER_W = 112, WEATHER_H = 48;
    private static final int GRAVE_X = 524, DECK_X = 578, OPP_PILE_Y = 14, MY_PILE_Y = 286, PW = 34, PH = 47;

    private static final long ANIM_MS = 340, ANIM_STAGGER = 70, BANNER_MS = 1150;

    /** Clickable areas found while drawing. */
    private record Hit(int x, int y, int w, int h, String kind, int uid, boolean mine, String row) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record Anim(float x, float y, float w, float h, long start) {}

    private record Deferred(CardDef def, float x, float y, float w, float h, int strength) {}

    private final List<Hit> hits = new ArrayList<>();
    private int selected = -1;
    private final List<Integer> picks = new ArrayList<>();

    // Animation state
    private final Map<Integer, float[]> lastPos = new HashMap<>(), framePos = new HashMap<>();
    private Map<Integer, String> lastLoc = new HashMap<>();
    private final Map<Integer, Anim> anims = new HashMap<>();
    private final List<Deferred> deferred = new ArrayList<>();
    // Banners ("Your turn", "Round won", ...)
    private final Deque<String> banners = new ArrayDeque<>();
    private String banner;
    private long bannerStart;
    private String lastTurnKey = "";
    private int lastMyGems = 2, lastTheirGems = 2, lastRound = 1;
    private boolean lastTheyPassed;
    // Input
    private long spaceHeld;
    private long forfeitArmed;

    public MatchScreen() {
        super("Gwent");
        refresh();
    }

    // ---------------------------------------------------------------- snapshot access

    private static Map<String, Object> m() { return ClientHooks.match; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> o, String k) { return o == null ? null : (Map<String, Object>) o.get(k); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> o, String k) {
        Object v = o == null ? null : o.get(k);
        return v == null ? Collections.emptyList() : (List<Map<String, Object>>) v;
    }

    private static int num(Map<String, Object> o, String k) { Object v = o == null ? null : o.get(k); return v instanceof Double ? ((Double) v).intValue() : 0; }
    private static boolean bool(Map<String, Object> o, String k) { return o != null && Boolean.TRUE.equals(o.get(k)); }
    private static String str(Map<String, Object> o, String k) { Object v = o == null ? null : o.get(k); return v == null ? "" : v.toString(); }
    private static CardDef def(String key) { return ClientHooks.cards.get(key); }

    private String phase() { return str(m(), "phase"); }
    private boolean over() { return m() != null && m().containsKey("winner"); }
    private Map<String, Object> me() { return map(m(), "me"); }
    private Map<String, Object> them() { return map(m(), "them"); }
    private Map<String, Object> choice() { return map(m(), "choice"); }
    private boolean redrawing() { return phase().equals("REDRAW") && num(me(), "redrawsLeft") > 0; }
    private boolean myTurn() { return bool(m(), "myTurn"); }

    // ---------------------------------------------------------------- new snapshot: animations and banners

    /** Called when a new snapshot arrives. */
    public void refresh() {
        if (m() == null) return;
        if (choice() == null) picks.clear();
        if (selected >= 0 && list(me(), "hand").stream().noneMatch(c -> num(c, "uid") == selected)) selected = -1;

        // Where is every visible card now?
        Map<Integer, String> loc = new HashMap<>();
        Map<Integer, CardDef> defs = new HashMap<>();
        for (Map<String, Object> c : list(me(), "hand")) { loc.put(num(c, "uid"), "m:hand"); defs.put(num(c, "uid"), def(str(c, "key"))); }
        for (String side : new String[]{"m", "t"}) {
            Map<String, Object> s = side.equals("m") ? me() : them();
            for (String r : ROWS) for (Map<String, Object> c : list(map(map(s, "rows"), r), "cards")) {
                loc.put(num(c, "uid"), side + ":" + r);
                defs.put(num(c, "uid"), def(str(c, "key")));
            }
            List<Map<String, Object>> grave = list(s, "grave");
            for (Map<String, Object> c : grave) loc.put(num(c, "uid"), side + ":grave");
        }
        for (Map<String, Object> c : list(m(), "weatherCards")) loc.put(num(c, "uid"), "weather");

        if (!lastLoc.isEmpty()) {
            long now = Util.getMillis();
            int n = 0, theirNew = 0;
            for (Map.Entry<Integer, String> e : loc.entrySet()) {
                int uid = e.getKey();
                String now2 = e.getValue(), before = lastLoc.get(uid);
                if (now2.equals(before) || now2.endsWith(":grave") && !isGraveTop(uid)) continue;
                float[] from = lastPos.get(uid);
                if (from == null) from = source(before, now2, defs.get(uid), theirNew++);
                if (from == null) continue;
                anims.put(uid, new Anim(from[0], from[1], from[2], from[3], now + n++ * ANIM_STAGGER));
            }
        }
        lastLoc = loc;

        // Banners
        int myGems = num(me(), "gems"), theirGems = num(them(), "gems"), round = num(m(), "round");
        if (myGems < lastMyGems || theirGems < lastTheirGems) {
            boolean iLost = myGems < lastMyGems, theyLost = theirGems < lastTheirGems;
            banners.add(iLost && theyLost ? "Round drawn" : theyLost ? "Round won!" : "Round lost");
        }
        if (bool(them(), "passed") && !lastTheyPassed && !over()) banners.add("Opponent passed");
        if (round != lastRound && !over()) banners.add("Round " + round);
        String turnKey = phase().equals("PLAY") && !over() ? (myTurn() ? "me" : "them") + round : "";
        if (!turnKey.isEmpty() && !turnKey.equals(lastTurnKey) && !(bool(me(), "passed") && !myTurn()))
            banners.add(myTurn() ? "Your turn" : "Opponent's turn");
        lastTurnKey = turnKey;
        lastMyGems = myGems;
        lastTheirGems = theirGems;
        lastRound = round;
        lastTheyPassed = bool(them(), "passed");
    }

    private boolean isGraveTop(int uid) {
        for (Map<String, Object> s : new Map[]{me(), them()}) {
            List<Map<String, Object>> g = list(s, "grave");
            if (!g.isEmpty() && num(g.get(g.size() - 1), "uid") == uid) return true;
        }
        return false;
    }

    /** Where a card that wasn't visible before comes from. */
    private float[] source(String before, String now, CardDef d, int theirIndex) {
        if ("m:grave".equals(before)) return rect(GRAVE_X, MY_PILE_Y, PW, PH);
        if ("t:grave".equals(before)) return rect(GRAVE_X, OPP_PILE_Y, PW, PH);
        if (now.startsWith("m:")) {
            if (d != null && d.has("spy") && !now.equals("m:hand")) return rect(80, OPP_BAR_Y + 10, CW, CH); // their spy
            return rect(DECK_X, MY_PILE_Y, PW, PH);
        }
        if (now.startsWith("t:")) {
            if (theirIndex > 0) return rect(DECK_X, OPP_PILE_Y, PW, PH); // muster pulls from their deck
            return rect(80, OPP_BAR_Y + 10, CW, CH);
        }
        if (now.equals("weather")) return rect(80, OPP_BAR_Y + 10, CW, CH);
        return null;
    }

    private static float[] rect(float x, float y, float w, float h) { return new float[]{x, y, w, h}; }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        deferred.clear();
        framePos.clear();
        if (m() == null) return;
        boolean overlay = over() || redrawing() || choice() != null || phase().equals("CHOOSE_FIRST") || phase().equals("REDRAW");
        boolean interactive = !overlay;

        drawTable(g);
        drawRows(g, mx, my, interactive);
        drawPlayerBar(g, them(), OPP_BAR_Y, false, mx, my);
        drawPlayerBar(g, me(), MY_BAR_Y, true, mx, my);
        drawLeader(g, them(), OPP_LEADER_Y, false, mx, my);
        drawLeader(g, me(), MY_LEADER_Y, true, mx, my);
        drawWeather(g, mx, my);
        drawPiles(g, them(), OPP_PILE_Y, mx, my);
        drawPiles(g, me(), MY_PILE_Y, mx, my);
        drawControls(g, mx, my, interactive);
        if (!overlay) drawHand(g, mx, my);

        // Cards in flight go over everything on the table.
        g.pose().pushPose();
        g.pose().translate(0, 0, 4000);
        for (Deferred d : deferred) CardArt.draw(g, d.def, Math.round(d.x), Math.round(d.y), Math.round(d.w), Math.round(d.h), d.strength, false, 0, 0);
        g.pose().popPose();

        drawBanner(g);
        drawOverlays(g, mx, my);
        lastPos.clear();
        lastPos.putAll(framePos);
        anims.entrySet().removeIf(e -> Util.getMillis() - e.getValue().start > ANIM_MS + 50 && !framePos.containsKey(e.getKey()));
    }

    /** Draws a card, or schedules it in flight if it is moving. Records where it was drawn. */
    private void card(GuiGraphics g, int uid, CardDef d, int x, int y, int w, int h, int strength, boolean highlight, float lookX, float lookY) {
        framePos.put(uid, rect(x, y, w, h));
        Anim a = anims.get(uid);
        if (a != null) {
            long t0 = Util.getMillis() - a.start;
            float t = Math.max(0, Math.min(1, t0 / (float) ANIM_MS));
            if (t < 1) {
                float e = 1 - (1 - t) * (1 - t) * (1 - t); // ease out
                deferred.add(new Deferred(d, a.x + (x - a.x) * e, a.y + (y - a.y) * e, a.w + (w - a.w) * e, a.h + (h - a.h) * e, strength));
                return;
            }
            anims.remove(uid);
        }
        CardArt.draw(g, d, x, y, w, h, strength, highlight, lookX, lookY);
    }

    /** The wooden table: dark rim, plank surface, board inlay and a gold divider between the two sides. */
    private void drawTable(GuiGraphics g) {
        tile(g, Blocks.DARK_OAK_PLANKS, 0, 0, VW, VH, 32);
        g.fill(0, 0, VW, VH, 0x90000000);
        // Table top with a rim
        tile(g, Blocks.STRIPPED_DARK_OAK_LOG, 2, 1, VW - 4, VH - 2, 24);
        g.fill(2, 1, VW - 2, VH - 1, 0x40000000);
        tile(g, Blocks.SPRUCE_PLANKS, 7, 5, VW - 14, VH - 10, 24);
        g.fill(7, 5, VW - 7, VH - 5, 0x58100800);
        // Edge shading (light falls on the middle of the table)
        for (int i = 0; i < 10; i++) {
            int a = (10 - i) * 6 << 24;
            g.fill(7 + i, 5, 8 + i, VH - 5, a);
            g.fill(VW - 8 - i, 5, VW - 7 - i, VH - 5, a);
            g.fill(7, 5 + i, VW - 7, 6 + i, a);
            g.fill(7, VH - 6 - i, VW - 7, VH - 5 - i, a);
        }
        // Board inlay behind the rows
        g.fill(BOARD_X0 - 3, OPP_Y - 3, BOARD_X1 + 3, MY_Y + ROW_H * 3 + 3, 0xFF3A2614);
        g.fill(BOARD_X0 - 2, OPP_Y - 2, BOARD_X1 + 2, MY_Y + ROW_H * 3 + 2, 0xFF6A4A26);
        tile(g, Blocks.OAK_PLANKS, BOARD_X0 - 1, OPP_Y - 1, BOARD_X1 - BOARD_X0 + 2, MY_Y + ROW_H * 3 - OPP_Y + 2, 24);
        g.fill(BOARD_X0 - 1, OPP_Y - 1, BOARD_X1 + 1, MY_Y + ROW_H * 3 + 1, 0x70140C04);
        int midY = OPP_Y + ROW_H * 3;
        g.fill(BOARD_X0 - 3, midY, BOARD_X1 + 3, midY + 2, 0xFFC9A227);
        g.fill(BOARD_X0 - 3, midY + 2, BOARD_X1 + 3, MY_Y, 0xFF3A2614);
    }

    private void drawRows(GuiGraphics g, int mx, int my, boolean interactive) {
        String weather = str(m(), "weather");
        CardDef sel = interactive ? selectedDef() : null;
        for (int i = 0; i < 6; i++) {
            boolean mine = i >= 3;
            int ri = mine ? i - 3 : 2 - i;
            String row = ROWS[ri];
            int y = mine ? MY_Y + ri * ROW_H : OPP_Y + i * ROW_H;
            Map<String, Object> r = map(map(mine ? me() : them(), "rows"), row);
            // Recessed slots: horn slot and card strip
            inset(g, SLOT_X - 2, y + 1, CW + 4, ROW_H - 2);
            inset(g, CARDS_X0 - 2, y + 1, CARDS_X1 - CARDS_X0 + 4, ROW_H - 2);
            if (weather.contains(row)) {
                int tint = row.equals("close") ? 0x5090C8FF : row.equals("ranged") ? 0x60B0B0B8 : 0x5030508C;
                g.fill(CARDS_X0 - 1, y + 2, CARDS_X1 + 1, y + ROW_H - 2, tint);
            }
            boolean target = rowTargetable(sel, mine, row);
            if (target) {
                boolean hov = in(mx, my, BOARD_X0, y, BOARD_X1 - BOARD_X0, ROW_H);
                g.fill(SLOT_X - 1, y + 2, CARDS_X1 + 1, y + ROW_H - 2, hov ? 0x60FFD070 : 0x30FFD070);
                outline(g, SLOT_X - 2, y + 1, CARDS_X1 - SLOT_X + 4, ROW_H - 2, 0xC0FFD070);
            }
            hits.add(new Hit(BOARD_X0, y, BOARD_X1 - BOARD_X0, ROW_H, "row", -1, mine, row));
            // Row score
            scoreDisc(g, SCORE_CX, y + ROW_H / 2, 9, num(r, "score"), false);
            // Horn slot
            CardDef sp = def(str(r, "special"));
            if (sp != null) {
                CardArt.draw(g, sp, SLOT_X, y + 2, CW, CH, Integer.MIN_VALUE, false, 10, 0);
                if (in(mx, my, SLOT_X, y + 2, CW, CH)) preview(sp, Integer.MIN_VALUE);
            } else {
                small(g, ROW_NAMES[ri], SLOT_X + (CW - (int) (font.width(ROW_NAMES[ri]) * 0.75f)) / 2, y + ROW_H / 2 - 3, CW, 0x50FFFFFF);
            }
            // Units
            List<Map<String, Object>> cards = list(r, "cards");
            int areaW = CARDS_X1 - CARDS_X0;
            int step = cards.size() <= 1 ? CW + 1 : Math.min(CW + 1, (areaW - CW) / (cards.size() - 1));
            int startX = CARDS_X0 + Math.max(0, (areaW - (step * (cards.size() - 1) + CW)) / 2);
            for (int k = 0; k < cards.size(); k++) {
                Map<String, Object> c = cards.get(k);
                CardDef d = def(str(c, "key"));
                if (d == null) continue;
                int cx = startX + k * step;
                int hitW = k == cards.size() - 1 ? CW : Math.min(step, CW);
                boolean decoyTarget = sel != null && sel.has("decoy") && mine && !d.isHero() && !d.has("decoy");
                boolean hov = interactive && in(mx, my, cx, y + 2, hitW, CH);
                g.pose().pushPose();
                g.pose().translate(0, 0, k * CardArt.Z_STEP / 2f);
                card(g, num(c, "uid"), d, cx, y + 2, CW, CH, num(c, "strength"), decoyTarget || hov, 14, 4);
                g.pose().popPose();
                hits.add(new Hit(cx, y + 2, hitW, CH, "board", num(c, "uid"), mine, row));
                if (hov) preview(d, num(c, "strength"));
            }
        }
    }

    /** A recessed slot cut into the table. */
    private static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x50000000);
        g.fill(x, y, x + w, y + 1, 0x90000000);
        g.fill(x, y, x + 1, y + h, 0x70000000);
        g.fill(x, y + h - 1, x + w, y + h, 0x30FFE0B0);
        g.fill(x + w - 1, y, x + w, y + h, 0x20FFE0B0);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    /** A round score badge: silver, or gold for the side that is ahead. */
    private void scoreDisc(GuiGraphics g, int cx, int cy, int r, int value, boolean gold) {
        disc(g, cx, cy, r + 1, 0xFF1A120A);
        disc(g, cx, cy, r, gold ? 0xFFE0B040 : 0xFFB8C0C8);
        disc(g, cx, cy, r - 2, gold ? 0xFF4A3410 : 0xFF2A3038);
        String t = String.valueOf(value);
        text(g, t, cx - font.width(t) / 2 + 1, cy - 3, 0xFFFFFFFF);
    }

    private void drawPlayerBar(GuiGraphics g, Map<String, Object> s, int y, boolean mine, int mx, int my) {
        boolean active = phase().equals("PLAY") && !over() && (mine == myTurn());
        int x0 = 9, x1 = 160;
        g.fill(x0, y, x1, y + BAR_H, 0xD0201408);
        g.fill(x0, y, x1, y + 1, active ? 0xFFE0C060 : 0xFF6A5A30);
        g.fill(x0, y + BAR_H - 1, x1, y + BAR_H, active ? 0xFFE0C060 : 0xFF6A5A30);
        if (active) g.fill(x0, y + 1, x1, y + BAR_H - 1, 0x20FFD070);
        // Portrait
        int px = 14, py = y + 5, pw = 30, ph = 38;
        g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFFB08A3E);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF1A120A);
        CardArt.scissor(g, px, py, px + pw, py + ph);
        CardArt.backdrop(g, str(s, "faction"), px, py, pw, ph);
        CardArt.drawSubject(g, mine ? "self" : str(m(), "opponentEntity"), null, px, py, pw, ph, px + pw / 2f - mx, py + 12 - my);
        g.disableScissor();
        // Name, faction, cards in hand, gems
        int ix = 50;
        small(g, str(s, "name"), ix, y + 6, 82, 0xFFF0E0B0);
        small(g, ClientHooks.cards.factionName(str(s, "faction")), ix, y + 15, 82, 0xFFA89870);
        g.fill(ix, y + 28, ix + 7, y + 38, 0xFF8A7440);
        g.fill(ix + 2, y + 26, ix + 9, y + 36, 0xFFE0C890);
        text(g, String.valueOf(num(s, "handCount")), ix + 12, y + 28, 0xFFFFFFFF);
        for (int i = 0; i < 2; i++) {
            int gx = ix + 38 + i * 13, gy = y + 32;
            boolean has = i < num(s, "gems");
            disc(g, gx, gy, 5, 0xFF1A120A);
            disc(g, gx, gy, 4, has ? 0xFFD02828 : 0xFF4A4A4A);
            if (has) g.fill(gx - 2, gy - 3, gx, gy - 1, 0xFFFF9090);
        }
        // Total score
        int mine2 = num(me(), "score"), theirs = num(them(), "score");
        boolean ahead = mine ? mine2 > theirs : theirs > mine2;
        scoreDisc(g, 149, y + BAR_H / 2, 11, num(s, "score"), ahead);
        if (bool(s, "passed")) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            text(g, "Passed", 118, mine ? y - 11 : y + BAR_H + 3, 0xFFFFC060);
            g.pose().popPose();
        }
    }

    private void drawLeader(GuiGraphics g, Map<String, Object> s, int y, boolean mine, int mx, int my) {
        int x = LEADER_X;
        inset(g, x - 3, y - 3, LW + 6, LH + 6);
        CardDef leader = def(str(s, "leader"));
        if (leader == null) return;
        boolean usable = mine && bool(s, "leaderUsable");
        boolean hov = in(mx, my, x, y, LW, LH);
        CardArt.draw(g, leader, x, y, LW, LH, Integer.MIN_VALUE, usable && hov, 12, 2);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (!bool(s, "leaderReady")) {
            g.fill(x, y, x + LW, y + LH, 0xB0000000);
            small(g, "used", x + 9, y + 20, 30, 0xFFAAAAAA);
        }
        // Crown badge: lit when the ability can be used now
        int bx = x + LW + 10, by = y + LH / 2;
        disc(g, bx, by, 7, 0xFF1A120A);
        disc(g, bx, by, 6, usable ? 0xFFE0B040 : bool(s, "leaderReady") ? 0xFF6A5A30 : 0xFF3A3A3A);
        g.fill(bx - 3, by - 1, bx + 4, by + 3, 0xFF1A120A);
        g.fill(bx - 3, by - 3, bx - 2, by - 1, 0xFF1A120A);
        g.fill(bx, by - 4, bx + 1, by - 1, 0xFF1A120A);
        g.fill(bx + 3, by - 3, bx + 4, by - 1, 0xFF1A120A);
        if (usable) small(g, "Click to use", bx + 10, by - 3, 60, 0xFFFFE080);
        g.pose().popPose();
        if (mine) hits.add(new Hit(x, y, LW, LH, "leader", -1, true, null));
        if (hov) preview(leader, Integer.MIN_VALUE);
    }

    private void drawWeather(GuiGraphics g, int mx, int my) {
        inset(g, WEATHER_X, WEATHER_Y, WEATHER_W, WEATHER_H);
        small(g, "Round " + num(m(), "round"), WEATHER_X + 3, WEATHER_Y - 8, 60, 0xFFE0C890);
        List<Map<String, Object>> wc = list(m(), "weatherCards");
        if (wc.isEmpty()) {
            small(g, "weather", WEATHER_X + WEATHER_W / 2 - 13, WEATHER_Y + WEATHER_H / 2 - 3, 60, 0x50FFFFFF);
            return;
        }
        int step = wc.size() <= 1 ? CW + 2 : Math.min(CW + 2, (WEATHER_W - 8 - CW) / (wc.size() - 1));
        int x0 = WEATHER_X + Math.max(4, (WEATHER_W - (step * (wc.size() - 1) + CW)) / 2);
        for (int k = 0; k < wc.size(); k++) {
            CardDef d = def(str(wc.get(k), "key"));
            if (d == null) continue;
            int x = x0 + k * step, y = WEATHER_Y + 4;
            g.pose().pushPose();
            g.pose().translate(0, 0, k * CardArt.Z_STEP / 2f);
            card(g, num(wc.get(k), "uid"), d, x, y, CW, CH, Integer.MIN_VALUE, false, 0, 0);
            g.pose().popPose();
            if (in(mx, my, x, y, Math.min(step, CW), CH)) preview(d, Integer.MIN_VALUE);
        }
    }

    private void drawPiles(GuiGraphics g, Map<String, Object> s, int y, int mx, int my) {
        // Graveyard: the top card
        inset(g, GRAVE_X - 3, y - 3, PW + 6, PH + 6);
        List<Map<String, Object>> grave = list(s, "grave");
        if (!grave.isEmpty()) {
            Map<String, Object> top = grave.get(grave.size() - 1);
            CardDef d = def(str(top, "key"));
            if (d != null) {
                card(g, num(top, "uid"), d, GRAVE_X, y, PW, PH, Integer.MIN_VALUE, false, 0, 0);
                if (in(mx, my, GRAVE_X, y, PW, PH)) preview(d, Integer.MIN_VALUE);
            }
        } else {
            small(g, "grave", GRAVE_X + 8, y + PH / 2 - 3, 40, 0x50FFFFFF);
        }
        // Deck: a stack of card backs and the count
        int n = num(s, "deckCount");
        inset(g, DECK_X - 3, y - 3, PW + 6, PH + 6);
        if (n > 0) {
            for (int i = Math.min(3, (n + 4) / 5) - 1; i >= 0; i--) cardBack(g, str(s, "faction"), DECK_X - i, y - i, PW, PH);
        }
        String t = String.valueOf(n);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(DECK_X + PW / 2 - 9, y + PH - 6, DECK_X + PW / 2 + 9, y + PH + 4, 0xE0101010);
        text(g, t, DECK_X + PW / 2 - font.width(t) / 2 + 1, y + PH - 5, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** The back of a card in the faction's colour. */
    private static void cardBack(GuiGraphics g, String faction, int x, int y, int w, int h) {
        int c = Texts.FACTION_COLOR.getOrDefault(faction, 0xFF555555);
        g.fill(x, y, x + w, y + h, 0xFF1A120A);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC9A227);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, c);
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, (c & 0x00FFFFFF) | 0xFF000000);
        int cx = x + w / 2, cy = y + h / 2;
        for (int i = 0; i < 8; i++) g.fill(cx - i, cy - 8 + i, cx + i + 1, cy - 7 + i, 0x60FFE0A0);
        for (int i = 0; i < 8; i++) g.fill(cx - 7 + i, cy + i, cx + 8 - i, cy + i + 1, 0x60FFE0A0);
    }

    private void drawControls(GuiGraphics g, int mx, int my, boolean interactive) {
        boolean canPass = interactive && myTurn() && !over();
        button(g, WEATHER_X, 200, 60, 16, "Pass", canPass, mx, my, () -> Net.toServer(Net.MatchAction.of("pass")));
        small(g, "or hold Space", WEATHER_X + 64, 205, 60, canPass ? 0xFFBBAA88 : 0x60BBAA88);
        if (spaceHeld > 0 && canPass) {
            float p = Math.min(1, (Util.getMillis() - spaceHeld) / 700f);
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(WEATHER_X + 1, 214, WEATHER_X + 1 + (int) (58 * p), 215, 0xFFFFE080);
            g.pose().popPose();
            if (p >= 1) {
                Net.toServer(Net.MatchAction.of("pass"));
                spaceHeld = -1;
            }
        }
        // Forfeit (asks once)
        boolean armed = Util.getMillis() - forfeitArmed < 3000;
        button(g, VW - 70, VH - 22, 58, 14, armed ? "Sure?" : "Forfeit", !over(), mx, my, () -> {
            if (Util.getMillis() - forfeitArmed < 3000) Net.toServer(Net.MatchAction.of("forfeit"));
            else forfeitArmed = Util.getMillis();
        });
        // Status and the latest event, under the hand
        if (interactive) {
            String status = statusLine();
            g.drawCenteredString(font, status, (HAND_X0 + HAND_X1) / 2, 332, 0xFFFFE9A0);
        }
        @SuppressWarnings("unchecked")
        List<Object> log = (List<Object>) m().get("log");
        if (log != null && !log.isEmpty()) {
            String last = log.get(log.size() - 1).toString();
            int w = (int) (font.width(last) * 0.75f);
            small(g, last, (HAND_X0 + HAND_X1) / 2 - Math.min(w, 300) / 2, 344, 300, 0xFFB0A080);
        }
    }

    private void drawHand(GuiGraphics g, int mx, int my) {
        inset(g, HAND_X0 - 4, HAND_Y - 3, HAND_X1 - HAND_X0 + 8, HAND_CH + 6);
        List<Map<String, Object>> hand = list(me(), "hand");
        int areaW = HAND_X1 - HAND_X0;
        int step = hand.size() <= 1 ? HAND_CW + 2 : Math.min(HAND_CW + 2, (areaW - HAND_CW) / (hand.size() - 1));
        int start = HAND_X0 + Math.max(0, (areaW - (step * (hand.size() - 1) + HAND_CW)) / 2);
        for (int k = 0; k < hand.size(); k++) {
            Map<String, Object> c = hand.get(k);
            CardDef d = def(str(c, "key"));
            if (d == null) continue;
            int uid = num(c, "uid");
            int hitW = k == hand.size() - 1 ? HAND_CW : Math.min(step, HAND_CW);
            boolean hov = in(mx, my, start + k * step, HAND_Y, hitW, HAND_CH);
            int cx = start + k * step, cy = HAND_Y - (uid == selected ? 7 : hov ? 3 : 0);
            g.pose().pushPose();
            g.pose().translate(0, 0, k * CardArt.Z_STEP / 2f);
            card(g, uid, d, cx, cy, HAND_CW, HAND_CH, Integer.MIN_VALUE, uid == selected || hov, cx + HAND_CW / 2f - mx, cy + 10 - my);
            g.pose().popPose();
            hits.add(new Hit(cx, HAND_Y - 7, hitW, HAND_CH + 7, "hand", uid, true, null));
            if (hov) preview(d, Integer.MIN_VALUE);
        }
    }

    private String statusLine() {
        CardDef sel = selectedDef();
        if (sel != null) {
            if (sel.has("decoy")) return "Click one of your units to take it back into your hand.";
            if (sel.has("horn") || sel.has("mardroeme")) return "Click one of your rows.";
            if (!sel.isUnit()) return "Click the board, or the card again, to play it.";
            if (sel.isAgile()) return "Click your close or ranged row.";
            if (sel.has("spy")) return "Spy: click the highlighted enemy row, or the card again.";
            return "Click the highlighted row, or the card again. Right-click cancels.";
        }
        if (myTurn()) return "Your turn: play a card" + (bool(me(), "leaderUsable") ? ", use your leader" : "") + " or pass.";
        if (bool(me(), "passed")) return "You passed. Your opponent finishes the round…";
        return "Your opponent is thinking…";
    }

    /** "Your turn", "Round won!" and so on, across the middle of the board. */
    private void drawBanner(GuiGraphics g) {
        long now = Util.getMillis();
        if (banner == null || now - bannerStart > BANNER_MS) {
            banner = banners.poll();
            bannerStart = now;
            if (banner == null) return;
        }
        long t = now - bannerStart;
        float a = t < 150 ? t / 150f : t > BANNER_MS - 250 ? Math.max(0, (BANNER_MS - t) / 250f) : 1;
        int alpha = (int) (a * 255);
        if (alpha < 8) return;
        int cy = OPP_Y + ROW_H * 3 + 1;
        g.pose().pushPose();
        g.pose().translate(0, 0, 4500);
        int x0 = BOARD_X0 - 20, x1 = BOARD_X1 + 20;
        g.fill(x0, cy - 17, x1, cy + 17, (int) (a * 0xC8) << 24);
        g.fill(x0, cy - 18, x1, cy - 17, (alpha << 24) | 0xC9A227);
        g.fill(x0, cy + 17, x1, cy + 18, (alpha << 24) | 0xC9A227);
        boolean good = banner.startsWith("Your") || banner.startsWith("Round won");
        int color = (alpha << 24) | (good ? 0xFFE080 : banner.startsWith("Round") && !banner.contains("lost") ? 0xF0F0F0 : 0xFF9F9F);
        float s = 2f;
        g.pose().translate((BOARD_X0 + BOARD_X1) / 2f - font.width(banner) * s / 2f, cy - 7, 0);
        g.pose().scale(s, s, 1);
        g.drawString(font, banner, 0, 0, color, true);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- overlays (redraw, choices, result)

    private void drawOverlays(GuiGraphics g, int mx, int my) {
        g.pose().pushPose();
        g.pose().translate(0, 0, CanvasScreen.Z_OVERLAY);
        try {
            if (over()) { drawResult(g, mx, my); return; }
            if (phase().equals("CHOOSE_FIRST")) {
                dim(g);
                if (choice() != null) {
                    g.drawCenteredString(font, "Orc perk: you decide who goes first.", VW / 2, 140, 0xFFFFE9A0);
                    button(g, VW / 2 - 106, 162, 100, 20, "I go first", true, mx, my, () -> Net.toServer(Net.MatchAction.of("first_me")));
                    button(g, VW / 2 + 6, 162, 100, 20, "They go first", true, mx, my, () -> Net.toServer(Net.MatchAction.of("first_them")));
                } else {
                    g.drawCenteredString(font, "Your opponent decides who goes first…", VW / 2, 160, 0xFFFFE9A0);
                }
                return;
            }
            if (redrawing()) {
                dim(g);
                g.drawCenteredString(font, "Your starting hand", VW / 2, 24, 0xFFFFE9A0);
                small(g, "Click up to " + num(me(), "redrawsLeft") + " card(s) to swap them for random cards from your deck.", VW / 2 - 150, 38, 300, 0xFFCCCCCC);
                drawOptions(g, list(me(), "hand"), mx, my, 56);
                button(g, VW / 2 - 60, 328, 120, 20, "Keep this hand", true, mx, my, () -> Net.toServer(Net.MatchAction.of("redraw_done")));
                return;
            }
            if (phase().equals("REDRAW")) {
                dim(g);
                g.drawCenteredString(font, "Waiting for your opponent to pick their hand…", VW / 2, 170, 0xFFFFE9A0);
                return;
            }
            Map<String, Object> ch = choice();
            if (ch != null) {
                dim(g);
                int count = num(ch, "count");
                g.drawCenteredString(font, choiceTitle(str(ch, "kind"), count), VW / 2, 24, 0xFFFFE9A0);
                drawOptions(g, list(ch, "options"), mx, my, 56);
                button(g, VW / 2 - 50, 328, 100, 20, count == 0 ? "OK" : "Confirm (" + picks.size() + "/" + count + ")", picks.size() == count, mx, my, () -> {
                    int[] arr = picks.stream().mapToInt(Integer::intValue).toArray();
                    Net.toServer(new Net.MatchAction("choose", -1, "", -1, arr));
                    picks.clear();
                });
            }
        } finally {
            g.pose().popPose();
        }
    }

    private void dim(GuiGraphics g) { g.fill(0, 0, VW, VH, 0xC0000000); }

    private void drawResult(GuiGraphics g, int mx, int my) {
        dim(g);
        String w = str(m(), "winner");
        int x = VW / 2 - 140, y = 90;
        panel(g, x, y, 280, 170);
        String title = w.equals("me") ? "Victory!" : w.equals("them") ? "Defeat" : "Draw";
        g.pose().pushPose();
        g.pose().translate(VW / 2f, y + 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawCenteredString(font, title, 0, 0, w.equals("me") ? 0xFFFFE080 : 0xFFE0E0E0);
        g.pose().popPose();
        @SuppressWarnings("unchecked") List<Object> mine = (List<Object>) me().get("rounds");
        @SuppressWarnings("unchecked") List<Object> theirs = (List<Object>) them().get("rounds");
        int ty = y + 38;
        text(g, "You", x + 120, ty, 0xFF9FFF9F);
        small(g, str(them(), "name"), x + 180, ty + 1, 90, 0xFFFF9F9F);
        if (mine != null && theirs != null) {
            for (int i = 0; i < Math.min(mine.size(), theirs.size()); i++) {
                ty += 12;
                int a = ((Double) mine.get(i)).intValue(), b = ((Double) theirs.get(i)).intValue();
                text(g, "Round " + (i + 1), x + 30, ty, 0xFFBBBBBB);
                text(g, String.valueOf(a), x + 120, ty, a > b ? 0xFFFFE080 : 0xFFFFFFFF);
                text(g, String.valueOf(b), x + 180, ty, b > a ? 0xFFFFE080 : 0xFFFFFFFF);
            }
        }
        wrapped(g, str(m(), "result"), x + 14, y + 112, 252, 0xFFFFFFFF);
        button(g, VW / 2 - 40, y + 142, 80, 20, "Close", true, mx, my, () -> {
            Net.toServer(Net.MatchAction.of("close"));
            ClientHooks.match = null;
            onClose();
        });
    }

    private static String choiceTitle(String kind, int count) {
        switch (kind) {
            case "MEDIC": return "Medic: pick a unit from your graveyard to play";
            case "LEADER_WEATHER": return "Pick a weather card to play";
            case "LEADER_OWN_GRAVE": return "Pick a card from your graveyard to take back";
            case "LEADER_ENEMY_GRAVE": return "Pick a card from your opponent's graveyard";
            case "DISCARD_TWO": return "Pick 2 cards to discard";
            case "PICK_FROM_DECK": return "Pick any card from your deck";
            case "PEEK": return "Three cards from your opponent's hand";
            default: return "Pick " + count;
        }
    }

    private void drawOptions(GuiGraphics g, List<Map<String, Object>> opts, int mx, int my, int top) {
        int cw = 52, ch = 74, gap = 6;
        int perRow = Math.min(10, opts.size());
        if (perRow == 0) return;
        int rows = (opts.size() + perRow - 1) / perRow;
        int y0 = top + Math.max(0, (320 - top - rows * (ch + gap)) / 2);
        for (int i = 0; i < opts.size(); i++) {
            int inRow = Math.min(perRow, opts.size() - (i / perRow) * perRow);
            int x0 = (VW - inRow * (cw + gap) + gap) / 2;
            int x = x0 + (i % perRow) * (cw + gap), y = y0 + (i / perRow) * (ch + gap);
            CardDef d = def(str(opts.get(i), "key"));
            if (d == null) continue;
            int uid = num(opts.get(i), "uid");
            boolean hov = in(mx, my, x, y, cw, ch);
            g.pose().pushPose();
            g.pose().translate(0, 0, (i % perRow) * CardArt.Z_STEP);
            CardArt.draw(g, d, x, y, cw, ch, Integer.MIN_VALUE, hov || picks.contains(uid), x + cw / 2f - mx, y + 15 - my);
            g.pose().popPose();
            hits.add(new Hit(x, y, cw, ch, "option", uid, true, null));
            if (hov) preview(d, Integer.MIN_VALUE);
        }
    }

    // ---------------------------------------------------------------- input

    private CardDef selectedDef() {
        if (selected < 0) return null;
        for (Map<String, Object> c : list(me(), "hand")) if (num(c, "uid") == selected) return def(str(c, "key"));
        return null;
    }

    private boolean rowTargetable(CardDef d, boolean mine, String row) {
        if (d == null) return false;
        if (d.isUnit()) {
            if (d.has("spy")) return !mine && row.equals(d.placement);
            if (d.isAgile()) return mine && !row.equals("siege");
            return mine && row.equals(d.placement);
        }
        if (d.has("horn") || d.has("mardroeme")) return mine && str(map(map(me(), "rows"), row), "special").isEmpty();
        return !d.has("decoy");
    }

    @Override
    protected boolean clicked(double mx, double my, int button) {
        if (m() == null || over()) return false;
        if (button == 1) { selected = -1; return true; }
        if (redrawing()) {
            for (Hit h : hits) if (h.kind.equals("option") && h.contains(mx, my)) {
                Net.toServer(new Net.MatchAction("redraw", h.uid, "", -1, new int[0]));
                return true;
            }
            return false;
        }
        Map<String, Object> ch = choice();
        if (ch != null) {
            int count = num(ch, "count");
            for (Hit h : hits) if (h.kind.equals("option") && h.contains(mx, my) && count > 0) {
                if (picks.contains(h.uid)) picks.remove((Integer) h.uid);
                else {
                    if (picks.size() >= count) picks.remove(0);
                    picks.add(h.uid);
                }
                return true;
            }
            return false;
        }
        if (!myTurn()) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            switch (h.kind) {
                case "hand":
                    if (h.uid == selected) playSelected(null);
                    else selected = h.uid;
                    return true;
                case "leader":
                    if (bool(me(), "leaderUsable")) Net.toServer(Net.MatchAction.of("leader"));
                    return true;
                case "board": {
                    CardDef sel = selectedDef();
                    if (sel != null && sel.has("decoy") && h.mine) {
                        Net.toServer(new Net.MatchAction("play", selected, "", h.uid, new int[0]));
                        selected = -1;
                        return true;
                    }
                    break;
                }
                case "row": {
                    CardDef sel = selectedDef();
                    if (sel != null && rowTargetable(sel, h.mine, h.row)) {
                        playSelected(h.row);
                        return true;
                    }
                    break;
                }
                default: break;
            }
        }
        return false;
    }

    private void playSelected(String row) {
        CardDef d = selectedDef();
        if (d == null || d.has("decoy")) return;
        if (row == null && (d.isAgile() || d.has("horn") || d.has("mardroeme"))) return;
        Net.toServer(new Net.MatchAction("play", selected, d.isUnit() && !d.isAgile() ? "" : (row == null ? "" : row), -1, new int[0]));
        selected = -1;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 32) { // Space: hold to pass
            if (spaceHeld == 0) spaceHeld = Util.getMillis();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == 32) { spaceHeld = 0; return true; }
        return super.keyReleased(key, scan, mods);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void onClose() {
        if (m() != null && !over()) {
            ClientHooks.matchHidden = true;
            if (minecraft != null && minecraft.player != null)
                minecraft.player.displayClientMessage(Component.literal("The match is still on. Press K to return."), true);
        }
        super.onClose();
    }
}
