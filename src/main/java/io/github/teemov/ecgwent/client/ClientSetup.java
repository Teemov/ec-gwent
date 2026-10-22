package io.github.teemov.ecgwent.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Key binding for the card book. */
public final class ClientSetup {
    public static final KeyMapping BOOK_KEY = new KeyMapping("key.ec_gwent.book", InputConstants.KEY_K, "key.categories.ec_gwent");

    private ClientSetup() {}

    @Mod.EventBusSubscriber(modid = EcGwent.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) { e.register(BOOK_KEY); }
    }

    @Mod.EventBusSubscriber(modid = EcGwent.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeEvents {
        @SubscribeEvent
        public static void loggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) {
            ClientHooks.match = null;
            ClientHooks.matchHidden = false;
        }

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            while (BOOK_KEY.consumeClick()) {
                if (Minecraft.getInstance().screen == null && !ClientHooks.openMatchIfRunning()) Net.toServer(new Net.RequestBook());
            }
        }
    }
}
