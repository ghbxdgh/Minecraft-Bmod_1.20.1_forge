package com.jinya.autocombo;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.IForgeRegistry;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * SlashBlade:Resharped combo driver, executed on the LOGICAL SERVER.
 *
 * WHY SERVER SIDE
 * The combo state machine lives in the blade capability and is stepped by the
 * server. On the client, getElapsedTime() comes from a synced lastActionTime
 * that lags by one to several ticks, so both resolvCurrentComboState() and
 * getNext() give wrong answers there: they claim the input window is open
 * before the server agrees, the click gets dropped, and the state falls into
 * a _end branch (a2_end -> combo_c, b6 -> combo_b_end).
 *
 * On the server the same two calls are exact, so this version needs no timing
 * table at all. It simply asks "can this state take a click yet" every tick
 * and clicks when the answer is yes. That makes it immune to walking, lag and
 * frame drops.
 *
 * Works because the player is hosting: on LAN / singleplayer the integrated
 * server runs inside the same JVM, so we can read the client key state and
 * drive the ServerPlayer directly. Dedicated servers are skipped.
 */
@Mod(AutoCombo.MODID)
public class AutoCombo {

    public static final String MODID = "autocombo";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Default binding: GLFW mouse button 3 == "Mouse 4". Rebindable in Options. */
    public static final KeyMapping TRIGGER = new KeyMapping(
            "key.autocombo.trigger",
            InputConstants.Type.MOUSE,
            3,
            "key.categories.misc");

    /** States that must be allowed to time out, never clicked. */
    private static final Set<String> SKIP = Set.of(
            "combo_a3",        // normal branch is a4 -> none, a dead end
            "combo_a4",
            "combo_a4_ex",
            "combo_a5ex",
            "combo_a4_end",
            "combo_a4_ex_end",
            "combo_a5ex_end",
            "combo_b7");       // chain end, clicking only restarts a1

    // shared between the client tick (reads the key) and the server tick
    private static UUID localPlayerId;
    private static boolean keyDown;
    private static String lastState = "";
    private static long lastClickTick = -100;

    public AutoCombo() {
        LOGGER.info("[autocombo] constructed");
    }

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TRIGGER);
        }
    }

    /** Client tick: capture who we are and whether the key is held. */
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientForgeBus {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                localPlayerId = null;
                keyDown = false;
                return;
            }
            localPlayerId = mc.player.getUUID();
            keyDown = mc.screen == null && TRIGGER.isDown();
        }
    }

    /** Server tick: authoritative state machine, authoritative timing. */
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ServerForgeBus {
        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            AutoCombo.serverTick(event);
        }
    }

    private static void serverTick(TickEvent.ServerTickEvent event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null || server.isDedicatedServer()) return;
            if (!keyDown || localPlayerId == null) return;

            ServerPlayer player = server.getPlayerList().getPlayer(localPlayerId);
            if (player == null || !player.isAlive() || player.isSpectator()) return;
            if (player.isUsingItem()) return;

            ItemStack held = player.getMainHandItem();
            if (!(held.getItem() instanceof ItemSlashBlade)) return;

            ISlashBladeState state = held.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
            if (state == null) return;

            IForgeRegistry<ComboState> registry = ComboStateRegistry.REGISTRY.get();
            if (registry == null) return;

            ResourceLocation current = state.resolvCurrentComboState(player);
            if (current == null) return;

            String name = current.getPath();
            if (!name.equals(lastState)) {
                LOGGER.info("[autocombo] state -> {}", name);
                lastState = name;
            }

            long now = server.getTickCount();
            if (now - lastClickTick < 1) return;

            if (name.equals("none") || name.equals("standby")) {
                click(player, held, now, name);
                return;
            }

            if (SKIP.contains(name)) return;

            ComboState combo = registry.getValue(current);
            if (combo == null) return;

            ResourceLocation next = combo.getNext(player);
            if (next != null && !next.equals(current)) {
                click(player, held, now, name);
            }
        } catch (Throwable t) {
            LOGGER.error("[autocombo] server tick failed", t);
        }
    }

    private static void click(ServerPlayer player, ItemStack held, long now, String from) {
        player.gameMode.useItem(player, player.serverLevel(), held, InteractionHand.MAIN_HAND);
        lastClickTick = now;
        LOGGER.info("[autocombo] click from {} (tick {})", from, now);
    }
}
