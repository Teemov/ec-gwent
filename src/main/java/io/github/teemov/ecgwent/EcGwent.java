package io.github.teemov.ecgwent;

import com.mojang.logging.LogUtils;
import io.github.teemov.ecgwent.net.Net;
import io.github.teemov.ecgwent.server.Collection;
import io.github.teemov.ecgwent.server.GwentCommands;
import io.github.teemov.ecgwent.server.GwentData;
import io.github.teemov.ecgwent.server.Matches;
import io.github.teemov.ecgwent.server.ServerHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** EC Gwent: a Gwent-style card game played against Easy NPCs and villagers. */
@Mod(EcGwent.MODID)
public class EcGwent {
    public static final String MODID = "ec_gwent";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EcGwent() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(FMLCommonSetupEvent e) {
        e.enqueueWork(Net::register);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        GwentCommands.register(e.getDispatcher());
    }

    @SubscribeEvent
    public void onReloadListeners(AddReloadListenerEvent e) {
        e.addListener(new GwentData.CardsListener());
        e.addListener(new GwentData.OpponentsListener());
    }

    /** Sends the card list on login and after /reload. */
    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent e) {
        if (e.getPlayer() != null) ServerHooks.syncCards(e.getPlayer());
        else for (ServerPlayer p : e.getPlayerList().getPlayers()) ServerHooks.syncCards(p);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) Matches.tick(e.getServer());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Matches.logout(p);
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone e) {
        Collection.copy(e.getOriginal(), e.getEntity());
    }

    /** Nobody in a running match can be hurt, so a creeper or a stray arrow can't end it. */
    @SubscribeEvent
    public void onAttack(LivingAttackEvent e) {
        if (e.getEntity().level().isClientSide || e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        if (Matches.isProtected(e.getEntity())) e.setCanceled(true);
    }

    /** Sneak + right-click another player: pick a bet and challenge them (or /gwent duel <player> [bet]). */
    @SubscribeEvent
    public void onInteractPlayer(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getTarget() instanceof net.minecraft.world.entity.player.Player target)
                || !e.getEntity().isShiftKeyDown()) return;
        if (e.getLevel().isClientSide) {
            if (!io.github.teemov.ecgwent.client.ClientAccess.unlocked()) return;
            java.util.UUID id = target.getUUID();
            String name = target.getName().getString();
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> io.github.teemov.ecgwent.client.ClientHooks.openChallenge(id, name));
        } else if (!io.github.teemov.ecgwent.server.Access.allowed((ServerPlayer) e.getEntity())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** Sneak + right-click a villager to challenge it. */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getTarget() instanceof Villager v) || v.isBaby()
                || !e.getEntity().isShiftKeyDown()) return;
        if (e.getEntity() instanceof ServerPlayer sp && !io.github.teemov.ecgwent.server.Access.allowed(sp)) return;
        if (e.getLevel().isClientSide && !io.github.teemov.ecgwent.client.ClientAccess.unlocked()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getEntity() instanceof ServerPlayer p) Matches.offerVillager(p, v);
    }
}
