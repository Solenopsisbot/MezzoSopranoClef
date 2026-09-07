package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.api.ScreenNames;
import dev.mezzo.clef.bot.Hotbar;
import dev.mezzo.clef.bot.RecipeIndex;
import dev.mezzo.clef.mixin.client.BossBarHudAccessor;
import dev.mezzo.clef.mixin.client.InGameHudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

/**
 * Commands for driving <b>any</b> open UI — vanilla or modded. Container menus (chests, furnaces,
 * crafting tables, anvils, villager trades, machine GUIs, ...) all run on the server-synced
 * {@code AbstractContainerMenu}/{@code handleInventoryMouseClick} system, so a generic slot reader + clicker handles them
 * uniformly. Pure-widget screens (buttons / text fields) are driven via the generic widget path.
 * Also reads server-side UI (title, boss bars, scoreboard).
 */
public final class UiCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("container", "read the open container: slots, cursor, and villager trades", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            AbstractContainerMenu h = mc.player.containerMenu;
            JsonObject o = new JsonObject();
            o.addProperty("handler", h.getClass().getSimpleName());
            o.addProperty("syncId", h.containerId);
            o.addProperty("screen", ScreenNames.of(mc.screen));
            o.addProperty("screenClass", ScreenNames.rawOf(mc.screen));
            JsonArray slots = new JsonArray();
            for (Slot s : h.slots) {
                JsonObject js = new JsonObject();
                js.addProperty("slot", s.index);
                js.addProperty("item", item(s.getItem()));
                js.addProperty("count", s.getItem().getCount());
                slots.add(js);
            }
            o.add("slots", slots);
            o.addProperty("cursor", item(h.getCarried()));
            if (h instanceof MerchantMenu m) {
                JsonArray trades = new JsonArray();
                MerchantOffers offers = m.getOffers();
                for (int i = 0; i < offers.size(); i++) {
                    MerchantOffer t = offers.get(i);
                    JsonObject jt = new JsonObject();
                    jt.addProperty("index", i);
                    jt.addProperty("buyA", item(t.getCostA()));
                    jt.addProperty("buyB", item(t.getCostB()));
                    jt.addProperty("sell", item(t.getResult()));
                    jt.addProperty("disabled", t.isOutOfStock());
                    jt.addProperty("uses", t.getUses());
                    jt.addProperty("maxUses", t.getMaxUses());
                    trades.add(jt);
                }
                o.add("trades", trades);
            }
            return o;
        }));

        d.register("clickSlot", "click a container slot {slot, button?=0, mode?=pickup|quickMove|swap|clone|throw|pickupAll}",
                ctx -> {
                    int slot = ctx.requireInt("slot");
                    int button = ctx.i("button", 0);
                    ClickType mode = slotAction(ctx.str("mode", "pickup"));
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();
                        AbstractContainerMenu h = mc.player.containerMenu;
                        mc.gameMode.handleInventoryMouseClick(h.containerId, slot, button, mode, mc.player);
                        JsonObject o = new JsonObject();
                        o.addProperty("clicked", slot);
                        o.addProperty("mode", mode.name());
                        o.addProperty("cursor", item(h.getCarried()));
                        return o;
                    });
                });

        d.register("closeScreen", "close the open container/screen", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.closeContainer();
            JsonObject o = new JsonObject();
            o.addProperty("closed", true);
            return o;
        }));

        d.register("selectTrade", "select a villager trade by index (open the villager first) {index}", ctx -> {
            int index = ctx.requireInt("index");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.getConnection() == null) throw ApiException.notInWorld();
                if (!(mc.player.containerMenu instanceof MerchantMenu m)) {
                    throw new IllegalStateException("no villager trade screen open");
                }
                m.setSelectionHint(index);
                mc.getConnection().send(new ServerboundSelectTradePacket(index));
                JsonObject o = new JsonObject();
                o.addProperty("selected", index);
                return o;
            });
        });

        d.register("screen", "list clickable widgets on the current screen (for modded button/field GUIs)", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            JsonObject o = new JsonObject();
            Screen sc = mc.screen;
            o.addProperty("screen", ScreenNames.of(sc));
            o.addProperty("screenClass", ScreenNames.rawOf(sc));
            JsonArray ws = new JsonArray();
            if (sc != null) {
                int i = 0;
                for (GuiEventListener e : sc.children()) {
                    if (e instanceof AbstractWidget w) {
                        JsonObject jw = new JsonObject();
                        jw.addProperty("index", i);
                        jw.addProperty("type", w.getClass().getSimpleName());
                        jw.addProperty("text", txt(w.getMessage()));
                        jw.addProperty("x", w.getX());
                        jw.addProperty("y", w.getY());
                        jw.addProperty("active", w.active);
                        jw.addProperty("visible", w.visible);
                        ws.add(jw);
                    }
                    i++;
                }
            }
            o.add("widgets", ws);
            return o;
        }));

        d.register("clickButton", "click a screen widget by its index (from `screen`) {index}", ctx -> {
            int index = ctx.requireInt("index");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screen sc = mc.screen;
                if (sc == null) throw ApiException.notFound("no screen open");
                GuiEventListener e = nth(sc, index);
                if (!(e instanceof AbstractWidget w)) throw ApiException.badArgs("widget " + index + " is not clickable");
                w.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
                JsonObject o = new JsonObject();
                o.addProperty("clicked", index);
                o.addProperty("text", txt(w.getMessage()));
                return o;
            });
        });

        d.register("setText", "type into a text-field widget {index, text}", ctx -> {
            int index = ctx.requireInt("index");
            String text = ctx.str("text", "");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screen sc = mc.screen;
                if (sc == null) throw ApiException.notFound("no screen open");
                GuiEventListener e = nth(sc, index);
                if (!(e instanceof EditBox tf)) throw ApiException.badArgs("widget " + index + " is not a text field");
                tf.setValue(text);
                JsonObject o = new JsonObject();
                o.addProperty("set", text);
                return o;
            });
        });

        d.register("serverui", "read server-side UI: title/subtitle/action-bar, boss bars, scoreboard sidebar", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            JsonObject o = new JsonObject();
            try {
                InGameHudAccessor hud = (InGameHudAccessor) (Object) mc.gui;
                JsonObject title = new JsonObject();
                title.addProperty("title", txt(hud.clef$getTitle()));
                title.addProperty("subtitle", txt(hud.clef$getSubtitle()));
                title.addProperty("actionBar", txt(hud.clef$getOverlayMessage()));
                o.add("title", title);
            } catch (Throwable ignored) {}
            try {
                BossBarHudAccessor bh = (BossBarHudAccessor) (Object) mc.gui.getBossOverlay();
                JsonArray bars = new JsonArray();
                bh.clef$getBossBars().values().forEach(b -> {
                    JsonObject jb = new JsonObject();
                    jb.addProperty("name", txt(b.getName()));
                    jb.addProperty("percent", b.getProgress());
                    bars.add(jb);
                });
                o.add("bossBars", bars);
            } catch (Throwable ignored) {}
            try {
                if (mc.level != null) {
                    JsonArray objectives = new JsonArray();
                    for (Objective obj : mc.level.getScoreboard().getObjectives()) {
                        JsonObject jo = new JsonObject();
                        jo.addProperty("name", obj.getName());
                        jo.addProperty("display", txt(obj.getDisplayName()));
                        objectives.add(jo);
                    }
                    o.add("scoreboards", objectives);
                    Objective sidebar = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
                    o.addProperty("sidebar", sidebar != null ? txt(sidebar.getDisplayName()) : "");
                }
            } catch (Throwable ignored) {}
            return o;
        }));

        // ---- higher-level inventory helpers (Baritone has none of this) ----

        d.register("findItem", "find inventory slots holding an item {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                var inv = mc.player.getInventory();
                JsonArray found = new JsonArray();
                int total = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (idMatches(inv.getItem(i), q)) {
                        JsonObject j = new JsonObject();
                        j.addProperty("slot", i);
                        j.addProperty("count", inv.getItem(i).getCount());
                        found.add(j);
                        total += inv.getItem(i).getCount();
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("total", total);
                o.add("slots", found);
                return o;
            });
        });

        d.register("equip",
                "wear or hold an item from the inventory — already in its equipment slot is a no-op "
                        + "({changed:false}), not an undress {item}",
                ctx -> {
                    String q = ctx.requireStr("item");
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();

                        // Idempotence first. `equip` is a shift-click, the armour and off-hand slots
                        // are part of the player inventory, and a shift-click on a worn piece
                        // quick-moves it back OFF. So a mind that defensively re-equipped its armour
                        // every turn undressed itself and fought with the gear in its bag.
                        EquipmentSlot worn = wornSlotFor(mc.player, q);
                        if (worn != null) {
                            JsonObject o = new JsonObject();
                            o.addProperty("equipped", q);
                            o.addProperty("changed", false);
                            o.addProperty("slot", worn.getName());
                            return o;
                        }

                        AbstractContainerMenu h = mc.player.containerMenu;
                        Inventory inv = mc.player.getInventory();
                        for (Slot s : h.slots) {
                            if (s.container != inv || !idMatches(s.getItem(), q)) continue;
                            // Never source from an equipment slot either: quick-moving out of one is
                            // unequipping, whatever the caller meant by "equip".
                            if (s.index >= MAIN_INVENTORY_SLOTS) continue;
                            mc.gameMode.handleInventoryMouseClick(h.containerId, s.index, 0, ClickType.QUICK_MOVE, mc.player);
                            JsonObject o = new JsonObject();
                            o.addProperty("equipped", q);
                            o.addProperty("changed", true);
                            o.addProperty("fromSlot", s.index);
                            EquipmentSlot now = wornSlotFor(mc.player, q);
                            if (now != null) o.addProperty("slot", now.getName());
                            return o;
                        }
                        throw ApiException.notFound("no '" + q + "' in inventory");
                    });
                });

        d.register("moveToHotbar",
                "put an item on the hotbar and select it, swapping it in if needed {item, slot?}",
                ctx -> {
                    String q = ctx.requireStr("item");
                    Integer slot = ctx.has("slot") ? ctx.i("slot", 0) : null;
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null) throw ApiException.notInWorld();
                        Hotbar.Selection selection = Hotbar.select(mc, RecipeIndex.resolveItem(q), slot);
                        JsonObject o = new JsonObject();
                        o.addProperty("slot", selection.slot());
                        o.addProperty("moved", selection.moved());
                        o.addProperty("fromSlot", selection.fromSlot());
                        return o;
                    });
                });

        d.register("deposit", "shift-click matching items from inventory INTO the open container {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> transfer(q, true));
        });

        d.register("withdraw", "shift-click matching items from the open container INTO inventory {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> transfer(q, false));
        });

        d.register("dropStack", "throw whole stacks of an item {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();
                AbstractContainerMenu h = mc.player.containerMenu;
                int dropped = 0;
                for (Slot s : h.slots) {
                    if (idMatches(s.getItem(), q)) {
                        mc.gameMode.handleInventoryMouseClick(h.containerId, s.index, 1, ClickType.THROW, mc.player);
                        dropped++;
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("dropped", dropped);
                return o;
            });
        });
    }

    /** shift-clicks (QUICK_MOVE) matching items between the player inventory and the open container. */
    private static JsonObject transfer(String q, boolean intoContainer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();
        AbstractContainerMenu h = mc.player.containerMenu;
        Object playerInv = mc.player.getInventory();
        int moved = 0;
        for (Slot s : h.slots) {
            boolean isPlayerSide = s.container == playerInv;
            if (isPlayerSide == intoContainer && idMatches(s.getItem(), q)) {
                mc.gameMode.handleInventoryMouseClick(h.containerId, s.index, 0, ClickType.QUICK_MOVE, mc.player);
                moved++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty(intoContainer ? "deposited" : "withdrew", moved);
        return o;
    }

    /**
     * Player inventory indices 0-35 are the main storage and hotbar; armour and the off-hand sit
     * above them. That split has held across every release this matrix builds, which is why the
     * check is a number rather than {@code Inventory.EQUIPMENT_SLOT_MAPPING} — that constant only
     * exists from 1.21.8, and {@code common/} compiles back to 1.14.4.
     */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    /** The slots {@link #wornSlotFor} considers "worn". Every one exists back to 1.14.4. */
    private static final EquipmentSlot[] WEARABLE = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND };

    /**
     * The equipment slot the player is already wearing {@code query} in, or null.
     *
     * <p>Covers everything with a well-defined home — armour, elytra, shield, carved pumpkin — plus
     * the off-hand. Deliberately not the main hand: shift-clicking an ordinary item has never meant
     * "hold it" ({@code moveToHotbar} is that), so the old behaviour is unchanged for anything that
     * isn't worn.</p>
     */
    private static EquipmentSlot wornSlotFor(Player player, String query) {
        for (EquipmentSlot slot : WEARABLE) {
            if (idMatches(player.getItemBySlot(slot), query)) return slot;
        }
        return null;
    }

    private static boolean idMatches(ItemStack st, String query) {
        if (st == null || st.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        String q = query.trim().toLowerCase();
        return id.equals(q) || id.endsWith(":" + q);
    }

    // ---- helpers --------------------------------------------------------------------

    private static ClickType slotAction(String mode) {
        return switch (mode.trim().toLowerCase()) {
            case "quickmove", "shift", "quick_move" -> ClickType.QUICK_MOVE;
            case "swap" -> ClickType.SWAP;
            case "clone", "middle" -> ClickType.CLONE;
            case "throw", "drop" -> ClickType.THROW;
            case "pickupall", "double" -> ClickType.PICKUP_ALL;
            case "quickcraft" -> ClickType.QUICK_CRAFT;
            default -> throw ApiException.badArgs("mode must be pickup, quickMove, swap, clone, throw, pickupAll, or quickCraft");
        };
    }

    private static GuiEventListener nth(Screen sc, int index) {
        java.util.List<? extends GuiEventListener> kids = sc.children();
        if (index < 0 || index >= kids.size()) throw ApiException.notFound("no widget at index " + index);
        return kids.get(index);
    }

    private static String item(ItemStack st) {
        if (st == null || st.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(st.getItem()) + " x" + st.getCount();
    }

    private static String txt(Component t) {
        return t == null ? "" : t.getString();
    }

    private UiCommands() {}
}
