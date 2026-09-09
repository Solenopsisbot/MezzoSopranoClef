package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.bot.ActionManager;
import dev.mezzo.clef.nav.BaritoneNavigator;
import dev.mezzo.clef.bot.EntityMotion;
import dev.mezzo.clef.bot.Hotbar;
import dev.mezzo.clef.bot.RecipeIndex;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Bot actuation + world-query commands (movement, mining, placing, combat, inventory, queries). */
public final class ActionCommands {

    /** How long {@code place} waits for the server to confirm the world actually changed. */
    private static final int PLACE_CONFIRM_TICKS = 10;

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

        d.register("mine", "break a block over time {x,y,z,face?,wait?} — emits mineDone; wait blocks for it",
                ctx -> {
                    BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
                    Direction face = parseFace(ctx.str("face", "up"));
                    boolean wait = ctx.bool("wait", false);
                    // Let Baritone own navigation, tool selection, aiming, and mining whenever its
                    // command bridge is available. Its planner handles walls and re-aiming safely.
                    if (ctx.server.services.navigator instanceof BaritoneNavigator baritone
                            && baritone.blockArgumentsSafe()) {
                        ctx.server.services.input.clear();
                        baritone.runCommand("mine " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                        JsonObject delegated = new JsonObject();
                        delegated.addProperty("mining", true);
                        delegated.addProperty("backend", "baritone");
                        delegated.addProperty("wait", wait);
                        return delegated;
                    }
                    CompletableFuture<ActionManager.MineResult> done = ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null) throw ApiException.notInWorld();
                        return ctx.server.services.actions.startMining(mc, pos, face);
                    });
                    if (!wait) {
                        JsonObject o = new JsonObject();
                        // Requests answerable without the server (already air, bedrock) resolve on the
                        // spot, so report the real outcome instead of an optimistic "mining: true".
                        if (done.isDone()) return mineJson(done.get());
                        o.addProperty("mining", true);
                        return o;
                    }
                    // MAX_MINING_TICKS is ~10s; the extra headroom is for a laggy server, not for
                    // the mine itself, which always ends in a completion.
                    return mineJson(done.get(30, TimeUnit.SECONDS));
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

        d.register("place",
                "right-click/place against a block face {x,y,z,face?,item?,confirm?} — 'placed' is verified, not assumed",
                ctx -> {
                    BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
                    Direction face = parseFace(ctx.str("face", "up"));
                    String item = ctx.has("item") ? ctx.requireStr("item") : null;
                    boolean confirm = ctx.bool("confirm", true);

                    record Started(InteractionResult result, Hotbar.Selection selection,
                                   CompletableFuture<ActionManager.PlaceOutcome> outcome) {}

                    Started started = ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.gameMode == null) throw ApiException.notInWorld();
                        Hotbar.Selection selection = item == null ? null
                                : Hotbar.select(mc, RecipeIndex.resolveItem(item), null);
                        // Start watching *before* the click: the server can answer within a tick.
                        CompletableFuture<ActionManager.PlaceOutcome> outcome = confirm
                                ? ctx.server.services.actions.confirmPlace(mc, pos, face, PLACE_CONFIRM_TICKS)
                                : null;
                        InteractionResult result = ctx.server.services.actions.interactBlock(mc, pos, face);
                        return new Started(result, selection, outcome);
                    });

                    JsonObject o = new JsonObject();
                    o.addProperty("result", String.valueOf(started.result()));
                    if (started.selection() != null) o.addProperty("slot", started.selection().slot());
                    if (started.outcome() == null) {
                        // confirm:false — we only know the click was accepted locally.
                        o.addProperty("placed", started.result().consumesAction());
                        o.addProperty("confirmed", false);
                        return o;
                    }
                    ActionManager.PlaceOutcome outcome = started.outcome().get(5, TimeUnit.SECONDS);
                    o.addProperty("placed", outcome.placed());
                    o.addProperty("confirmed", true);
                    o.addProperty("ticks", outcome.ticks());
                    if (outcome.at() != null) {
                        o.addProperty("x", outcome.at().getX());
                        o.addProperty("y", outcome.at().getY());
                        o.addProperty("z", outcome.at().getZ());
                        o.addProperty("block", outcome.block());
                    }
                    return o;
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
                mc.player.getInventory().setSelectedSlot(slot);
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
                boolean ok = mc.player.drop(all);
                JsonObject o = new JsonObject();
                o.addProperty("dropped", ok);
                return o;
            });
        });

        d.register("inventory", "list inventory contents", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw ApiException.notInWorld();
            Inventory inv = mc.player.getInventory();
            JsonObject o = new JsonObject();
            o.addProperty("selectedSlot", inv.getSelectedSlot());
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) continue;
                JsonObject it = new JsonObject();
                it.addProperty("slot", i);
                it.addProperty("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                it.addProperty("name", st.getHoverName().getString());
                it.addProperty("count", st.getCount());
                items.add(it);
            }
            o.add("items", items);
            return o;
        }));

        d.register("entities",
                "list nearby entities with combat/trade detail {radius?=16, kinds?:[type ids]}",
                ctx -> {
                    double radius = ctx.d("radius", 16);
                    Set<String> kinds = ctx.strings("kinds");
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.level == null) throw ApiException.notInWorld();
                        double r2 = radius * radius;
                        JsonArray arr = new JsonArray();
                        for (Entity e : mc.level.entitiesForRendering()) {
                            if (e == mc.player || !e.isAlive()) continue;
                            double d2 = e.distanceToSqr(mc.player);
                            if (d2 > r2) continue;
                            String type = EntityType.getKey(e.getType()).toString();
                            if (kinds != null && !matchesKind(kinds, type)) continue;
                            arr.add(describeEntity(mc, e, type, Math.sqrt(d2)));
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
                o.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
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
                // 26.x wants the hit result too; hitting the entity at its own position is what a plain
                // "right-click the mob" does.
                InteractionResult r = mc.gameMode.interact(mc.player, e, new EntityHitResult(e), hand);
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
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
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
                mc.gameMode.handlePickItemFromBlock(pos, nbt);
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

    /** Shared shape between {@code mine wait:true} and the {@code mineDone} event. */
    private static JsonObject mineJson(ActionManager.MineResult result) {
        JsonObject o = new JsonObject();
        o.addProperty("x", result.pos().getX());
        o.addProperty("y", result.pos().getY());
        o.addProperty("z", result.pos().getZ());
        o.addProperty("mining", false);
        o.addProperty("broken", result.broken());
        if (result.reason() != null) o.addProperty("reason", result.reason());
        if (result.detail() != null) o.addProperty("detail", result.detail());
        o.addProperty("ticks", result.ticks());
        return o;
    }

    // ---- entity description ----------------------------------------------------------

    /**
     * Everything about a nearby entity that a survival bot has to decide on: is it dangerous, is it
     * hurt, what is it holding, is it mine, and is it looking at me. Fields that don't apply to a
     * given entity are simply absent rather than null-filled.
     */
    static JsonObject describeEntity(Minecraft mc, Entity e, String type, double distance) {
        JsonObject je = new JsonObject();
        je.addProperty("id", e.getId());
        je.addProperty("type", type);
        je.addProperty("name", e.getName().getString());
        je.addProperty("x", e.getX());
        je.addProperty("y", e.getY());
        je.addProperty("z", e.getZ());
        je.addProperty("distance", distance);
        je.addProperty("onFire", e.isOnFire());

        // Motion, in blocks per tick, as the client actually observed it — see EntityMotion for
        // why Entity.getDeltaMovement() alone reports zero for every server-driven mob. Exposed
        // twice: `vx/vy/vz` for callers doing arithmetic on it, `velocity` as the array this
        // command has always had. Same numbers; picking either is fine.
        Vec3 velocity = EntityMotion.of(e);
        je.addProperty("vx", velocity.x);
        je.addProperty("vy", velocity.y);
        je.addProperty("vz", velocity.z);
        JsonArray vel = new JsonArray();
        vel.add(velocity.x);
        vel.add(velocity.y);
        vel.add(velocity.z);
        je.add("velocity", vel);

        if (e instanceof LivingEntity living) {
            je.addProperty("health", living.getHealth());
            je.addProperty("maxHealth", living.getMaxHealth());
            je.addProperty("armor", living.getArmorValue());
            je.addProperty("baby", living.isBaby());
            ItemStack held = living.getMainHandItem();
            if (!held.isEmpty()) je.addProperty("held", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
            je.addProperty("lookingAtMe", lookingAt(living, mc.player));
        }

        // "Hostile" is two different questions: is this the kind of thing that attacks players, and
        // is this particular one attacking *me* right now. A tamed wolf is not a monster but can be
        // actively hunting you; a distant zombie is a monster that hasn't noticed you.
        boolean monster = e.getType().getCategory() == MobCategory.MONSTER;
        boolean targetingUs = e instanceof Mob mob && mob.getTarget() == mc.player;
        je.addProperty("hostile", monster || targetingUs);
        je.addProperty("targetingMe", targetingUs);

        if (e instanceof ItemEntity item) {
            JsonObject stack = new JsonObject();
            stack.addProperty("id", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
            stack.addProperty("count", item.getItem().getCount());
            je.add("item", stack);
        }
        if (e instanceof Villager villager) {
            JsonObject data = new JsonObject();
            villager.getVillagerData().profession().unwrapKey()
                    .ifPresent(key -> data.addProperty("profession", key.identifier().toString()));
            data.addProperty("level", villager.getVillagerData().level());
            je.add("villager", data);
        }
        if (e instanceof TamableAnimal tameable && tameable.isTame()) {
            var owner = tameable.getOwnerReference();
            if (owner != null) {
                je.addProperty("owner", owner.getUUID().toString());
                je.addProperty("ownedByMe", mc.player != null && owner.getUUID().equals(mc.player.getUUID()));
            }
        }
        return je;
    }

    /**
     * How squarely {@code source} is facing {@code target}: the dot product of its look vector with
     * the direction to the target's eyes. 1 is dead-on, 0 is side-on, negative is facing away.
     */
    private static double lookingAt(LivingEntity source, Entity target) {
        if (target == null) return 0;
        Vec3 look = source.getViewVector(1.0f).normalize();
        Vec3 toTarget = target.getEyePosition().subtract(source.getEyePosition());
        double length = toTarget.length();
        if (length < 1e-6) return 1;
        return look.dot(toTarget.scale(1.0 / length));
    }

    /** Accepts both {@code minecraft:zombie} and a bare {@code zombie} in the {@code kinds} filter. */
    private static boolean matchesKind(Set<String> kinds, String type) {
        if (kinds.contains(type)) return true;
        int colon = type.indexOf(':');
        return colon >= 0 && kinds.contains(type.substring(colon + 1));
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
