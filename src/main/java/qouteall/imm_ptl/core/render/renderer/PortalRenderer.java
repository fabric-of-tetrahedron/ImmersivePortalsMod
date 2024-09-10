package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class PortalRenderer {

    /**
     * An event for filtering whether a portal should render.
     * All listeners' results are ANDed.
     */
    public static final Event<Predicate<Portal>> PORTAL_RENDERING_PREDICATE =
        EventFactory.createArrayBacked(
            Predicate.class,
            (listeners) -> (portal) -> {
                for (Predicate<Portal> listener : listeners) {
                    if (!listener.test(portal)) {
                        return false;
                    }
                }
                return true;
            }
        );

    public static final Minecraft client = Minecraft.getInstance();

    public abstract void onBeforeTranslucentRendering(Matrix4f modelView);

    public abstract void onAfterTranslucentRendering(Matrix4f modelView);

    // will be called when rendering portal
    public abstract void onHandRenderingEnded();

    // will be called when rendering portal
    public void onBeforeHandRendering(Matrix4f modelView) {}

    // this will NOT be called when rendering portal
    public abstract void prepareRendering();

    // this will NOT be called when rendering portal
    public abstract void finishRendering();

    // this will be called when rendering portal entities
    public abstract void renderPortalInEntityRenderer(Portal portal);

    // return true to skip framebuffer clear
    // this will also be called in outer world rendering
    public abstract boolean replaceFrameBufferClearing();

    protected List<Portal> getPortalsToRender(Matrix4f modelView) {
        Supplier<Frustum> frustumSupplier = Helper.cached(() -> {
            Frustum frustum = new Frustum(
                modelView,
                RenderSystem.getProjectionMatrix()
            );

            Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();
            frustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);

            return frustum;
        });

        ObjectArrayList<Portal> renderables = new ObjectArrayList<>();

        ClientLevel world = client.level;
        assert world != null;
        List<Portal> globalPortals = GlobalPortalStorage.getGlobalPortals(world);
        for (Portal globalPortal : globalPortals) {
            if (!shouldSkipRenderingPortal(globalPortal, frustumSupplier)) {
                renderables.add(globalPortal);
            }
        }

        world.entitiesForRendering().forEach(e -> {
            if (e instanceof Portal portal) {
                if (!shouldSkipRenderingPortal(portal, frustumSupplier)) {
                    renderables.add(portal);
                }
            }
        });

        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        renderables.sort(Comparator.comparingDouble(
            e -> e.getDistanceToNearestPointInPortal(cameraPos)
        ));
        return renderables;
    }

    private static boolean shouldSkipRenderingPortal(Portal portal, Supplier<Frustum> frustumSupplier) {
//        System.out.println("开始检查是否应跳过渲染传送门: " + portal);

        // 检查传送门是否有效
        if (!portal.isPortalValid()) {
//            System.out.println("传送门无效，跳过渲染");
            return true;
        }

        // 如果最大传送门层数为0，则强制渲染不可见的传送门
        // 否则，跳过不可见的传送门
        if (!portal.isVisible() && IPGlobal.maxPortalLayer != 0) {
//            System.out.println("传送门不可见且最大层数不为0，跳过渲染");
            return true;
        }

        // 检查是否已达到传送门渲染数量限制
        if (RenderStates.getRenderedPortalNum() >= IPGlobal.portalRenderLimit) {
//            System.out.println("已达到传送门渲染数量限制，跳过渲染");
            return true;
        }

        // 获取经过等距调整的相机位置
        Vec3 cameraPos = TransformationManager.getIsometricAdjustedCameraPos();
//        System.out.println("调整后的相机位置: " + cameraPos);

        // 检查传送门是否大致可见
        if (!portal.isRoughlyVisibleTo(cameraPos)) {
//            System.out.println("传送门大致不可见，跳过渲染");
            if (!IPGlobal.doNotCullPortalsBehindPlayerInOrthographicProjection) return true;
        }

        // 处理嵌套传送门的情况
        if (PortalRendering.isRendering()) {
            Portal outerPortal = PortalRendering.getRenderingPortal();
//            System.out.println("正在渲染嵌套传送门，外层传送门: " + outerPortal);

            // 检查当前传送门是否可以在外层传送门中渲染
            if (outerPortal.cannotRenderInMe(portal)) {
//                System.out.println("当前传送门不能在外层传送门中渲染，跳过渲染");
                return true;
            }
        }

        // 计算相机到传送门最近点的距离
        double distance = portal.getDistanceToNearestPointInPortal(cameraPos);
//        System.out.println("相机到传送门最近点的距离: " + distance);

        // 检查是否超出渲染范围
        if (distance > getRenderRange()) {
//            System.out.println("传送门超出渲染范围，跳过渲染");
            return true;
        }

        // 执行早期视锥体剔除
        if (IPCGlobal.earlyFrustumCullingPortal) {
//            System.out.println("执行早期视锥体剔除");
            // 当传送门非常接近时，视锥体剔除不起作用
            if (distance > 0.1) {
                Frustum frustum = frustumSupplier.get();
                // 检查传送门的边界框是否在视锥体内
                if (!frustum.isVisible(portal.getThinBoundingBox())) {
                    System.out.println("传送门不在视锥体内，跳过渲染");
//                    if (!IPGlobal.doNotCullPortalsBehindPlayerInOrthographicProjection)
                        return true;

                }
            }
        }

        // 检查是否为无效的递归渲染
        if (PortalRendering.isInvalidRecursionRendering(portal)) {
//            System.out.println("检测到无效的递归渲染，跳过渲染");
            return true;
        }

        // 执行自定义的传送门渲染谓词测试
        boolean predicateTest = PORTAL_RENDERING_PREDICATE.invoker().test(portal);
        if (!predicateTest) {
//            System.out.println("自定义传送门渲染谓词测试失败，跳过渲染");
            return true;
        }

        // 如果所有检查都通过，则不跳过渲染
//        System.out.println("所有检查通过，将渲染传送门");
        return false;
    }

    public static double getRenderRange() {
        double range = client.options.getEffectiveRenderDistance() * 16;
        if (RenderStates.isLaggy || IPGlobal.reducedPortalRendering) {
            range = 16;
        }
        if (PortalRendering.getPortalLayer() > 1) {
            //do not render deep layers of mirror when far away
            range /= (PortalRendering.getPortalLayer());
        }
        if (PortalRendering.getPortalLayer() >= 1) {
            double outerPortalScale = PortalRendering.getRenderingPortal().getScale();
            if (outerPortalScale > 2) {
                range *= outerPortalScale;
                range = Math.min(range, 32 * 16);
            }
        }
        return range;
    }

    protected final void renderPortalContent(
        Portal portal
    ) {
        if (PortalRendering.getPortalLayer() > PortalRendering.getMaxPortalLayer()) {
            return;
        }

        ClientLevel newWorld = ClientWorldLoader.getWorld(portal.getDestDim());

        PortalRendering.onBeginPortalWorldRendering();

        int renderDistance = getPortalRenderDistance(portal);

        invokeWorldRendering(
            new WorldRenderInfo.Builder()
                .setWorld(newWorld)
                .setCameraPos(PortalRendering.getRenderingCameraPos())
                .setCameraTransformation(portal.getAdditionalCameraTransformation())
                .setOverwriteCameraTransformation(false)
                .setDescription(portal.getDiscriminator())
                .setRenderDistance(renderDistance)
                .setDoRenderHand(false)
                .setEnableViewBobbing(true)
                .setDoRenderSky(!portal.isFuseView())
                .build()
        );

        PortalRendering.onEndPortalWorldRendering();

        GlStateManager._enableDepthTest();

        MyRenderHelper.restoreViewPort();


    }

    private static int getPortalRenderDistance(Portal portal) {
        int mcRenderDistance = client.options.getEffectiveRenderDistance();

        if (portal.getScale() > 2) {
            double radiusBlocks = portal.getDestAreaRadiusEstimation() * 1.4;

            radiusBlocks = Math.min(radiusBlocks, 32 * 16);

            return Math.max((int) (radiusBlocks / 16), mcRenderDistance);
        }
        if (IPGlobal.reducedPortalRendering) {
            return mcRenderDistance / 3;
        }
        return mcRenderDistance;
    }

    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }

    @Nullable
    public static Matrix4f getPortalTransformation(Portal portal) {
        Matrix4f rot = getPortalRotationMatrix(portal);

        Matrix4f mirror = portal instanceof Mirror ?
            TransformationManager.getMirrorTransformation(portal.getNormal()) : null;

        Matrix4f scale = getPortalScaleMatrix(portal);

        return combineNullable(rot, combineNullable(mirror, scale));
    }

    @Nullable
    public static Matrix4f getPortalRotationMatrix(Portal portal) {
        if (portal.getRotation() == null) {
            return null;
        }

        Quaternionf rot = portal.getRotation().toMcQuaternion();
        rot.conjugate();
        return rot.get(new Matrix4f());
    }

    @Nullable
    public static Matrix4f combineNullable(@Nullable Matrix4f a, @Nullable Matrix4f b) {
        return Helper.combineNullable(a, b, (m1, m2) -> {
            m1.mul(m2);
            return m1;
        });
    }

    @Nullable
    public static Matrix4f getPortalScaleMatrix(Portal portal) {
        // if it's not a fuseView portal
        // whether to apply scale transformation to camera does not change triangle position
        // to avoid abrupt fog change, do not apply for non-fuse-view portal
        // for fuse-view portal, the depth value should be correct so the scale should be applied
        if (shouldApplyScaleToModelView(portal)) {
            float v = (float) (1.0 / portal.getScale());
            return new Matrix4f().scale(v, v, v);
        }
        return null;
    }

    public static boolean shouldApplyScaleToModelView(Portal portal) {
        return portal.hasScaling() && portal.isFuseView();
    }

    public void onBeginIrisTranslucentRendering(Matrix4f modelView) {}

    private static boolean fabulousWarned = false;

    public static void switchToCorrectRenderer() {
        if (PortalRendering.isRendering()) {
            //do not switch when rendering
            return;
        }

        if (Minecraft.getInstance().options.graphicsMode().get() == GraphicsStatus.FABULOUS) {
            if (!fabulousWarned) {
                fabulousWarned = true;
                CHelper.printChat(Component.translatable("imm_ptl.fabulous_warning"));
            }
        }

        IPModInfoChecking.checkShaderpack();

        if (IrisInterface.invoker.isIrisPresent()) {
            if (IrisInterface.invoker.isShaders()) {
                if (IPCGlobal.experimentalIrisPortalRenderer) {
                    switchRenderer(ExperimentalIrisPortalRenderer.instance);
                    return;
                }

                switch (IPGlobal.renderMode) {
                    case normal -> switchRenderer(IrisPortalRenderer.instance);
                    case compatibility -> switchRenderer(IrisCompatibilityPortalRenderer.instance);
                    case debug -> switchRenderer(IrisCompatibilityPortalRenderer.debugModeInstance);
                    case none -> switchRenderer(IPCGlobal.rendererDummy);
                }
                return;
            }
        }

        switch (IPGlobal.renderMode) {
            case normal -> switchRenderer(IPCGlobal.rendererUsingStencil);
            case compatibility -> switchRenderer(IPCGlobal.rendererUsingFrameBuffer);
            case debug -> switchRenderer(IPCGlobal.rendererDebug);
            case none -> switchRenderer(IPCGlobal.rendererDummy);
        }
    }

    private static void switchRenderer(PortalRenderer renderer) {
        if (IPCGlobal.renderer != renderer) {
            Helper.log("switched to renderer " + renderer.getClass());
            IPCGlobal.renderer = renderer;

            if (IrisInterface.invoker.isShaders()) {
                IrisInterface.invoker.reloadPipelines();
            }
        }
    }
}
