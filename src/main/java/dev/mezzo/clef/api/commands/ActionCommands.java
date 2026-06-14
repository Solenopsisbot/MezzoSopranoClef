package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Bot actuation + world-query commands (movement, mining, placing, combat, inventory, queries). */
public final class ActionCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("move", "walk control {forward,backward,left,right,jump,sprint,sneak,durationMs?}", ctx -> {
            ctx.server.services.input.set(
                    ctx.bool("forward", false), ctx.bool("backward", false),
                    ctx.bool("left", false), ctx.bool("right", false),
                    ctx.bool("jump", false), ctx.bool("sneak", false), ctx.bool("sprint", false),
                    (long) ctx.d("durationMs", 0));
            JsonObject o = new JsonObject();
            o.addProperty("moving", ctx.server.services.input.isActive());
            return o;
        });

        d.register("stopMove", "stop all movement input", ctx -> {
            ctx.server.services.input.clear();
            JsonObject o = new JsonObject();
            o.addProperty("stopped", true);
            return o;
        });

        d.register("mine", "start breaking a block over time {x,y,z,face?}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            Direction face = parseFace(ctx.str("face", "up"));
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                ctx.server.services.actions.startMining(pos, face);
                JsonObject o = new JsonObject();
                o.addProperty("mining", true);
                return o;
            });
        });

        d.register("stopMine", "stop breaking", ctx -> ctx.onMain(() -> {
            ctx.server.services.actions.stopMining(MinecraftClient.getInstance());
            JsonObject o = new JsonObject();
            o.addProperty("stopped", true);
            return o;
        }));

        d.register("breakBlock", "instantly break a block (creative) {x,y,z}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            return ctx.onMain(() -> {
                boolean ok = ctx.server.services.actions.breakInstant(MinecraftClient.getInstance(), pos);
                JsonObject o = new JsonObject();
                o.addProperty("broken", ok);
                return o;
            });
        });

        d.register("place", "right-click/place against a block face {x,y,z,face?}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            Direction face = parseFace(ctx.str("face", "up"));
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                ctx.server.services.actions.interactBlock(mc, pos, face);
                JsonObject o = new JsonObject();
                o.addProperty("placed", true);
                return o;
            });
        });

        d.register("use", "use held item / right-click air {hand?}", ctx -> {
            Hand hand = parseHand(ctx.str("hand", "main"));
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                ctx.server.services.actions.useItem(mc, hand);
                JsonObject o = new JsonObject();
                o.addProperty("used", true);
                return o;
            });
        });

        d.register("attack", "attack {entityId?} or the nearest entity within reach", ctx -> {
            Integer id = ctx.has("entityId") ? ctx.i("entityId", -1) : null;
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.world == null) throw ApiException.notInWorld();
                Entity target = id != null ? mc.world.getEntityById(id) : nearest(mc, 4.0);
                if (target == null) throw ApiException.notFound("no target in range");
                ctx.server.services.actions.attackEntity(mc, target);
                JsonObject o = new JsonObject();
                o.addProperty("attacked", target.getId());
                o.addProperty("type", EntityType.getId(target.getType()).toString());
                return o;
            });
        });

        d.register("setSlot", "select hotbar slot {slot 0-8}", ctx -> {
            int slot = ctx.requireInt("slot");
            if (slot < 0 || slot > 8) throw ApiException.badArgs("slot must be 0-8");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                mc.player.getInventory().setSelectedSlot(slot);
                JsonObject o = new JsonObject();
                o.addProperty("slot", slot);
                return o;
            });
        });

        d.register("dropItem", "drop the held item {all?}", ctx -> {
            boolean all = ctx.bool("all", false);
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                boolean ok = mc.player.dropSelectedItem(all);
                JsonObject o = new JsonObject();
                o.addProperty("dropped", ok);
                return o;
            });
        });

        d.register("inventory", "list inventory contents", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            PlayerInventory inv = mc.player.getInventory();
            JsonObject o = new JsonObject();
            o.addProperty("selectedSlot", inv.getSelectedSlot());
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack st = inv.getStack(i);
                if (st.isEmpty()) continue;
                JsonObject it = new JsonObject();
                it.addProperty("slot", i);
                it.addProperty("item", Registries.ITEM.getId(st.getItem()).toString());
                it.addProperty("name", st.getName().getString());
                it.addProperty("count", st.getCount());
                items.add(it);
            }
            o.add("items", items);
            return o;
        }));

        d.register("entities", "list nearby entities {radius?=16}", ctx -> {
            double radius = ctx.d("radius", 16);
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.world == null) throw ApiException.notInWorld();
                double r2 = radius * radius;
                JsonArray arr = new JsonArray();
                for (Entity e : mc.world.getEntities()) {
                    if (e == mc.player || !e.isAlive()) continue;
                    double d2 = e.squaredDistanceTo(mc.player);
                    if (d2 > r2) continue;
                    JsonObject je = new JsonObject();
                    je.addProperty("id", e.getId());
                    je.addProperty("type", EntityType.getId(e.getType()).toString());
                    je.addProperty("name", e.getName().getString());
                    je.addProperty("x", e.getX());
                    je.addProperty("y", e.getY());
                    je.addProperty("z", e.getZ());
                    je.addProperty("distance", Math.sqrt(d2));
                    arr.add(je);
                }
                return arr;
            });
        });

        d.register("blockAt", "block id at {x,y,z}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.world == null) throw ApiException.notInWorld();
                var state = mc.world.getBlockState(pos);
                JsonObject o = new JsonObject();
                o.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
                o.addProperty("air", state.isAir());
                return o;
            });
        });

        d.register("interactEntity", "right-click an entity — mount/trade/breed/leash {entityId, hand?}", ctx -> {
            int id = ctx.requireInt("entityId");
            Hand hand = parseHand(ctx.str("hand", "main"));
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.world == null || mc.interactionManager == null) throw ApiException.notInWorld();
                Entity e = mc.world.getEntityById(id);
                if (e == null) throw ApiException.notFound("no entity with id " + id);
                ActionResult r = mc.interactionManager.interactEntity(mc.player, e, hand);
                JsonObject o = new JsonObject();
                o.addProperty("interacted", id);
                o.addProperty("type", EntityType.getId(e.getType()).toString());
                o.addProperty("result", String.valueOf(r));
                return o;
            });
        });

        d.register("swapHands", "swap main-hand and off-hand items", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.getNetworkHandler() == null) throw ApiException.notInWorld();
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
            JsonObject o = new JsonObject();
            o.addProperty("swapped", true);
            return o;
        }));

        d.register("pickBlock", "pick the block at {x,y,z} into the hotbar (like middle-click)", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            boolean nbt = ctx.bool("nbt", false);
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.interactionManager == null) throw ApiException.notInWorld();
                mc.interactionManager.pickItemFromBlock(pos, nbt);
                JsonObject o = new JsonObject();
                o.addProperty("picked", true);
                return o;
            });
        });

        d.register("useHold", "hold right-click for N ticks — charge bow/crossbow, block, fish {ticks?=40}", ctx -> {
            int ticks = ctx.i("ticks", 40);
            ctx.server.services.use.hold(ticks);
            JsonObject o = new JsonObject();
            o.addProperty("holding", ticks);
            return o;
        });

        d.register("useRelease", "release a held use (fire a charged bow / stop)", ctx -> {
            ctx.server.services.use.release();
            JsonObject o = new JsonObject();
            o.addProperty("released", true);
            return o;
        });

        d.register("eat", "eat/drink the currently held item {ticks?=40}", ctx -> {
            ctx.server.services.use.hold(ctx.i("ticks", 40));
            JsonObject o = new JsonObject();
            o.addProperty("eating", true);
            return o;
        });

        d.register("respawn", "respawn after death", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            mc.player.requestRespawn();
            JsonObject o = new JsonObject();
            o.addProperty("respawned", true);
            return o;
        }));
    }

    private static Direction parseFace(String s) {
        try {
            return Direction.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badArgs("face must be one of: up, down, north, south, east, west");
        }
    }

    private static Hand parseHand(String s) {
        String hand = s == null ? "main" : s.trim().toLowerCase();
        return switch (hand) {
            case "main", "main_hand", "mainhand" -> Hand.MAIN_HAND;
            case "off", "off_hand", "offhand" -> Hand.OFF_HAND;
            default -> throw ApiException.badArgs("hand must be 'main' or 'off'");
        };
    }

    private static Entity nearest(MinecraftClient mc, double maxDist) {
        Entity best = null;
        double bd = maxDist * maxDist;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !e.isAlive()) continue;
            double d2 = e.squaredDistanceTo(mc.player);
            if (d2 < bd) { bd = d2; best = e; }
        }
        return best;
    }

    private ActionCommands() {}
}
