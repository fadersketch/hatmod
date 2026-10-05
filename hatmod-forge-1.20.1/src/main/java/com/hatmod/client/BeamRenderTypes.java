package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 光柱用的渲染类型。
 *
 * <p>用**原版**的 {@link RenderType#energySwirl(ResourceLocation, float, float)}，
 * 不自己 {@code RenderType.create} 造类型（光影模组是按渲染类型接管绘制的，
 * 自己造的类型在开光影时会被整个跳过）。
 *
 * <p>原版这一批类型里，同时满足下面三条的只有 {@code energy_swirl}
 * （僵尸围城时的"能量漩涡"用的就是它）：
 * <ul>
 *   <li><b>加色混合</b>（{@code ADDITIVE_TRANSPARENCY}）：光锥必须靠"叠加变亮"才像光，
 *       普通 alpha 混合只会叠成一层灰雾</li>
 *   <li><b>不剔除背面</b>（{@code NO_CULL}）：第一人称时人是站在光锥内部的</li>
 *   <li><b>只写颜色不写深度</b>（{@code COLOR_WRITE}）：自身各层不互相遮挡，但仍会被墙挡住</li>
 * </ul>
 * 另一个原版加色类型 {@code RenderType.eyes} 不行：它剔背面，而且着色器里带着
 * {@code if (color.a < 0.1) discard;}，会把柔和的外缘切出硬边。
 * {@code energy_swirl} 也有这句 discard，所以顶点的透明度必须给满 1.0、
 * 亮度只能写在颜色里（详见 {@link BeamRenderer}）。
 *
 * <p>必须缓存成静态实例：{@code RenderType.energySwirl(...)} 每次调用都会新建一个对象，
 * 而 {@code BufferSource} 是拿对象身份当 key 的。若画的时候和 {@code endBatch} 的时候
 * 拿到的不是同一个实例，缓冲就永远刷不出去。
 */
public final class BeamRenderTypes {
    public static final ResourceLocation BEAM_TEXTURE =
            new ResourceLocation(HatMod.MOD_ID, "textures/particle/beam.png");
    /** 轴线贴片用的圆形光斑。 */
    public static final ResourceLocation BEAM_CORE_TEXTURE =
            new ResourceLocation(HatMod.MOD_ID, "textures/particle/beam_core.png");

    private static final RenderType BEAM = RenderType.energySwirl(BEAM_TEXTURE, 0.0F, 0.0F);
    private static final RenderType BEAM_CORE = RenderType.energySwirl(BEAM_CORE_TEXTURE, 0.0F, 0.0F);

    private BeamRenderTypes() {
    }

    /** 光锥本体：多层圆台叠出来的体积光。 */
    public static RenderType beam() {
        return BEAM;
    }

    /** 沿轴线的贴片：保证顺着光柱看时中心也有亮芯。 */
    public static RenderType beamCore() {
        return BEAM_CORE;
    }
}
