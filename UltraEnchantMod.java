package ru.example.ultraenchant;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class UltraEnchantMod implements ModInitializer {
    /**
     * Vanilla enchantment levels are NBT shorts. Long levels live in a separate tag.
     * Very large enchanted levels DO NOT imply spawning that many actual entities.
     */
    private static final String EXTENDED_LEVELS = "UltraEnchantLevels";
    private static final String LORE_MARKER = "[UltraEnchant]";
    private static final String SPAWN_MARKER = "ultraenchant:spawn_processed";
    private static final int MAX_CONFIGURABLE_SHOTS = 256;
    private static int maxRealShots = 33;

    private record RecentUse(long level, long tick) {}
    private static final Map<UUID, RecentUse> RECENT_USES = new HashMap<>();
    private static final Map<UUID, Long> LAST_VOLLEYS = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("ue")
                // In singleplayer, allow the local player to use the command
                // even when cheats were disabled at world creation.
                // On dedicated servers, OP level 2 is still required.
                .requires(source -> source.hasPermissionLevel(2) || source.getServer().isSingleplayer())
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                        "UltraEnchant Million: /ue add minecraft:multishot 1000000 | " +
                        "/ue all 1000000 | /ue shots 33 | /ue clear"), false);
                    return 1;
                })
                .then(literal("add")
                    .then(argument("enchantment", IdentifierArgumentType.identifier())
                        .then(argument("level", LongArgumentType.longArg(1L))
                            .executes(ctx -> addSingle(ctx.getSource(),
                                IdentifierArgumentType.getIdentifier(ctx, "enchantment"),
                                LongArgumentType.getLong(ctx, "level"))))))
                .then(literal("all")
                    .then(argument("level", LongArgumentType.longArg(1L))
                        .executes(ctx -> addAll(ctx.getSource(),
                            LongArgumentType.getLong(ctx, "level")))))
                .then(literal("shots")
                    .then(argument("count", IntegerArgumentType.integer(3, MAX_CONFIGURABLE_SHOTS))
                        .executes(ctx -> {
                            maxRealShots = IntegerArgumentType.getInteger(ctx, "count");
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                "Максимум реальных снарядов за залп: " + maxRealShots), false);
                            return 1;
                        })))
                .then(literal("clear").executes(ctx -> clear(ctx.getSource())))
            )
        );

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!world.isClient && !player.isSpectator()) {
                long level = getTrueLevel(stack, new Identifier("minecraft", "multishot"));
                if (level > 0L) {
                    RECENT_USES.put(player.getUuid(), new RecentUse(level, world.getTime()));
                }
            }
            return TypedActionResult.pass(stack);
        });

        ServerEntityEvents.ENTITY_LOAD.register(UltraEnchantMod::onEntitySpawn);
    }

    private static ItemStack held(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return source.getPlayerOrThrow().getMainHandStack();
    }

    private static String enchantmentTag(ItemStack stack) {
        return stack.isOf(Items.ENCHANTED_BOOK) ? "StoredEnchantments" : "Enchantments";
    }

    private static long getTrueLevel(ItemStack stack, Identifier id) {
        NbtCompound root = stack.getNbt();
        if (root != null && root.contains(EXTENDED_LEVELS, NbtElement.COMPOUND_TYPE)) {
            NbtCompound extended = root.getCompound(EXTENDED_LEVELS);
            if (extended.contains(id.toString(), NbtElement.NUMBER_TYPE)) {
                return extended.getLong(id.toString());
            }
        }
        if (id.equals(new Identifier("minecraft", "multishot"))) {
            return EnchantmentHelper.getLevel(Enchantments.MULTISHOT, stack);
        }
        return 0L;
    }

    private static void setEnchantment(ItemStack stack, Identifier id, long level) {
        NbtCompound root = stack.getOrCreateNbt();
        NbtCompound extended = root.getCompound(EXTENDED_LEVELS);
        extended.putLong(id.toString(), level);
        root.put(EXTENDED_LEVELS, extended);

        // Keep a compatible vanilla enchantment for glint + baseline effect.
        // NBT short cannot represent 1,000,000, so it must NOT contain the real level.
        short vanillaLevel = (short) Math.min(level, Short.MAX_VALUE);
        String key = enchantmentTag(stack);
        NbtList entries = root.getList(key, NbtElement.COMPOUND_TYPE);
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            NbtCompound enchantment = entries.getCompound(i);
            if (enchantment.getString("id").equals(id.toString())) {
                enchantment.putShort("lvl", vanillaLevel);
                found = true;
                break;
            }
        }
        if (!found) {
            NbtCompound enchantment = new NbtCompound();
            enchantment.putString("id", id.toString());
            enchantment.putShort("lvl", vanillaLevel);
            entries.add(enchantment);
        }
        root.put(key, entries);
        refreshLore(stack);
    }

    private static void refreshLore(ItemStack stack) {
        NbtCompound root = stack.getOrCreateNbt();
        NbtCompound display = root.getCompound("display");
        NbtList oldLore = display.getList("Lore", NbtElement.STRING_TYPE);
        NbtList newLore = new NbtList();
        for (int i = 0; i < oldLore.size(); i++) {
            String value = oldLore.getString(i);
            if (!value.contains(LORE_MARKER)) newLore.add(NbtString.of(value));
        }
        NbtCompound extended = root.getCompound(EXTENDED_LEVELS);
        for (String id : extended.getKeys()) {
            String label = id.equals("minecraft:multishot") ? "Мультивыстрел" : id;
            Text line = Text.literal("§d" + LORE_MARKER + " §b" + label + " " + extended.getLong(id));
            newLore.add(NbtString.of(Text.Serializer.toJson(line)));
        }
        if (newLore.isEmpty()) display.remove("Lore");
        else display.put("Lore", newLore);
        if (display.isEmpty()) root.remove("display");
        else root.put("display", display);
    }

    private static int addSingle(ServerCommandSource source, Identifier id, long level)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) {
            source.sendError(Text.literal("Возьми предмет в основную руку."));
            return 0;
        }
        if (!Registries.ENCHANTMENT.containsId(id)) {
            source.sendError(Text.literal("Неизвестные чары: " + id));
            return 0;
        }
        setEnchantment(stack, id, level);
        source.sendFeedback(() -> Text.literal("Наложено: " + id + " " + level), false);
        return 1;
    }

    private static int addAll(ServerCommandSource source, long level)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) {
            source.sendError(Text.literal("Возьми предмет в основную руку."));
            return 0;
        }
        int[] count = {0};
        // Add in one pass so the enchantment lore is re-generated once.
        for (Identifier id : Registries.ENCHANTMENT.getIds()) {
            NbtCompound root = stack.getOrCreateNbt();
            NbtCompound extended = root.getCompound(EXTENDED_LEVELS);
            extended.putLong(id.toString(), level);
            root.put(EXTENDED_LEVELS, extended);
            String key = enchantmentTag(stack);
            NbtList entries = root.getList(key, NbtElement.COMPOUND_TYPE);
            NbtCompound enchant = new NbtCompound();
            enchant.putString("id", id.toString());
            enchant.putShort("lvl", (short) Math.min(level, Short.MAX_VALUE));
            boolean found = false;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.getCompound(i).getString("id").equals(id.toString())) {
                    entries.set(i, enchant);
                    found = true;
                    break;
                }
            }
            if (!found) entries.add(enchant);
            root.put(key, entries);
            count[0]++;
        }
        refreshLore(stack);
        source.sendFeedback(() -> Text.literal("Наложено чар: " + count[0] + ", уровень: " + level), false);
        return count[0];
    }

    private static int clear(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) return 0;
        NbtCompound root = stack.getNbt();
        if (root != null) {
            root.remove("Enchantments");
            root.remove("StoredEnchantments");
            root.remove(EXTENDED_LEVELS);
            refreshLore(stack);
        }
        source.sendFeedback(() -> Text.literal("Зачарования удалены."), false);
        return 1;
    }

    private static void onEntitySpawn(Entity entity, ServerWorld world) {
        if (!(entity instanceof ProjectileEntity projectile)) return;
        if (!(entity instanceof ThrownItemEntity || entity instanceof PersistentProjectileEntity)) return;
        if (!(projectile.getOwner() instanceof PlayerEntity shooter)) return;
        if (entity.getCommandTags().contains(SPAWN_MARKER)) return;
        entity.addCommandTag(SPAWN_MARKER);

        Identifier multishot = new Identifier("minecraft", "multishot");
        long level = Math.max(
            getTrueLevel(shooter.getMainHandStack(), multishot),
            getTrueLevel(shooter.getOffHandStack(), multishot)
        );
        RecentUse recent = RECENT_USES.get(shooter.getUuid());
        if (recent != null && recent.tick() >= world.getTime() - 1) {
            level = Math.max(level, recent.level());
        }
        if (level <= 0L) return;

        Long last = LAST_VOLLEYS.put(shooter.getUuid(), world.getTime());
        if (last != null && last == world.getTime()) return;

        // Saturating arithmetic: works even for Long.MAX_VALUE levels.
        int shotCount = level >= maxRealShots ? maxRealShots :
            Math.min(maxRealShots, Math.max(3, (int) (level * 2L + 1L)));
        Vec3d originalVelocity = projectile.getVelocity();
        if (originalVelocity.lengthSquared() < 0.00001) return;

        NbtCompound template = entity.writeNbt(new NbtCompound());
        for (int i = 0; i < shotCount - 1; i++) {
            Entity copy = entity.getType().create(world);
            if (!(copy instanceof ProjectileEntity extra)) continue;
            copy.readNbt(template.copy());
            copy.setUuid(UUID.randomUUID());
            copy.addCommandTag(SPAWN_MARKER);

            float fraction = (i + 1.0f) / shotCount;
            float degrees = -27.0f + 54.0f * fraction;
            Vec3d velocity = originalVelocity.rotateY((float) Math.toRadians(degrees));
            copy.refreshPositionAndAngles(entity.getX(), entity.getY(), entity.getZ(),
                entity.getYaw() + degrees, entity.getPitch());
            extra.setOwner(shooter);
            extra.setVelocity(velocity.x, velocity.y, velocity.z);
            world.spawnEntity(extra);
        }
    }
}
