package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.mixin.client.BossBarHudAccessor;
import dev.mezzo.clef.mixin.client.InGameHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * Commands for driving <b>any</b> open UI — vanilla or modded. Container menus (chests, furnaces,
 * crafting tables, anvils, villager trades, machine GUIs, ...) all run on the server-synced
 * {@code ScreenHandler}/{@code clickSlot} system, so a generic slot reader + clicker handles them
 * uniformly. Pure-widget screens (buttons / text fields) are driven via the generic widget path.
 * Also reads server-side UI (title, boss bars, scoreboard).
 */
public final class UiCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("container", "read the open container: slots, cursor, and villager trades", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) throw new IllegalStateException("not in world");
            ScreenHandler h = mc.player.currentScreenHandler;
            JsonObject o = new JsonObject();
            o.addProperty("handler", h.getClass().getSimpleName());
            o.addProperty("syncId", h.syncId);
            o.addProperty("screen", mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "none");
            JsonArray slots = new JsonArray();
            for (Slot s : h.slots) {
                JsonObject js = new JsonObject();
                js.addProperty("slot", s.id);
                js.addProperty("item", item(s.getStack()));
                js.addProperty("count", s.getStack().getCount());
                slots.add(js);
            }
            o.add("slots", slots);
            o.addProperty("cursor", item(h.getCursorStack()));
            if (h instanceof MerchantScreenHandler m) {
                JsonArray trades = new JsonArray();
                TradeOfferList offers = m.getRecipes();
                for (int i = 0; i < offers.size(); i++) {
                    TradeOffer t = offers.get(i);
                    JsonObject jt = new JsonObject();
                    jt.addProperty("index", i);
                    jt.addProperty("buyA", item(t.getDisplayedFirstBuyItem()));
                    jt.addProperty("buyB", item(t.getDisplayedSecondBuyItem()));
                    jt.addProperty("sell", item(t.getSellItem()));
                    jt.addProperty("disabled", t.isDisabled());
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
                    int slot = ctx.i("slot", 0);
                    int button = ctx.i("button", 0);
                    SlotActionType mode = slotAction(ctx.str("mode", "pickup"));
                    return ctx.onMain(() -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player == null || mc.interactionManager == null) throw new IllegalStateException("not in world");
                        ScreenHandler h = mc.player.currentScreenHandler;
                        mc.interactionManager.clickSlot(h.syncId, slot, button, mode, mc.player);
                        JsonObject o = new JsonObject();
                        o.addProperty("clicked", slot);
                        o.addProperty("mode", mode.name());
                        o.addProperty("cursor", item(h.getCursorStack()));
                        return o;
                    });
                });

        d.register("closeScreen", "close the open container/screen", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.closeHandledScreen();
            JsonObject o = new JsonObject();
            o.addProperty("closed", true);
            return o;
        }));

        d.register("selectTrade", "select a villager trade by index (open the villager first) {index}", ctx -> {
            int index = ctx.i("index", 0);
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.getNetworkHandler() == null) throw new IllegalStateException("not in world");
                if (!(mc.player.currentScreenHandler instanceof MerchantScreenHandler m)) {
                    throw new IllegalStateException("no villager trade screen open");
                }
                m.setRecipeIndex(index);
                mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(index));
                JsonObject o = new JsonObject();
                o.addProperty("selected", index);
                return o;
            });
        });

        d.register("screen", "list clickable widgets on the current screen (for modded button/field GUIs)", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            JsonObject o = new JsonObject();
            Screen sc = mc.currentScreen;
            o.addProperty("screen", sc != null ? sc.getClass().getSimpleName() : "none");
            JsonArray ws = new JsonArray();
            if (sc != null) {
                int i = 0;
                for (Element e : sc.children()) {
                    if (e instanceof ClickableWidget w) {
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
            int index = ctx.i("index", 0);
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                Screen sc = mc.currentScreen;
                if (sc == null) throw new IllegalStateException("no screen open");
                Element e = nth(sc, index);
                if (!(e instanceof ClickableWidget w)) throw new IllegalStateException("widget " + index + " is not clickable");
                w.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
                JsonObject o = new JsonObject();
                o.addProperty("clicked", index);
                o.addProperty("text", txt(w.getMessage()));
                return o;
            });
        });

        d.register("setText", "type into a text-field widget {index, text}", ctx -> {
            int index = ctx.i("index", 0);
            String text = ctx.str("text", "");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                Screen sc = mc.currentScreen;
                if (sc == null) throw new IllegalStateException("no screen open");
                Element e = nth(sc, index);
                if (!(e instanceof TextFieldWidget tf)) throw new IllegalStateException("widget " + index + " is not a text field");
                tf.setText(text);
                JsonObject o = new JsonObject();
                o.addProperty("set", text);
                return o;
            });
        });

        d.register("serverui", "read server-side UI: title/subtitle/action-bar, boss bars, scoreboard sidebar", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            JsonObject o = new JsonObject();
            try {
                InGameHudAccessor hud = (InGameHudAccessor) (Object) mc.inGameHud;
                JsonObject title = new JsonObject();
                title.addProperty("title", txt(hud.clef$getTitle()));
                title.addProperty("subtitle", txt(hud.clef$getSubtitle()));
                title.addProperty("actionBar", txt(hud.clef$getOverlayMessage()));
                o.add("title", title);
            } catch (Throwable ignored) {}
            try {
                BossBarHudAccessor bh = (BossBarHudAccessor) (Object) mc.inGameHud.getBossBarHud();
                JsonArray bars = new JsonArray();
                bh.clef$getBossBars().values().forEach(b -> {
                    JsonObject jb = new JsonObject();
                    jb.addProperty("name", txt(b.getName()));
                    jb.addProperty("percent", b.getPercent());
                    bars.add(jb);
                });
                o.add("bossBars", bars);
            } catch (Throwable ignored) {}
            try {
                if (mc.world != null) {
                    JsonArray objectives = new JsonArray();
                    for (ScoreboardObjective obj : mc.world.getScoreboard().getObjectives()) {
                        JsonObject jo = new JsonObject();
                        jo.addProperty("name", obj.getName());
                        jo.addProperty("display", txt(obj.getDisplayName()));
                        objectives.add(jo);
                    }
                    o.add("scoreboards", objectives);
                    ScoreboardObjective sidebar = mc.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
                    o.addProperty("sidebar", sidebar != null ? txt(sidebar.getDisplayName()) : "");
                }
            } catch (Throwable ignored) {}
            return o;
        }));

        // ---- higher-level inventory helpers (Baritone has none of this) ----

        d.register("findItem", "find inventory slots holding an item {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw new IllegalStateException("not in world");
                var inv = mc.player.getInventory();
                JsonArray found = new JsonArray();
                int total = 0;
                for (int i = 0; i < inv.size(); i++) {
                    if (idMatches(inv.getStack(i), q)) {
                        JsonObject j = new JsonObject();
                        j.addProperty("slot", i);
                        j.addProperty("count", inv.getStack(i).getCount());
                        found.add(j);
                        total += inv.getStack(i).getCount();
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("total", total);
                o.add("slots", found);
                return o;
            });
        });

        d.register("equip", "shift-click an item from inventory (armor/shield auto-equips) {item}", ctx -> {
            String q = ctx.requireStr("item");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.interactionManager == null) throw new IllegalStateException("not in world");
                ScreenHandler h = mc.player.currentScreenHandler;
                Object playerInv = mc.player.getInventory();
                for (Slot s : h.slots) {
                    if (s.inventory == playerInv && idMatches(s.getStack(), q)) {
                        mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                        JsonObject o = new JsonObject();
                        o.addProperty("equipped", q);
                        o.addProperty("fromSlot", s.id);
                        return o;
                    }
                }
                throw new IllegalStateException("no '" + q + "' in inventory");
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
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.interactionManager == null) throw new IllegalStateException("not in world");
                ScreenHandler h = mc.player.currentScreenHandler;
                int dropped = 0;
                for (Slot s : h.slots) {
                    if (idMatches(s.getStack(), q)) {
                        mc.interactionManager.clickSlot(h.syncId, s.id, 1, SlotActionType.THROW, mc.player);
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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) throw new IllegalStateException("not in world");
        ScreenHandler h = mc.player.currentScreenHandler;
        Object playerInv = mc.player.getInventory();
        int moved = 0;
        for (Slot s : h.slots) {
            boolean isPlayerSide = s.inventory == playerInv;
            if (isPlayerSide == intoContainer && idMatches(s.getStack(), q)) {
                mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                moved++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty(intoContainer ? "deposited" : "withdrew", moved);
        return o;
    }

    private static boolean idMatches(ItemStack st, String query) {
        if (st == null || st.isEmpty()) return false;
        String id = Registries.ITEM.getId(st.getItem()).toString();
        String q = query.trim().toLowerCase();
        return id.equals(q) || id.endsWith(":" + q);
    }

    // ---- helpers --------------------------------------------------------------------

    private static SlotActionType slotAction(String mode) {
        return switch (mode.trim().toLowerCase()) {
            case "quickmove", "shift", "quick_move" -> SlotActionType.QUICK_MOVE;
            case "swap" -> SlotActionType.SWAP;
            case "clone", "middle" -> SlotActionType.CLONE;
            case "throw", "drop" -> SlotActionType.THROW;
            case "pickupall", "double" -> SlotActionType.PICKUP_ALL;
            case "quickcraft" -> SlotActionType.QUICK_CRAFT;
            default -> SlotActionType.PICKUP;
        };
    }

    private static Element nth(Screen sc, int index) {
        java.util.List<? extends Element> kids = sc.children();
        if (index < 0 || index >= kids.size()) throw new IllegalStateException("no widget at index " + index);
        return kids.get(index);
    }

    private static String item(ItemStack st) {
        if (st == null || st.isEmpty()) return "empty";
        return Registries.ITEM.getId(st.getItem()) + " x" + st.getCount();
    }

    private static String txt(Text t) {
        return t == null ? "" : t.getString();
    }

    private UiCommands() {}
}
