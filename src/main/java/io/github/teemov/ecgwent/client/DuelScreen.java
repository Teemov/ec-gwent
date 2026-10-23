package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Challenging a player, or answering their challenge. Each player picks their own bet:
 * the higher bet goes first, equal bets toss a coin, and the winner takes both bets.
 */
public final class DuelScreen extends CanvasScreen {
    private static final int MAX_BET = 64;
    private final UUID other;
    private final String name;
    /** The challenger's bet when answering; -1 when this player is the one challenging. */
    private final int theirBet;
    private int myBet;
    private boolean answered;

    public DuelScreen(UUID other, String name, int theirBet) {
        super("Gwent");
        this.other = other;
        this.name = name;
        this.theirBet = theirBet;
        this.myBet = Math.max(0, Math.min(theirBet, emeralds()));
    }

    private boolean challenging() { return theirBet < 0; }

    private static int emeralds() {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getInventory().countItem(Items.EMERALD);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        int x = VW / 2 - 140, y = 90;
        panel(g, x, y, 280, 172);
        int px = x + 10, py = y + 10;
        g.fill(px - 1, py - 1, px + 51, py + 71, 0xFF6A5A30);
        CardArt.scissor(g, px, py, px + 50, py + 70);
        g.fill(px, py, px + 50, py + 70, 0xFF1C1818);
        CardArt.drawSubject(g, "player:" + other, null, px, py, 50, 70, px + 25 - mx, py + 20 - my);
        g.disableScissor();
        text(g, name, x + 70, y + 12, 0xFFFFE9A0);
        if (challenging()) {
            wrapped(g, "Challenge " + name + " to a game of Gwent.", x + 70, y + 26, 200, 0xFFDDDDDD);
        } else {
            wrapped(g, "challenges you to a game of Gwent.", x + 70, y + 26, 200, 0xFFDDDDDD);
            wrapped(g, theirBet > 0 ? name + " bets " + theirBet + " emerald" + (theirBet == 1 ? "" : "s") + "." : name + " bets nothing.",
                    x + 70, y + 40, 200, theirBet > 0 ? 0xFF80FF80 : 0xFFAAAAAA);
        }
        wrapped(g, "The higher bet goes first; equal bets toss a coin. The winner takes both bets.", x + 70, y + 54, 200, 0xFFAAAAAA);

        // Bet picker
        int by = y + 92, have = emeralds();
        text(g, "Your bet", x + 14, by + 5, 0xFFE0C890);
        button(g, x + 70, by, 22, 18, "-5", myBet > 0, mx, my, () -> myBet = Math.max(0, myBet - 5));
        button(g, x + 94, by, 18, 18, "-", myBet > 0, mx, my, () -> myBet--);
        g.fill(x + 116, by, x + 160, by + 18, 0xFF1A120A);
        String b = String.valueOf(myBet);
        text(g, b, x + 138 - font.width(b) / 2, by + 5, 0xFF80FF80);
        button(g, x + 164, by, 18, 18, "+", myBet < Math.min(MAX_BET, have), mx, my, () -> myBet++);
        button(g, x + 184, by, 22, 18, "+5", myBet < Math.min(MAX_BET, have), mx, my, () -> myBet = Math.min(Math.min(MAX_BET, have), myBet + 5));
        small(g, "You have " + have + " emerald" + (have == 1 ? "" : "s"), x + 212, by + 6, 64, 0xFF999999);
        String who = challenging() ? "" : myBet > theirBet ? "You will go first." : myBet < theirBet ? name + " will go first." : "A coin decides who goes first.";
        if (!who.isEmpty()) small(g, who, x + 70, by + 22, 200, 0xFFFFE080);

        if (challenging()) {
            button(g, x + 20, y + 140, 120, 20, "Challenge", true, mx, my, () -> {
                answered = true;
                Net.toServer(new Net.DuelInvite(other, myBet));
                onClose();
            });
            button(g, x + 146, y + 140, 114, 20, "Cancel", true, mx, my, this::onClose);
        } else {
            button(g, x + 20, y + 140, 120, 20, "Accept", true, mx, my, () -> answer(true));
            button(g, x + 146, y + 140, 114, 20, "Decline", true, mx, my, () -> answer(false));
        }
    }

    private void answer(boolean accept) {
        answered = true;
        Net.toServer(new Net.DuelAnswer(other, accept, myBet));
        onClose();
    }

    @Override
    public void onClose() {
        if (!answered && !challenging()) { answered = true; Net.toServer(new Net.DuelAnswer(other, false, 0)); }
        super.onClose();
    }
}
