package io.github.teemov.ecgwent.net;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.client.ClientHooks;
import io.github.teemov.ecgwent.server.Matches;
import io.github.teemov.ecgwent.server.ServerHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** All packets between client and server. */
public final class Net {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EcGwent.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private Net() {}

    static void writeString(FriendlyByteBuf b, String s) { b.writeByteArray(s.getBytes(StandardCharsets.UTF_8)); }
    static String readString(FriendlyByteBuf b) { return new String(b.readByteArray(), StandardCharsets.UTF_8); }

    public static void register() {
        int id = 0;
        // ---------------------------------------------------------------- client -> server
        CHANNEL.messageBuilder(RequestBook.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {}).decoder(b -> new RequestBook())
                .consumerMainThread((m, ctx) -> ServerHooks.sendBook(ctx.get().getSender(), true)).add();
        CHANNEL.messageBuilder(PickStarter.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> b.writeUtf(m.faction)).decoder(b -> new PickStarter(b.readUtf(64)))
                .consumerMainThread((m, ctx) -> ServerHooks.pickStarter(ctx.get().getSender(), m.faction)).add();
        CHANNEL.messageBuilder(SaveDeck.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {
                    b.writeUtf(m.faction);
                    b.writeUtf(m.leader);
                    b.writeVarInt(m.cards.size());
                    for (String c : m.cards) b.writeUtf(c);
                })
                .decoder(b -> {
                    String f = b.readUtf(64), l = b.readUtf(128);
                    int n = Math.min(b.readVarInt(), 200);
                    List<String> cards = new ArrayList<>();
                    for (int i = 0; i < n; i++) cards.add(b.readUtf(128));
                    return new SaveDeck(f, l, cards);
                })
                .consumerMainThread((m, ctx) -> ServerHooks.saveDeck(ctx.get().getSender(), m.faction, m.leader, m.cards)).add();
        CHANNEL.messageBuilder(MatchAction.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {
                    b.writeUtf(m.type);
                    b.writeVarInt(m.uid);
                    b.writeUtf(m.row);
                    b.writeVarInt(m.target);
                    b.writeVarIntArray(m.picks);
                })
                .decoder(b -> new MatchAction(b.readUtf(32), b.readVarInt(), b.readUtf(16), b.readVarInt(), b.readVarIntArray(16)))
                .consumerMainThread((m, ctx) -> Matches.action(ctx.get().getSender(), m)).add();
        CHANNEL.messageBuilder(AcceptVillager.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> b.writeVarInt(m.entityId)).decoder(b -> new AcceptVillager(b.readVarInt()))
                .consumerMainThread((m, ctx) -> Matches.acceptVillager(ctx.get().getSender(), m.entityId)).add();
        CHANNEL.messageBuilder(DuelAnswer.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> { b.writeUUID(m.from); b.writeBoolean(m.accept); b.writeVarInt(m.bet); })
                .decoder(b -> new DuelAnswer(b.readUUID(), b.readBoolean(), b.readVarInt()))
                .consumerMainThread((m, ctx) -> Matches.answerDuel(ctx.get().getSender(), m.from, m.accept, m.bet)).add();
        CHANNEL.messageBuilder(DuelInvite.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> { b.writeUUID(m.target); b.writeVarInt(m.bet); })
                .decoder(b -> new DuelInvite(b.readUUID(), b.readVarInt()))
                .consumerMainThread((m, ctx) -> {
                    net.minecraft.server.level.ServerPlayer from = ctx.get().getSender();
                    net.minecraft.server.level.ServerPlayer to = from == null ? null : from.server.getPlayerList().getPlayer(m.target);
                    if (from == null || to == null) return;
                    String err = Matches.inviteDuel(from, to, m.bet);
                    if (err != null) from.displayClientMessage(net.minecraft.network.chat.Component.literal(err)
                            .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
                }).add();
        // ---------------------------------------------------------------- server -> client
        CHANNEL.messageBuilder(SyncCards.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> {
                    b.writeVarInt(m.files.size());
                    for (String f : m.files) writeString(b, f);
                })
                .decoder(b -> {
                    int n = b.readVarInt();
                    List<String> files = new ArrayList<>();
                    for (int i = 0; i < n; i++) files.add(readString(b));
                    return new SyncCards(files);
                })
                .consumerMainThread((m, ctx) -> client(() -> () -> ClientHooks.syncCards(m.files))).add();
        CHANNEL.messageBuilder(BookData.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> { b.writeNbt(m.data); b.writeBoolean(m.open); })
                .decoder(b -> new BookData(b.readNbt(), b.readBoolean()))
                .consumerMainThread((m, ctx) -> client(() -> () -> ClientHooks.bookData(m.data, m.open))).add();
        CHANNEL.messageBuilder(MatchView.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> writeString(b, m.json)).decoder(b -> new MatchView(readString(b)))
                .consumerMainThread((m, ctx) -> client(() -> () -> ClientHooks.matchView(m.json))).add();
        CHANNEL.messageBuilder(DuelOffer.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> { b.writeUUID(m.from); b.writeUtf(m.name); b.writeVarInt(m.bet); })
                .decoder(b -> new DuelOffer(b.readUUID(), b.readUtf(64), b.readVarInt()))
                .consumerMainThread((m, ctx) -> client(() -> () -> ClientHooks.duelOffer(m.from, m.name, m.bet))).add();
        CHANNEL.messageBuilder(AccessState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> b.writeBoolean(m.unlocked)).decoder(b -> new AccessState(b.readBoolean()))
                .consumerMainThread((m, ctx) -> io.github.teemov.ecgwent.client.ClientAccess.set(m.unlocked)).add();
        CHANNEL.messageBuilder(VillagerOffer.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> { b.writeVarInt(m.entityId); b.writeUtf(m.name); b.writeVarInt(m.level); })
                .decoder(b -> new VillagerOffer(b.readVarInt(), b.readUtf(128), b.readVarInt()))
                .consumerMainThread((m, ctx) -> client(() -> () -> ClientHooks.villagerOffer(m.entityId, m.name, m.level))).add();
    }

    private static void client(Supplier<Runnable> r) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, r);
    }

    public static void toPlayer(ServerPlayer p, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg);
    }

    public static void toServer(Object msg) { CHANNEL.sendToServer(msg); }

    // ---------------------------------------------------------------- messages
    public static final class RequestBook {}

    public static final class PickStarter {
        final String faction;
        public PickStarter(String faction) { this.faction = faction; }
    }

    public static final class SaveDeck {
        final String faction, leader;
        final List<String> cards;
        public SaveDeck(String faction, String leader, List<String> cards) { this.faction = faction; this.leader = leader; this.cards = cards; }
    }

    /** type: play, leader, pass, redraw, redraw_done, choose, first_me, first_them, forfeit, close, reopen */
    public static final class MatchAction {
        public final String type, row;
        public final int uid, target;
        public final int[] picks;
        public MatchAction(String type, int uid, String row, int target, int[] picks) {
            this.type = type; this.uid = uid; this.row = row == null ? "" : row; this.target = target; this.picks = picks;
        }
        public static MatchAction of(String type) { return new MatchAction(type, -1, "", -1, new int[0]); }
    }

    public static final class AcceptVillager {
        final int entityId;
        public AcceptVillager(int entityId) { this.entityId = entityId; }
    }

    public static final class SyncCards {
        final List<String> files;
        public SyncCards(List<String> files) { this.files = files; }
    }

    public static final class BookData {
        final CompoundTag data;
        final boolean open;
        public BookData(CompoundTag data, boolean open) { this.data = data; this.open = open; }
    }

    public static final class MatchView {
        final String json;
        public MatchView(String json) { this.json = json; }
    }

    public static final class DuelAnswer {
        final java.util.UUID from;
        final boolean accept;
        final int bet;
        public DuelAnswer(java.util.UUID from, boolean accept, int bet) { this.from = from; this.accept = accept; this.bet = bet; }
    }

    public static final class DuelInvite {
        final java.util.UUID target;
        final int bet;
        public DuelInvite(java.util.UUID target, int bet) { this.target = target; this.bet = bet; }
    }

    public static final class DuelOffer {
        final java.util.UUID from;
        final String name;
        final int bet;
        public DuelOffer(java.util.UUID from, String name, int bet) { this.from = from; this.name = name; this.bet = bet; }
    }

    public static final class AccessState {
        final boolean unlocked;
        public AccessState(boolean unlocked) { this.unlocked = unlocked; }
    }

    public static final class VillagerOffer {
        final int entityId, level;
        final String name;
        public VillagerOffer(int entityId, String name, int level) { this.entityId = entityId; this.name = name; this.level = level; }
    }

    @SuppressWarnings("unused")
    private static NetworkEvent.Context unused;
}
