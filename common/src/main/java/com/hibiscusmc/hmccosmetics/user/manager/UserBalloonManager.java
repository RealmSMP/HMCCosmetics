package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.entity.data.BukkitEntityData;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import lombok.Getter;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public class UserBalloonManager {

    private final CosmeticUser user;
    @Getter
    private BalloonType balloonType;
    private CosmeticBalloonType cosmeticBalloonType;
    @Getter
    private UserBalloonPufferfish pufferfish;
    private final ArmorStand modelEntity;
    private final UUID modelUniqueId;
    private final int modelId;
    private Location location;
    private Vector velocity = new Vector();
    private boolean removed = false;

    public UserBalloonManager(CosmeticUser user, @NotNull Location location) {
        this.user = user;
        this.location = location.clone();
        this.pufferfish = new UserBalloonPufferfish(user.getUniqueId(), NMSHandlers.getHandler().getUtilHandler().getNextEntityId(), UUID.randomUUID());
        this.modelEntity = location.getWorld().spawn(location, ArmorStand.class, (e) -> {
            e.setInvisible(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.setSmall(true);
            e.setMarker(true);
            e.setPersistent(false);
            e.setAI(false);
            e.getPersistentDataContainer().set(HMCCServerUtils.getCosmemeticMobKey(), PersistentDataType.BOOLEAN, true);
        });
        this.modelUniqueId = this.modelEntity.getUniqueId();
        this.modelId = this.modelEntity.getEntityId();
    }

    public void spawnModel(@NotNull CosmeticBalloonType cosmeticBalloonType, Color color) {
        // redo this
        if (cosmeticBalloonType.getModelName() != null && Hooks.isActiveHook("ModelEngine")) {
            balloonType = BalloonType.MODELENGINE;
        } else {
            if (cosmeticBalloonType.getItem() != null) {
                balloonType = BalloonType.ITEM;
            } else {
                balloonType = BalloonType.NONE;
            }
        }
        this.cosmeticBalloonType = cosmeticBalloonType;
        MessagesUtil.sendDebugMessages("balloontype is " + balloonType);

        if (balloonType == BalloonType.MODELENGINE) {
            String id = cosmeticBalloonType.getModelName();
            MessagesUtil.sendDebugMessages("Attempting Spawning for " + id);
            if (ModelEngineAPI.getBlueprint(id) == null) {
                MessagesUtil.sendDebugMessages("Invalid Model Engine Blueprint " + id, Level.SEVERE);
                return;
            }
            runOnModelEntity(entity -> {
                ModeledEntity modeledEntity = ModelEngineAPI.getOrCreateModeledEntity(entity);
                ActiveModel model = ModelEngineAPI.createActiveModel(ModelEngineAPI.getBlueprint(id));
                model.setCanHurt(false);
                modeledEntity.addModel(model, false);

                if (color != null) {
                    modeledEntity.getModels().forEach((d, singleModel) -> {
                        if (cosmeticBalloonType.isDyeablePart(d)) {
                            singleModel.setDefaultTint(color);
                            singleModel.getModelRenderer().sendToClient(ModelEngineAPI.getNMSHandler().createParsers());
                        }
                    });
                }

                BukkitEntityData data = (BukkitEntityData) modeledEntity.getBase().getData();
                data.setBlockedCullIgnoreRadius((double) Settings.getViewDistance());
                data.getTracked().setPlayerPredicate(this::playerCheck);
            });
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            runOnModelEntity(entity -> entity.getEquipment().setHelmet(cosmeticBalloonType.getItem()));
        }
    }

    public void remove() {
        if (removed) return;
        removed = true;

        // This code is like a brick road, always bumpy.
        // Basically, the balloon viewers ignore people in wardrobe, which well, if your the user in the wardrobe, ain't including you.
        // This manually passes in the viewers for it to destroy, which includes the person in the wardrobe
        if (user.getPlayer() != null && user.isInWardrobe()) pufferfish.destroyPufferfish(List.of(user.getPlayer()));
        else pufferfish.destroyPufferfish();

        cosmeticBalloonType = null;
        runOnModelEntity(entity -> {
            if (balloonType == BalloonType.MODELENGINE) {
                final ModeledEntity modeledEntity = ModelEngineAPI.getModeledEntity(entity);
                if (modeledEntity == null) {
                    MessagesUtil.sendDebugMessages("Balloon Removal Failed - Model Entity is Null");
                } else {
                    modeledEntity.destroy();
                    MessagesUtil.sendDebugMessages("Balloon Model Engine Removal");
                }
            }

            entity.remove();
            MessagesUtil.sendDebugMessages("Balloon Entity Removed");
        }, true, 3);
    }

    public void addPlayerToModel(final CosmeticUser user, final CosmeticBalloonType cosmeticBalloonType) {
        addPlayerToModel(user, cosmeticBalloonType, null);
    }

    public void addPlayerToModel(final CosmeticUser user, final CosmeticBalloonType cosmeticBalloonType, Color color) {
        if (balloonType == BalloonType.MODELENGINE) {
            runOnModelEntity(entity -> {
                final ModeledEntity model = ModelEngineAPI.getModeledEntity(entity);
                if (model == null) {
                    spawnModel(cosmeticBalloonType, color);
                    MessagesUtil.sendDebugMessages("model is null");
                    return;
                }

                MessagesUtil.sendDebugMessages("Show to player");
            });
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            runOnModelEntity(entity -> entity.getEquipment().setHelmet(user.getUserCosmeticItem(cosmeticBalloonType)));
        }
    }
    public void removePlayerFromModel(final Player viewer) {
        if (balloonType == BalloonType.MODELENGINE) {
            runOnModelEntity(entity -> {
                final ModeledEntity model = ModelEngineAPI.getModeledEntity(entity);
                if (model == null) return;

                MessagesUtil.sendDebugMessages("Hidden from player");
            });
            return;
        }
        if (balloonType == BalloonType.ITEM) {
            runOnModelEntity(entity -> entity.getEquipment().clear());
            return;
        }
    }

    public Entity getModelEntity() {
        return this.modelEntity;
    }


    public int getPufferfishBalloonId() {
        return pufferfish.getPufferFishEntityId();
    }

    public UUID getPufferfishBalloonUniqueId() {
        return pufferfish.getUuid();
    }

    public UUID getModelUnqiueId() {
        return modelUniqueId;
    }

    public int getModelId() {
        return modelId;
    }

    public Location getLocation() {
        return location.clone();
    }

    public void setLocation(Location location) {
        this.location = location.clone();
        runOnModelEntity(entity -> entity.teleportAsync(location));
    }

    public Vector getVelocity() {
        return velocity.clone();
    }

    public void setVelocity(Vector vector) {
        this.velocity = vector.clone();
        runOnModelEntity(entity -> entity.setVelocity(vector));
    }

    public void sendRemoveLeashPacket(List<Player> viewer) {
        HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), -1, viewer);
    }

    public void sendRemoveLeashPacket() {
        HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), -1, getLocation());
    }

    public void sendLeashPacket(int entityId) {
        if (cosmeticBalloonType.isShowLead()) {
            HMCCPacketManager.sendLeashPacket(getPufferfishBalloonId(), entityId, getLocation());
        }
    }

    public boolean isValid() {
        return !removed;
    }

    public enum BalloonType {
        MODELENGINE,
        ITEM,
        NONE
    }

    private boolean playerCheck(final Player player) {
        MessagesUtil.sendDebugMessages("playerCheck");
        CosmeticUser viewer = CosmeticUsers.getUser(player.getUniqueId());

        if (user.getPlayer() == player) {
            return (!user.isHidden());
        } else {
            if (user.isInWardrobe()) return false;
            MessagesUtil.sendDebugMessages("playerCheck - Not Same Player");
            if (viewer != null && viewer.isInWardrobe()) {
                MessagesUtil.sendDebugMessages("playerCheck - Viewer in Wardrobe");
                return false;
            }
        }
        return (!user.isHidden());
    }

    private void runOnModelEntity(@NotNull Consumer<ArmorStand> consumer) {
        runOnModelEntity(consumer, false, 3);
    }

    private void runOnModelEntity(@NotNull Consumer<ArmorStand> consumer, boolean allowRemoved) {
        runOnModelEntity(consumer, allowRemoved, 3);
    }

    private void runOnModelEntity(@NotNull Consumer<ArmorStand> consumer, boolean allowRemoved, int retriesLeft) {
        modelEntity.getScheduler().run(HMCCosmeticsPlugin.getInstance(), task -> {
            if ((!allowRemoved && removed) || !modelEntity.isValid()) return;
            consumer.accept(modelEntity);
        }, () -> {
            if (!modelEntity.isValid()) {
                removed = true;
                return;
            }

            if (retriesLeft <= 0) {
                MessagesUtil.sendDebugMessages("Balloon entity scheduler retired before task could run");
                return;
            }

            runOnModelEntity(consumer, allowRemoved, retriesLeft - 1);
        });
    }
}
