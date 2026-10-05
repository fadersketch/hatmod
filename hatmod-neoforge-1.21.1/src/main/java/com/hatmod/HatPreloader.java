package com.hatmod;

/**
 * 启动时把本模组自己的类全部加载一遍。
 *
 * <p>为什么需要这一步：有些内存优化模组（例如 AllTheLeaks 针对 FML#10684 的处理）
 * 会在加载结束后强制关闭 mod jar 的文件系统句柄。之后任何**第一次**被加载的类
 * 都会因为读不到 jar 而抛 {@code NoClassDefFoundError}，表现就是「一戴上帽子游戏就崩」——
 * 崩溃点恰好是第一个被延迟加载的类。
 *
 * <p>这里用 {@code Class.forName(...)} 在 mod 构造阶段（jar 还开着）把它们全部登记进
 * 类加载器，之后就不再依赖 jar 句柄了。类名写成字符串而不是直接引用，
 * 这样在服务端也能安全跳过客户端专属的类。
 */
public final class HatPreloader {

    /** 双端都存在的类。 */
    private static final String[] COMMON = {
            "com.hatmod.HatAbilities",
            "com.hatmod.HatAbilities$Hold",
            "com.hatmod.HatCommand",
            "com.hatmod.HatCreativeTab",
            "com.hatmod.HatDamageTypes",
            "com.hatmod.HatDurability",
            "com.hatmod.HatEffects",
            "com.hatmod.HatEffects$VeilEffect",
            "com.hatmod.HatEnchants",
            "com.hatmod.HatEnchants$Kind",
            "com.hatmod.HatFusionRecipe",
            "com.hatmod.HatItems",
            "com.hatmod.HatItems$1",
            "com.hatmod.HatMod",
            "com.hatmod.HatMusic",
            "com.hatmod.HatNetwork",
            "com.hatmod.HatNetwork$BeamTargetPayload",
            "com.hatmod.HatNetwork$ForesightPayload",
            "com.hatmod.HatNetwork$GreenHeartsPayload",
            "com.hatmod.HatNetwork$MusicPayload",
            "com.hatmod.HatNetwork$SettingsSyncPayload",
            "com.hatmod.HatNetwork$SettingsSyncPayload$Snapshot",
            "com.hatmod.HatNetwork$RouteSnapshot",
            "com.hatmod.HatNetwork$SettingsUpdatePayload",
            "com.hatmod.HatNetwork$RoutePayload",
            "com.hatmod.HatParticles",
            "com.hatmod.HatParticles$SimpleParticleTypeImpl",
            "com.hatmod.HatPreloader",
            "com.hatmod.HatProtection",
            "com.hatmod.HatRecipes",
            "com.hatmod.HatRouteState",
            "com.hatmod.HatSettings",
            "com.hatmod.HatSettings$1",
            "com.hatmod.HatSettings$ConfigFile",
            "com.hatmod.HatSettings$Entry",
            "com.hatmod.HatSounds",
            "com.hatmod.HatSounds$1",
            "com.hatmod.HatState",
            "com.hatmod.HatTunerItem",
            "com.hatmod.HatType",
            "com.hatmod.HatType$Trait",
    };

    /** 只在客户端存在的类（渲染、模型、粒子、客户端事件）。 */
    private static final String[] CLIENT = {
            "com.hatmod.client.BeamRenderer",
            "com.hatmod.client.BeamRenderTypes",
            "com.hatmod.client.BeamTargets",
            "com.hatmod.client.ClientHatSettings",
            "com.hatmod.client.ClientHatSettings$Snapshot",
            "com.hatmod.client.CowboyHatModel",
            "com.hatmod.client.FlashLightParticle",
            "com.hatmod.client.FlashLightParticle$Provider",
            "com.hatmod.client.ForesightFilter",
            "com.hatmod.client.GreenHeartRender",
            "com.hatmod.client.HatItemClientExtensions",
            "com.hatmod.client.HatItemClientExtensions$1",
            "com.hatmod.client.HatModClient",
            "com.hatmod.client.HatMusicInstance",
            "com.hatmod.client.HatMusicPlayer",
            "com.hatmod.client.HatMusicPlayer$Track",
            "com.hatmod.client.HatTexturePreloader",
            "com.hatmod.client.HatTexturePreloader$1",
            "com.hatmod.client.HatTunerScreen",
    };

    private HatPreloader() {
    }

    /**
     * 装了 Curios 没有。
     *
     * <p>引用了 Curios 的那两个类（{@link CuriosCompat}、{@code client.CurioHatRenderer}）
     * 只能在装了 Curios 时预加载，否则会因为找不到 Curios 的类而失败。
     */
    private static boolean curiosPresent() {
        net.neoforged.fml.ModList list = net.neoforged.fml.ModList.get();
        return list != null && list.isLoaded("curios");
    }

    /** 由 mod 构造函数调用（双端都会跑）。 */
    public static void preloadCommon() {
        ClassLoader loader = HatPreloader.class.getClassLoader();
        for (String name : COMMON) {
            load(name, loader);
        }
        if (curiosPresent()) {
            load("com.hatmod.CuriosCompat", loader);
        }
    }

    /** 由客户端专用的 HatModClient 静态块调用（只在客户端存在的那一半）。 */
    public static void preloadClient() {
        ClassLoader loader = HatPreloader.class.getClassLoader();
        for (String name : CLIENT) {
            load(name, loader);
        }
        if (curiosPresent()) {
            load("com.hatmod.client.CurioHatRenderer", loader);
        }
    }

    private static void load(String name, ClassLoader loader) {
        try {
            // initialize=false：只是把类登记进类加载器，不跑静态初始化块。
            Class.forName(name, false, loader);
        } catch (Throwable throwable) {
            HatMod.LOGGER.warn("[HatMod] 预加载类 {} 失败：{}", name, throwable.toString());
        }
    }
}
