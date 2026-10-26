package diobede.bbsaaa.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import diobede.bbsaaa.forms.AAAParticleForm;
import diobede.bbsaaa.BBSAAaddon;
import net.minecraft.util.Identifier;
import diobede.bbsaaa.particles.AAAPreviewRenderer;
import diobede.bbsaaa.particles.FilmPlaybackTracker;
import diobede.bbsaaa.particles.ParticleEffectIds;
import diobede.bbsaaa.particles.ParticlePlayback;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mod.chloeprime.aaaparticles.api.client.effekseer.ParticleEmitter;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Form transforms and drawing. ParticlePlayback owns native emitter state. */
public class AAAParticleFormRenderer extends FormRenderer<AAAParticleForm> implements ITickable
{
    public static final Link ICON = new Link("bbs-aaaddon", "textures/aaa_texture.png");
    private static final Set<AAAParticleFormRenderer> ACTIVE = new HashSet<>();
    private static final int MAX_MISSED_TICKS = 20;

    private final ParticlePlayback playback = new ParticlePlayback();
    private final AAAPreviewRenderer preview = new AAAPreviewRenderer();
    private IEntity entity;
    private boolean renderedSinceTick;
    private boolean allowsUnboundEntity;
    private int missedTicks;
    private boolean warnedInvalidEffect;

    public AAAParticleFormRenderer(AAAParticleForm form)
    {
        super(form);
    }

    public ParticleEmitter getEmitter()
    {
        return this.playback.getEmitter();
    }

    public void restartPreview()
    {
        this.playback.requestRestart();
    }

    /** Do not call exists(): AAA's before-hand and tail checks must agree even
     * when an emitter finishes during the draw between those two checks. */
    public static boolean hasWorldEmitters()
    {
        for (AAAParticleFormRenderer renderer : ACTIVE)
        {
            ParticleEmitter emitter = renderer.getEmitter();
            if (emitter != null && emitter.type == ParticleEmitter.Type.WORLD)
            {
                return true;
            }
        }
        return false;
    }

    public static void checkActiveRenderers()
    {
        for (AAAParticleFormRenderer renderer : List.copyOf(ACTIVE))
        {
            if (!renderer.allowsUnboundEntity && renderer.entity != null && renderer.entity.getForm() == null)
            {
                renderer.cleanup();
            }
            else if (renderer.renderedSinceTick)
            {
                renderer.missedTicks = 0;
            }
            else if (++renderer.missedTicks >= MAX_MISSED_TICKS)
            {
                renderer.cleanup();
            }

            renderer.renderedSinceTick = false;
        }
    }

    public static void cleanupAll()
    {
        for (AAAParticleFormRenderer renderer : List.copyOf(ACTIVE))
        {
            renderer.cleanup();
        }
    }

    public static void cleanupFilm(BaseFilmController film)
    {
        for (AAAParticleFormRenderer renderer : List.copyOf(ACTIVE))
        {
            if (film.getEntities().containsValue(renderer.entity))
            {
                renderer.cleanup();
            }
        }
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        Link image = this.form.preview.get();
        Texture texture = context.render.getTextures().getTexture(image == null ? ICON : image);

        if (texture == null || texture.width <= 0 || texture.height <= 0)
        {
            return;
        }

        float scale = Math.min((x2 - x1 - 4F) / texture.width, (y2 - y1 - 4F) / texture.height);
        int width = Math.max(0, (int) (texture.width * scale));
        int height = Math.max(0, (int) (texture.height * scale));
        context.batcher.fullTexturedBox(texture, x1 + (x2 - x1 - width) / 2,
            y1 + (y2 - y1 - height) / 2, width, height);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        // Shadow/picking passes must not advance, create, or destroy emitters.
        if (BBSRendering.isIrisShadowPass() || context.isPicking())
        {
            return;
        }

        boolean isPreview = context.type == FormRenderType.PREVIEW;
        if (context.entity == null)
        {
            this.cleanup();
            return;
        }
        Identifier effectId = ParticleEffectIds.fromLink(this.form.effect.get());
        if (effectId == null)
        {
            if (!this.warnedInvalidEffect)
            {
                this.warnedInvalidEffect = true;
                BBSAAaddon.LOGGER.warn("AAA form has no valid effect link; drawing no effect: {}", this.form.effect.get());
            }
            this.cleanup();
            return;
        }
        this.warnedInvalidEffect = false;

        this.entity = context.entity;
        this.allowsUnboundEntity = isPreview || context.type == FormRenderType.MODEL_BLOCK;
        this.playback.tick(context.entity.getAge(), this.form.restart.get());
        ParticleEmitter emitter = this.playback.update(this.form, effectId,
            isPreview, FilmPlaybackTracker.isPaused(context.entity), MinecraftClient.getInstance().isPaused());

        if (this.playback.getEmitter() != null)
        {
            // A completed non-looping effect is still owned by this visible form.
            // Do not let abandonment cleanup rearm it every second.
            this.renderedSinceTick = true;
            ACTIVE.add(this);
        }

        if (emitter == null)
        {
            return;
        }

        if (isPreview)
        {
            // AAA receives context.stack, which already contains CML's full
            // transform, including pivot and overlays. Keep the emitter local
            // to avoid applying translation, rotation and scale a second time.
            emitter.setPosition(0, 0, 0);
            emitter.setRotation(0, 0, 0);
            float scale = this.form.particleScale.get();
            emitter.setScale(scale, scale, scale);
        }
        else
        {
            this.applyWorldTransform(context, emitter);
        }

        this.playback.prepareDraw();

        if (isPreview)
        {
            this.preview.render(context);
        }
    }

    private void applyWorldTransform(FormRenderingContext context, ParticleEmitter emitter)
    {
        // FormRenderer already applied pivot, rotation and scale. Preserve its matrix
        // rather than decomposing Euler angles or applying the transform twice.
        Matrix4f matrix = new Matrix4f(RenderSystem.getInverseViewRotationMatrix());
        matrix.mul(context.stack.peek().getPositionMatrix());
        Vector3f position = matrix.getTranslation(new Vector3f());
        float scale = this.form.particleScale.get();
        emitter.setTransformMatrix(new float[] {
            matrix.m00() * scale, matrix.m10() * scale, matrix.m20() * scale, (float) (position.x + context.camera.position.x),
            matrix.m01() * scale, matrix.m11() * scale, matrix.m21() * scale, (float) (position.y + context.camera.position.y),
            matrix.m02() * scale, matrix.m12() * scale, matrix.m22() * scale, (float) (position.z + context.camera.position.z)
        });
    }

    @Override
    public void tick(IEntity entity)
    {
        if (entity != null)
        {
            this.playback.tick(entity.getAge(), this.form.restart.get());
        }
    }

    public void cleanup()
    {
        this.playback.stop();
        this.entity = null;
        this.renderedSinceTick = false;
        this.missedTicks = 0;
        ACTIVE.remove(this);
    }
}
