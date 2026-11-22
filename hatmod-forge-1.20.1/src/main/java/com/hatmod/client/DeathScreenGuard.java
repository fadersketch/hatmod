package com.hatmod.client;

import com.hatmod.HatAbilities;
import com.hatmod.HatMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端侧的「被外来硬杀从世界里摘掉」抢救 —— **只在破防模式 + 戴帽时生效**。
 *
 * <p>有的硬杀在服务端把人摘掉之后，还会给所有客户端发一条移除包；
 * 落到自己身上就是「本地玩家被从这个客户端世界里删掉」：镜头还能转
 * （{@code Minecraft.player} 这个对象还在被 tick），但人已经不在世界里了，死亡界面也关不掉。
 * 有的则是直接把血写成 -∞。
 *
 * <p>这里每刻做三件事：
 * <ul>
 *   <li>发现自己被删了（{@code isRemoved()}）就 {@code unsetRemoved} 再塞回客户端世界；</li>
 *   <li>血已经回来之后，把还挂着的死亡界面强行关掉 —— 它不会自己消失。</li>
 * </ul>
 *
 * <p>正常模式一行都不做：没开破防、或没戴帽子时，该怎么杀就怎么杀。
 * 换维度 / 换存档后的 3 秒内也不插手 —— 那段时间客户端会自己重建 LocalPlayer，
 * 插手反而会把旧世界的玩家对象塞进新世界（画面直接不渲染）。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DeathScreenGuard {

    /** 上一次看到的客户端世界：换维度 / 换存档会换对象。 */
    private static ClientLevel lastLevel;
    /** 换世界之后先等这么多刻，让客户端自己重建玩家。 */
    private static int levelGrace;
    /** 抢救日志已经打过几次。 */
    private static int rescued;

    private DeathScreenGuard() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        rescue();
    }

    private static void rescue() {
        // 只在破防模式；没有帽子也不管 —— 这是帽子的能力，不是白给的。
        if (!ClientHatSettings.forceBreak()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        if (self == null || minecraft.level == null) {
            lastLevel = null;
            return;
        }
        if (HatAbilities.hatStack(self).isEmpty()) {
            return;
        }
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            levelGrace = 60;
        }
        if (levelGrace > 0) {
            levelGrace--;
            return;
        }
        float health = self.getHealth();
        boolean alive = !Float.isNaN(health) && !Float.isInfinite(health) && health > 0.0F;
        if (!alive) {
            return;
        }
        // 被摘掉：本地玩家已经不在客户端世界里了。塞回去，否则画面里没有身体、
        // 只有镜头跟着一个「不存在」的玩家转。
        if (self.isRemoved()) {
            unsetRemoved(self);
            addEntity(minecraft, self);
            if (rescued++ < 3) {
                HatMod.LOGGER.warn("[HatMod] 本地玩家被从客户端世界里摘掉了，已塞回去");
            }
        }
        Screen screen = minecraft.screen;
        if (screen != null && isDeathScreen(screen)) {
            minecraft.setScreen(null);
            if (rescued++ < 3) {
                HatMod.LOGGER.warn("[HatMod] 血已经回来了，关掉还挂着的死亡界面");
            }
        }
    }

    /** 客户端这份实体是不是已经被标成删除了。 */
    private static void unsetRemoved(Entity entity) {
        for (String name : new String[]{"unsetRemoved", "m_146912_"}) {
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method method = c.getDeclaredMethod(name);
                    method.setAccessible(true);
                    method.invoke(entity);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // 换下一个名字 / 父类
                } catch (Throwable ignored) {
                    return;
                }
            }
        }
    }

    /** 把本地玩家重新塞回客户端世界（{@code ClientLevel#addEntity}，生产环境是 SRG 名）。 */
    private static void addEntity(Minecraft minecraft, LocalPlayer self) {
        for (String name : new String[]{"addEntity", "m_104639_"}) {
            try {
                java.lang.reflect.Method add = minecraft.level.getClass()
                        .getMethod(name, Entity.class);
                add.setAccessible(true);
                add.invoke(minecraft.level, self);
                return;
            } catch (NoSuchMethodException ignored) {
                // 换下一个名字
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static boolean isDeathScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.equals("net.minecraft.client.gui.screens.DeathScreen");
    }
}
