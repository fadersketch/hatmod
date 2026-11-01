package com.hatmod.client;

import com.hatmod.BrainrotCycle;
import com.hatmod.HatMod;
import com.hatmod.Teto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 脑蚀里的两段**强制视线**。
 *
 * <ol>
 *   <li>「Nope, too late」那一下：镜头扳到最近的一只 Teto 身上 —— 它的小动作只有一瞬，
 *       正常打的时候没人会刚好在那一刻看着它；
 *   <li>尖叫那一轮：镜头锁在**声波射出去的方向**，一直锁到控制结束（和缓慢同长）。
 *       方向在声波射出那一刻**锁死一次**（那一刻最近的那个目标），之后小怪走动、
 *       被光束打飞都不再改朝向 —— 不然镜头会跟着乱甩。
 * </ol>
 *
 * <p>都是每刻把「剩余角度」转掉一定比例，是被"扳"过去的，不是瞬移；
 * 只改本地玩家的 {@code yRot} / {@code xRot}，不碰服务端状态，窗口一过立刻交还控制权。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID, value = Dist.CLIENT)
public final class TetoFocus {

    /** 「Nope, too late」那一下：每刻转向剩余角度的比例（0.35 约 0.3 秒到位）。 */
    private static final float TURN_PER_TICK = 0.35F;
    /** 声波那 40 秒：转过一次就到位，之后轻轻咬住，别让玩家觉得镜头被钉死得太硬。 */
    private static final float SONIC_TURN_PER_TICK = 0.25F;
    /** 只找这个范围内的 Teto（格）。 */
    private static final double RANGE = 16.0D;
    /** 声波方向锁定时，找目标的半径（格）。 */
    private static final double SONIC_RANGE = 40.0D;
    /** 抬头/低头的上限：别把镜头扳成对天对地。 */
    private static final float PITCH_LIMIT = 60.0F;

    /** 声波方向：在这一轮第一次进入窗口时锁死一次。 */
    private static boolean sonicLatched;
    private static float sonicYaw;
    private static float sonicPitch;

    private TetoFocus() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !BrainrotQteClient.active()) {
            sonicLatched = false;
            return;
        }
        int age = BrainrotQteClient.age();
        if (age < 0) {
            return;
        }

        // ① 尖叫那一轮：锁在声波方向上，锁到控制结束
        if (BrainrotCycle.sonicLockWindow(age)) {
            if (!sonicLatched) {
                latchSonic(player);
                sonicLatched = true;
            }
            turn(player, sonicYaw, sonicPitch, SONIC_TURN_PER_TICK);
            return;
        }
        sonicLatched = false;

        // ② 「Nope, too late」那一下：看最近的 Teto。
        // **只有第一人称**才转视线 —— 第三人称下改的是玩家的 yaw，整个人会绕着镜头打转，很别扭；
        // 那种视角下只留「缓慢」，让人感觉脚下发沉就够了。
        if (!minecraft.options.getCameraType().isFirstPerson() || !BrainrotCycle.tetoFocusWindow(age)) {
            return;
        }
        Entity target = nearestTeto(player);
        if (target == null) {
            return;
        }
        Vec3 aim = target.getBoundingBox().getCenter();
        float[] angles = anglesTo(player, aim);
        turn(player, angles[0], angles[1], TURN_PER_TICK);
    }

    /** 锁死此刻的声波方向：最近的那个活物（没有就保持当前朝向）。 */
    private static void latchSonic(LocalPlayer player) {
        LivingEntity best = null;
        double bestSq = SONIC_RANGE * SONIC_RANGE;
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(SONIC_RANGE))) {
            if (entity == player || !entity.isAlive() || entity instanceof Teto) {
                continue;
            }
            double distance = entity.distanceToSqr(player);
            if (distance < bestSq) {
                bestSq = distance;
                best = entity;
            }
        }
        if (best == null) {
            sonicYaw = player.getYRot();
            sonicPitch = player.getXRot();
            return;
        }
        float[] angles = anglesTo(player, best.getBoundingBox().getCenter());
        sonicYaw = angles[0];
        sonicPitch = angles[1];
    }

    /** 从眼睛到目标中心：{yaw, pitch}（度）。 */
    private static float[] anglesTo(LocalPlayer player, Vec3 aim) {
        Vec3 eye = player.getEyePosition();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, flat) * (180.0D / Math.PI)));
        return new float[]{yaw, Mth.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT)};
    }

    private static void turn(LocalPlayer player, float yaw, float pitch, float rate) {
        float turnYaw = Mth.wrapDegrees(yaw - player.getYRot()) * rate;
        player.setYRot(player.getYRot() + turnYaw);
        player.yRotO += turnYaw;
        float turnPitch = (pitch - player.getXRot()) * rate;
        player.setXRot(player.getXRot() + turnPitch);
        player.xRotO += turnPitch;
    }

    private static Entity nearestTeto(LocalPlayer player) {
        Entity best = null;
        double bestSq = RANGE * RANGE;
        for (Teto teto : player.level().getEntitiesOfClass(Teto.class, player.getBoundingBox().inflate(RANGE))) {
            double distance = teto.distanceToSqr(player);
            if (distance < bestSq) {
                bestSq = distance;
                best = teto;
            }
        }
        return best;
    }
}
