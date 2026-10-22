package io.github.teemov.ecgwent;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** EC Gwent: a Gwent-style card game played against Easy NPCs. */
@Mod(EcGwent.MODID)
public class EcGwent {
    public static final String MODID = "ec_gwent";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EcGwent() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gwent")
                .then(Commands.literal("version").executes(ctx -> {
                    String version = ModList.get().getModContainerById(MODID)
                            .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
                    ctx.getSource().sendSuccess(() -> Component.literal("EC Gwent " + version), false);
                    return 1;
                })));
    }
}
