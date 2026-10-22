package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Play Gwent with this villager for 2 emeralds?" */
public final class VillagerScreen extends Screen {
    private static final String[] LEVELS = {"", "Novice", "Apprentice", "Journeyman", "Expert", "Master"};
    private final int entityId, level;
    private final String name;

    public VillagerScreen(int entityId, String name, int level) {
        super(Component.literal("Gwent"));
        this.entityId = entityId;
        this.name = name;
        this.level = level;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Play (2 emeralds)"), b -> {
            Net.toServer(new Net.AcceptVillager(entityId));
            onClose();
        }).bounds(width / 2 - 105, height / 2 + 10, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Not now"), b -> onClose()).bounds(width / 2 + 5, height / 2 + 10, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        String lvl = level >= 1 && level < LEVELS.length ? LEVELS[level] + " " : "";
        g.drawCenteredString(font, lvl + name + " wants to play Gwent.", width / 2, height / 2 - 30, 0xFFFFE9A0);
        g.drawCenteredString(font, "Win: your 2 emeralds back plus a prize. Lose: the villager keeps them.", width / 2, height / 2 - 16, 0xFFDDDDDD);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
