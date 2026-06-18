package org.bukkit.craftbukkit.entity;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.entity.TeleportFlag;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.isaiah.common.entity.IRemoveReason;
import net.kyori.adventure.pointer.PointersSupplier;
import net.kyori.adventure.util.TriState;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftSound;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.ServerOperator;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.item.ItemStackBridge;
import org.cardboardpowered.bridge.world.level.LevelBridge;
import org.cardboardpowered.impl.world.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public abstract class CraftEntity implements org.bukkit.entity.Entity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static PermissibleBase perm;
    private static final CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new CraftPersistentDataTypeRegistry();
    static final PointersSupplier<org.bukkit.entity.Entity> POINTERS_SUPPLIER = PointersSupplier.<org.bukkit.entity.Entity>builder()
            .resolving(net.kyori.adventure.identity.Identity.DISPLAY_NAME, org.bukkit.entity.Entity::name)
            .resolving(net.kyori.adventure.identity.Identity.UUID, org.bukkit.entity.Entity::getUniqueId)
            .resolving(net.kyori.adventure.permission.PermissionChecker.POINTER, entity1 -> entity1::permissionValue)
            .build();

    protected final CraftServer server = CraftServer.INSTANCE;
    protected Entity entity;
    private final EntityType entityType;
    private EntityDamageEvent lastDamageEvent;
    private final CraftPersistentDataContainer persistentDataContainer = new CraftPersistentDataContainer(CraftEntity.DATA_TYPE_REGISTRY);
    // Paper start - Folia shedulers
    //public final io.papermc.paper.threadedregions.EntityScheduler taskScheduler = new io.papermc.paper.threadedregions.EntityScheduler(this);
    //private final io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler apiScheduler = new io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler(this);

    @Override
    public final io.papermc.paper.threadedregions.scheduler.EntityScheduler getScheduler() {
        //return this.apiScheduler; // TODO
        return null;
    };
    // Paper end - Folia schedulers

    public CraftEntity(final Entity entity) {
        this.entity = entity;
        this.entityType = CraftEntityType.minecraftToBukkit(entity.getType());
    }

    public static <T extends Entity> CraftEntity getEntity(CraftServer server, T entity) {
        Preconditions.checkArgument(entity != null, "Unknown entity");

        if (entity instanceof net.minecraft.world.entity.player.Player && !(entity instanceof ServerPlayer)) {
            return new CraftHumanEntity(server, (net.minecraft.world.entity.player.Player) entity);
        }

        if (entity instanceof EnderDragonPart complexPart) {
            if (complexPart.parentMob instanceof EnderDragon) {
                return new CraftEnderDragonPart(server, complexPart);
            } else {
                return new CraftComplexPart(server, complexPart);
            }
        }

        CraftEntityTypes.EntityTypeData<?, T> entityTypeData = CraftEntityTypes.getEntityTypeData(CraftEntityType.minecraftToBukkit(entity.getType()));

        if (entityTypeData != null) {
            return (CraftEntity) entityTypeData.convertFunction().apply(server, entity);
        }

        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            return new CraftMob(server, mob) {};
        }

        if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            return new CraftLivingEntity(server, livingEntity);
        }

        return new CraftEntity(entity) {};
    }

    public Entity getHandle() {
        return this.entity;
    }

    public Entity getHandleRaw() {
        return this.entity;
    }

    public void setHandle(final Entity entity) {
        this.entity = entity;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{uuid=" + this.getUniqueId() + '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final CraftEntity other = (CraftEntity) obj;
        return this.entity == other.entity; // There should never be duplicate entities with differing references
    }

    @Override
    public int hashCode() {
        return this.getUniqueId().hashCode();
    }

    @Override
    public Location getLocation() {
        return CraftLocation.toBukkit(this.entity.position(), this.getWorld(), ((EntityBridge)this.entity).cardboard$getBukkitYaw(), this.entity.getXRot());
    }

    @Override
    public Location getLocation(Location loc) {
        if (loc != null) {
            loc.setWorld(this.getWorld());
            loc.setX(this.entity.getX());
            loc.setY(this.entity.getY());
            loc.setZ(this.entity.getZ());
            loc.setYaw(((EntityBridge)this.entity).cardboard$getBukkitYaw());
            loc.setPitch(this.entity.getXRot());
        }

        return loc;
    }

    @Override
    public Vector getVelocity() {
        return CraftVector.toBukkit(this.entity.getDeltaMovement());
    }

    @Override
    public void setVelocity(Vector velocity) {
        Preconditions.checkArgument(velocity != null, "velocity");
        velocity.checkFinite();
        if (!(this instanceof org.bukkit.entity.Projectile || this instanceof org.bukkit.entity.Minecart) && isUnsafeVelocity(velocity)) {
        }
        this.entity.setDeltaMovement(CraftVector.toVec3(velocity));
        this.entity.hurtMarked = true;
    }

    private static boolean isUnsafeVelocity(Vector vel) {
        final double x = vel.getX();
        final double y = vel.getY();
        final double z = vel.getZ();

        if (x > 4 || x < -4 || y > 4 || y < -4 || z > 4 || z < -4) {
            return true;
        }

        return false;
    }

    @Override
    public double getHeight() {
        return this.getHandle().getBbHeight();
    }

    @Override
    public double getWidth() {
        return this.getHandle().getBbWidth();
    }

    @Override
    public BoundingBox getBoundingBox() {
        AABB bb = this.getHandle().getBoundingBox();
        return new BoundingBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    @Override
    public boolean isOnGround() {
        if (this.entity instanceof AbstractArrow abstractArrow) {
            return abstractArrow.isInGround();
        }
        return this.entity.onGround();
    }

    @Override
    public boolean isInWater() {
        return this.entity.isInWater();
    }

    @Override
    public World getWorld() {
        return ((LevelBridge)this.entity.level()).cardboard$getWorld();
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        NumberConversions.checkFinite(pitch, "pitch not finite");
        NumberConversions.checkFinite(yaw, "yaw not finite");

        yaw = Location.normalizeYaw(yaw);
        pitch = Location.normalizePitch(pitch);

        this.getHandle().forceSetRotation(yaw, false, pitch, false);
    }

    @Override
    public boolean teleport(Location location) {
        return this.teleport(location, TeleportCause.PLUGIN);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause) {
        return teleport(location, cause, new TeleportFlag[0]);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause, TeleportFlag... flags) {
        Preconditions.checkArgument(location != null, "location cannot be null");
        Preconditions.checkArgument(location.getWorld() != null, "Target world cannot be null");
        location.checkFinite();

        return this.teleport0(location, cause, flags);
    }

    protected boolean teleport0(Location location, TeleportCause cause, TeleportFlag... flags) {
        Entity entity = this.getHandle();
        if (!entity.isAlive() || !((EntityBridge)entity).isValidBF()) {
            return false;
        }

        final Set<net.minecraft.world.entity.Relative> relativeFlags = EnumSet.noneOf(net.minecraft.world.entity.Relative.class);
        for (final TeleportFlag flag : flags) {
            if (flag instanceof TeleportFlag.Relative relativeFlag) {
                relativeFlags.add(deltaRelativeToNMS(relativeFlag));
            }
        }

        return this.entity.teleport(new TeleportTransition(
                ((CraftWorld) location.getWorld()).getHandle(),
                CraftLocation.toVec3(location),
                Vec3.ZERO,
                location.getYaw(),
                location.getPitch(),
                relativeFlags,
                TeleportTransition.DO_NOTHING
        )) != null;
    }

    public static net.minecraft.world.entity.Relative deltaRelativeToNMS(TeleportFlag.Relative apiFlag) {
        return switch (apiFlag) {
            case VELOCITY_X -> net.minecraft.world.entity.Relative.DELTA_X;
            case VELOCITY_Y -> net.minecraft.world.entity.Relative.DELTA_Y;
            case VELOCITY_Z -> net.minecraft.world.entity.Relative.DELTA_Z;
            case VELOCITY_ROTATION -> net.minecraft.world.entity.Relative.ROTATE_DELTA;
        };
    }

    public static TeleportFlag.@Nullable Relative deltaRelativeToAPI(net.minecraft.world.entity.Relative nmsFlag) {
        return switch (nmsFlag) {
            case DELTA_X -> TeleportFlag.Relative.VELOCITY_X;
            case DELTA_Y -> TeleportFlag.Relative.VELOCITY_Y;
            case DELTA_Z -> TeleportFlag.Relative.VELOCITY_Z;
            case ROTATE_DELTA -> TeleportFlag.Relative.VELOCITY_ROTATION;
            case X, Y, Z, Y_ROT, X_ROT -> null;
        };
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination) {
        return this.teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination, TeleportCause cause) {
        return this.teleport(destination.getLocation(), cause);
    }

    // rest of file unchanged