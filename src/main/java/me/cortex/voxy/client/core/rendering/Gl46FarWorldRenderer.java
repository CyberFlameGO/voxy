package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.mixin.joml.AccessFrustumIntersection;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL42.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glClearNamedBufferData;
import static org.lwjgl.opengl.GL45C.nglClearNamedBufferData;

public class Gl46FarWorldRenderer extends AbstractFarWorldRenderer<Gl46Viewport> {
    private final Shader commandGen = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
            .compile();

    private final Shader lodShader = Shader.make()
            .add(ShaderType.VERTEX, "voxy:lod/gl46/quads2.vert")
            .add(ShaderType.FRAGMENT, "voxy:lod/gl46/quads.frag")
            .compile();


    //TODO: Note the cull shader needs a different element array since its rastering cubes not quads
    private final Shader cullShader = Shader.make()
            .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
            .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
            .compile();

    private final GlBuffer glCommandBuffer;
    private final GlBuffer glCommandCountBuffer;

    public Gl46FarWorldRenderer(int geometryBuffer, int maxSections) {
        super(geometryBuffer, maxSections);
        this.glCommandBuffer = new GlBuffer(maxSections*5L*4 * 6);
        this.glCommandCountBuffer = new GlBuffer(1024);
        glClearNamedBufferData(this.glCommandBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[1]);
        glClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[1]);
    }

    protected void bindResources(Gl46Viewport viewport) {
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometry.geometryId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.glCommandBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.glCommandCountBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, this.geometry.metaId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.visibilityBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, this.models.getBufferId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, this.models.getColourBufferId());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, this.lightDataBuffer.id);//Lighting LUT
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.glCommandBuffer.id);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, this.glCommandCountBuffer.id);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());

        //Bind the texture atlas
        glBindSampler(0, this.models.getSamplerId());
        glBindTextureUnit(0, this.models.getTextureId());
    }

    //FIXME: dont do something like this as it breaks multiviewport mods
    // the issue is that voxy expects the counter to be incremented by one each frame (the compute shader generating the commands)
    // checks `frameId - 1`, this means in a multiviewport, effectivly only the stuff that was marked visible by the last viewport
    // would be visible in the current viewport (if there are 2 viewports)

    //To fix the issue, need to make a viewport api, that independently tracks the frameId and has its own glVisibilityBuffer buffer
    private void updateUniformBuffer(Gl46Viewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, this.uniformBuffer.size());

        var mat = new Matrix4f(viewport.projection).mul(viewport.modelView);
        var innerTranslation = new Vector3f((float) (viewport.cameraX-(this.sx<<5)), (float) (viewport.cameraY-(this.sy<<5)), (float) (viewport.cameraZ-(this.sz<<5)));
        mat.translate(-innerTranslation.x, -innerTranslation.y, -innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;
        MemoryUtil.memPutInt(ptr, this.sx); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.sy); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.sz); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.geometry.getSectionCount()); ptr += 4;
        var planes = ((AccessFrustumIntersection)this.frustum).getPlanes();
        for (var plane : planes) {
            plane.getToAddress(ptr); ptr += 4*4;
        }
        innerTranslation.getToAddress(ptr); ptr += 4*3;
        MemoryUtil.memPutInt(ptr, viewport.frameId++); ptr += 4;
    }

    private int aaa;
    private int bbb;
    public void renderFarAwayOpaque(Gl46Viewport viewport) {
        if (this.geometry.getSectionCount() == 0) {
            return;
        }
        /*
        {//Mark all of the updated sections as being visible from last frame
            for (int id : this.updatedSectionIds) {
                long ptr = UploadStream.INSTANCE.upload(viewport.visibilityBuffer, id * 4L, 4);
                MemoryUtil.memPutInt(ptr, viewport.frameId - 1);//(visible from last frame)
            }
        }

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);


        //this.models.bakery.renderFaces(Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, 1), 1234, true);
        //this.models.bakery.renderFaces(Blocks.CHEST.getDefaultState(), 1234, false);


        glMemoryBarrier(-1);
        glFinish();
        RenderLayer.getCutoutMipped().startDrawing();
        glMemoryBarrier(-1);
        glFinish();
        //RenderSystem.enableBlend();
        //RenderSystem.defaultBlendFunc();

        glMemoryBarrier(-1);
        glFinish();
        nglClearNamedBufferData(this.glCommandBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
        nglClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.callocInt(4);
            //nglClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, MemoryUtil.memAddress(buf));
            //glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
        //glClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[1]);
        //glClearNamedBufferData(this.glCommandBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[1]);
        glMemoryBarrier(-1);
        glFinish();
        this.updateUniformBuffer(viewport);
        glMemoryBarrier(-1);
        glFinish();
        UploadStream.INSTANCE.commit();
        glBindVertexArray(AbstractFarWorldRenderer.STATIC_VAO);
        glMemoryBarrier(-1);
        glFinish();

        //glClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[1]);

        glMemoryBarrier(-1);
        glFinish();
        this.commandGen.bind();
        glMemoryBarrier(-1);
        glFinish();
        this.bindResources(viewport);
        glMemoryBarrier(-1);
        glFinish();
        //glDispatchCompute((this.geometry.getSectionCount()+127)/128, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT);


        glMemoryBarrier(-1);
        glFinish();
        glMemoryBarrier(-1);
        glFinish();
        this.lodShader.bind();
        glMemoryBarrier(-1);
        glFinish();
        this.bindResources(viewport);
        glMemoryBarrier(-1);
        glFinish();
        glDisable(GL_CULL_FACE);
        glMemoryBarrier(-1);
        glFinish();
        //glPointSize(10);
        //TODO: replace glMultiDrawElementsIndirectCountARB with glMultiDrawElementsIndirect on intel gpus, since it performs so much better
        glFinish();
        //glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 12000, 0);
        //glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 0, (int) (this.geometry.getSectionCount()*4.4), 0);
        glMemoryBarrier(-1);
        glFinish();
        glEnable(GL_CULL_FACE);
        //System.out.println(aaa);





        glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);
        glMemoryBarrier(-1);
        glFinish();

        this.cullShader.bind();
        glMemoryBarrier(-1);
        glFinish();
        this.bindResources(viewport);
        glMemoryBarrier(-1);
        glFinish();

        glColorMask(false, false, false, false);
        glDepthMask(false);

        glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3, GL_UNSIGNED_BYTE, (1 << 16) * 6 * 2, this.geometry.getSectionCount());
        glMemoryBarrier(-1);
        glFinish();

        glDepthMask(true);
        glColorMask(true, true, true, true);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glMemoryBarrier(-1);
        glFinish();


        //TODO: need to do temporal rasterization here

        glBindVertexArray(0);
        glMemoryBarrier(-1);
        glFinish();
        glBindSampler(0, 0);
        glMemoryBarrier(-1);
        glFinish();
        glBindTextureUnit(0, 0);
        glMemoryBarrier(-1);
        glFinish();
        RenderLayer.getCutoutMipped().endDrawing();
        glMemoryBarrier(-1);
        glFinish();

        glBindBufferBase(GL_UNIFORM_BUFFER, 0, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, 0);//Lighting LUT
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        //Bind the texture atlas
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);

         */
        glMemoryBarrier(-1);
        glFinish();

        nglClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.callocInt(4);
            nglClearNamedBufferData(this.glCommandCountBuffer.id, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, MemoryUtil.memAddress(buf));
            //glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
        glMemoryBarrier(-1);
        glFinish();
        DownloadStream.INSTANCE.download(this.glCommandCountBuffer, 0, 4, (ptr, siz) -> {
            int cnt = MemoryUtil.memGetInt(ptr);
            aaa = cnt;
        });
        glMemoryBarrier(-1);
        glFinish();
        DownloadStream.INSTANCE.commit();
        glMemoryBarrier(-1);
        glFinish();
        DownloadStream.INSTANCE.tick();
        glFinish();
        System.out.println(aaa);
    }

    @Override
    public void renderFarAwayTranslucent(Gl46Viewport viewport) {
        /*
        RenderLayer.getTranslucent().startDrawing();
        glBindVertexArray(AbstractFarWorldRenderer.STATIC_VAO);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);

        //TODO: maybe change this so the alpha isnt applied in the same way or something?? since atm the texture bakery uses a very hacky
        // blend equation to make it avoid double applying translucency
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);


        glBindSampler(0, this.models.getSamplerId());
        glBindTextureUnit(0, this.models.getTextureId());

        //RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        this.lodShader.bind();
        this.bindResources(viewport);

        glDepthMask(false);
        //glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, 400_000 * 4 * 5, 4, this.geometry.getSectionCount(), 0);
        glDepthMask(true);

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);


        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glDisable(GL_BLEND);

        RenderLayer.getTranslucent().endDrawing();

        glBindBufferBase(GL_UNIFORM_BUFFER, 0, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, 0);//Lighting LUT
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        //Bind the texture atlas
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glMemoryBarrier(-1);
        glFinish();

         */
    }

    protected Gl46Viewport createViewport0() {
        return new Gl46Viewport(this);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.commandGen.free();
        this.lodShader.free();
        this.cullShader.free();
        this.glCommandBuffer.free();
        this.glCommandCountBuffer.free();
    }
}
