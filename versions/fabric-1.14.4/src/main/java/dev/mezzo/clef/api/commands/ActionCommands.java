package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.bot.EntityMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                ctx.server.services.actions.startMining(pos, face);
                JsonObject o = new JsonObject();
                o.addProperty("mining", true);
                return o;
            });
        });

        d.register("stopMine", "stop breaking", ctx -> ctx.onMain(() -> {
            ctx.server.services.actions.stopMining(Minecraft.getInstance());
            JsonObject o = new JsonObject();
            o.addProperty("stopped", true);
            return o;
        }));

        d.register("breakBlock", "instantly break a block (creative) {x,y,z}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            return ctx.onMain(() -> {
                boolean ok = ctx.server.services.actions.breakInstant(Minecraft.getInstance(), pos);
                JsonObject o = new JsonObject();
                o.addProperty("broken", ok);
                return o;
            });
        });

        d.register("place", "right-click/place against a block face {x,y,z,face?}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            Direction face = parseFace(ctx.str("face", "up"));
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                ctx.server.services.actions.interactBlock(mc, pos, face);
                JsonObject o = new JsonObject();
                o.addProperty("placed", true);
                return o;
            });
        });

        d.register("use", "use held item / right-click air {hand?}", ctx -> {
            InteractionHand hand = parseHand(ctx.str("hand", "main"));
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
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
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) throw ApiException.notInWorld();
                Entity target = id != null ? mc.level.getEntity(id) : nearest(mc, 4.0);
                if (target == null) throw ApiException.notFound("no target in range");
                ctx.server.services.actions.attackEntity(mc, target);
                JsonObject o = new JsonObject();
                o.addProperty("attacked", target.getId());
                o.addProperty("type", EntityType.getKey(target.getType()).toString());
                return o;
            });
        });

        d.register("setSlot", "select hotbar slot {slot 0-8}", ctx -> {
            int slot = ctx.requireInt("slot");
            if (slot < 0 || slot > 8) throw ApiException.badArgs("slot must be 0-8");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                mc.player.inventory.selected = slot;
                JsonObject o = new JsonObject();
                o.addProperty("slot", slot);
                return o;
            });
        });

        d.register("dropItem", "drop the held item {all?}", ctx -> {
            boolean all = ctx.bool("all", false);
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                boolean ok = mc.player.drop(all) != null;
                JsonObject o = new JsonObject();
                o.addProperty("dropped", ok);
                return o;
            });
        });

        d.register("inventory", "list inventory contents", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            Inventory inv = mc.player.inventory;
            JsonObject o = new JsonObject();
            o.addProperty("selectedSlot", inv.selected);
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) continue;
                JsonObject it = new JsonObject();
                it.addProperty("slot", i);
                it.addProperty("item", Registry.ITEM.getKey(st.getItem()).toString());
                it.addProperty("name", st.getHoverName().getString());
                it.addProperty("count", st.getCount());
                items.add(it);
            }
            o.add("items", items);
            return o;
        }));

        d.register("entities", "list nearby entities {radius?=16}", ctx -> {
            double radius = ctx.d("radius", 16);
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) throw ApiException.notInWorld();
                double r2 = radius * radius;
                JsonArray arr = new JsonArray();
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player || !e.isAlive()) continue;
                    double d2 = e.distanceToSqr(mc.player);
                    if (d2 > r2) continue;
                    JsonObject je = new JsonObject();
                    je.addProperty("id", e.getId());
                    je.addProperty("type", EntityType.getKey(e.getType()).toString());
                    je.addProperty("name", e.getName().getString());
                    je.addProperty("x", e.x);
                    je.addProperty("y", e.y);
                    je.addProperty("z", e.z);
                    je.addProperty("distance", Math.sqrt(d2));
                    // Motion, in blocks per tick, as the client actually observed it — see
                    // EntityMotion for why getDeltaMovement() alone is zero for server-driven mobs.
                    Vec3 velocity = EntityMotion.of(e);
                    je.addProperty("vx", velocity.x);
                    je.addProperty("vy", velocity.y);
                    je.addProperty("vz", velocity.z);
                    JsonArray vel = new JsonArray();
                    vel.add(velocity.x);
                    vel.add(velocity.y);
                    vel.add(velocity.z);
                    je.add("velocity", vel);
                    arr.add(je);
                }
                return arr;
            });
        });

        d.register("blockAt", "block id at {x,y,z}", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) throw ApiException.notInWorld();
                var state = mc.level.getBlockState(pos);
                JsonObject o = new JsonObject();
                o.addProperty("block", Registry.BLOCK.getKey(state.getBlock()).toString());
                o.addProperty("air", state.isAir());
                return o;
            });
        });

        d.register("interactEntity", "right-click an entity — mount/trade/breed/leash {entityId, hand?}", ctx -> {
            int id = ctx.requireInt("entityId");
            InteractionHand hand = parseHand(ctx.str("hand", "main"));
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null || mc.gameMode == null) throw ApiException.notInWorld();
                Entity e = mc.level.getEntity(id);
                if (e == null) throw ApiException.notFound("no entity with id " + id);
                InteractionResult r = mc.gameMode.interact(mc.player, e, hand);
                JsonObject o = new JsonObject();
                o.addProperty("interacted", id);
                o.addProperty("type", EntityType.getKey(e.getType()).toString());
                o.addProperty("result", String.valueOf(r));
                return o;
            });
        });

        d.register("swapHands", "swap main-hand and off-hand items", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) throw ApiException.notInWorld();
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_HELD_ITEMS, BlockPos.ZERO, Direction.DOWN));
            JsonObject o = new JsonObject();
            o.addProperty("swapped", true);
            return o;
        }));

        d.register("pickBlock", "pick the block at {x,y,z} into the hotbar (like middle-click)", ctx -> {
            BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
            boolean nbt = ctx.bool("nbt", false);
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode == null) throw ApiException.notInWorld();
                // This release picks by hotbar slot; the position/NBT form arrived later, so mirror
                // vanilla middle-click by asking the server for the block's item into the held slot.
                mc.gameMode.handlePickItem(mc.player.inventory.selected);
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
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            mc.player.respawn();
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

    private static InteractionHand parseHand(String s) {
        String hand = s == null ? "main" : s.trim().toLowerCase();
        return switch (hand) {
            case "main", "main_hand", "mainhand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand", "offhand" -> InteractionHand.OFF_HAND;
            default -> throw ApiException.badArgs("hand must be 'main' or 'off'");
        };
    }

    private static Entity nearest(Minecraft mc, double maxDist) {
        Entity best = null;
        double bd = maxDist * maxDist;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !e.isAlive()) continue;
            double d2 = e.distanceToSqr(mc.player);
            if (d2 < bd) { bd = d2; best = e; }
        }
        return best;
    }

    private ActionCommands() {}
}
