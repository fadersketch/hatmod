package com.hatmod.client;

import com.hatmod.BrainrotCycle;
import com.hatmod.HatMod;
import com.hatmod.Teto;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
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

    /** 这一轮是不是我们把视角**强行按回了第一人称**；窗口结束 / 链路断掉时要还回去。 */
    private static boolean forcedFirstPerson;
    private static CameraType savedCameraType;

    /** 声波方向：在这一轮第一次进入窗口时锁死一次。 */
    private static boolean sonicLatched;
    private static float sonicYaw;
    private static float sonicPitch;

    private TetoFocus() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !BrainrotQteClient.active()) {
            sonicLatched = false;
            restoreCamera(minecraft);
            return;
        }
        int age = BrainrotQteClient.age();
        if (age < 0) {
            restoreCamera(minecraft);
            return;
        }

        // ① 尖叫那一轮：锁在声波方向上，锁到控制结束。
        //    只有声波真放出来了才锁（{@link SonicFlash} 由服务端的启动包点亮）
        if (SonicFlash.locking()) {
            restoreCamera(minecraft);
            if (!sonicLatched) {
                latchSonic(player);
                sonicLatched = true;
            }
            turn(player, sonicYaw, sonicPitch, SONIC_TURN_PER_TICK);
            return;
        }
        sonicLatched = false;

        // ② 「Nope, too late」那一下：看最近的 Teto。
        // 窗口里**先把视角按回第一人称**：第三人称下改的只是玩家的 yaw，整个人会绕着镜头打转，
        // 等于什么都看不到。玩家自己按 F5 也不让改（每刻确认一次），窗口一过再还回原视角。
        if (!BrainrotCycle.tetoFocusWindow(age)) {
            restoreCamera(minecraft);
            return;
        }
        forceFirstPerson(minecraft);
        Entity target = mostForwardTeto(player);
        if (target == null) {
            return;
        }
        Vec3 aim = target.getBoundingBox().getCenter();
        float[] angles = anglesTo(player, aim);
        // 站定一旦开始就**精确对准**（速率 1 = 直接钉死），不再按比例逼近：
        // 四只彼此严格差 90°，镜头精确对准其中一只，另外三只就必然在正右 / 正后 / 正左。
        float rate = age >= BrainrotCycle.stillStart() ? 1.0F : TURN_PER_TICK;
        turn(player, angles[0], angles[1], rate);
    }

    /** 把视角按回第一人称；第一次动手前先记住玩家原本是第几人称。 */
    private static void forceFirstPerson(Minecraft minecraft) {
        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            return;
        }
        if (!forcedFirstPerson) {
            savedCameraType = minecraft.options.getCameraType();
            forcedFirstPerson = true;
        }
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
    }

    /** 窗口结束：把玩家原本的视角还回去（没强改过就什么都不做）。 */
    private static void restoreCamera(Minecraft minecraft) {
        if (!forcedFirstPerson) {
            return;
        }
        forcedFirstPerson = false;
        if (savedCameraType != null) {
            minecraft.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
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

    /**
     * 挑**最接近玩家正前方**的那只 Teto（不是距离最近的）。
     *
     * <p>站定前整圈已经对到前后左右，四只距离完全一样 —— 按「最近」挑等于随机挑到侧面甚至背后，
     * 镜头白白转 90°/180°，正前那只反而被甩到身后。按方位挑，镜头几乎不用动，
     * 正前那只就一直正对着人。
     */
    private static Entity mostForwardTeto(LocalPlayer player) {
        Entity best = null;
        float bestDiff = 180.0F;
        for (Teto teto : player.level().getEntitiesOfClass(Teto.class, player.getBoundingBox().inflate(RANGE))) {
            if (teto.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            // 只锁**自己的** Teto：别人的（其它玩家的、Khragg 的）一律不碰镜头
            if (!teto.isOwnedBy(player)) {
                continue;
            }
            double dx = teto.getX() - player.getX();
            double dz = teto.getZ() - player.getZ();
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float diff = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = teto;
            }
        }
        return best;
    }
}
