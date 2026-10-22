package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;

/** "Play Gwent with this villager for 2 emeralds?" */
public final class VillagerScreen extends CanvasScreen {
    private static final String[] LEVELS = {"", "Novice", "Apprentice", "Journeyman", "Expert", "Master"};
    private final int entityId, level;
    private final String name;

    public VillagerScreen(int entityId, String name, int level) {
        super("Gwent");
        this.entityId = entityId;
        this.name = name;
        this.level = level;
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        int x = VW / 2 - 130, y = 110;
        panel(g, x, y, 260, 130);
        int px = x + 10, py = y + 10;
        CardArt.scissor(g, px, py, px + 50, py + 70);
        CardArt.drawSubject(g, "entity:" + entityId + "|minecraft:villager", null, px, py, 50, 70, px + 25 - mx, py + 20 - my);
        g.disableScissor();
        String lvl = level >= 1 && level < LEVELS.length ? LEVELS[level] + " " : "";
        text(g, lvl + name, x + 70, y + 12, 0xFFFFE9A0);
        wrapped(g, "wants to play Gwent for 2 emeralds.", x + 70, y + 26, 180, 0xFFDDDDDD);
        wrapped(g, "Win: your 2 emeralds back plus a prize. Lose: the villager keeps them. "
                + "Experienced villagers play stronger decks.", x + 70, y + 42, 180, 0xFFAAAAAA);
        button(g, x + 20, y + 98, 110, 20, "Play", true, mx, my, () -> {
            Net.toServer(new Net.AcceptVillager(entityId));
            onClose();
        });
        button(g, x + 136, y + 98, 104, 20, "Not now", true, mx, my, this::onClose);
    }
}
