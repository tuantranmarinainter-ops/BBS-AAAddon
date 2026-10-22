package diobede.bbsaaa;

import diobede.bbsaaa.forms.AAAParticleForm;
import diobede.bbsaaa.forms.renderers.AAAParticleFormRenderer;
import diobede.bbsaaa.forms.renderers.BBSEffectLoader;
import diobede.bbsaaa.ui.forms.editors.forms.UIAAAParticleForm;
import diobede.bbsaaa.forms.AAAParticleSection;
import diobede.bbsaaa.particles.FilmPlaybackTracker;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.RegisterFormEditorsEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormRenderersEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormSectionsEvent;
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
import mchorse.bbs_mod.api.client.events.BBSClientReadyEvent;
import mchorse.bbs_mod.api.client.events.RegisterTrackStylesEvent;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.List;

public class BbsAAClientAddon implements BBSAddonMod
{
    /* Animatable properties that are specific to the AAA particle form */
    private static final String[] TRACKS = {"paused", "loop", "speed", "restart", "particleScale"};

    /* BBS FS: BBSAddonMod is an empty marker without lifecycle hooks, so Fabric client
     * events are registered when the bbs-client-addon entrypoint is constructed. */
    public BbsAAClientAddon()
    {
        ClientTickEvents.END_CLIENT_TICK.register((client) -> BbsAAClientAddon.tickCleanup());
        /* DISCONNECT fires on the Netty IO thread; stopping native Effekseer emitters there
         * calls OpenGL without a context and aborts the JVM. Run the cleanup on the client thread. */
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BbsAAClientAddon::cleanup));

        if (System.getProperty("bbsaaa.autotest") != null)
        {
            if ("films".equals(System.getProperty("bbsaaa.autotest")))
            {
                diobede.bbsaaa.autotest.FilmShowcase.install();
            }
            else
            {
                diobede.bbsaaa.autotest.AutoTest.install();
            }
        }
    }

    @Subscribe
    public void ready(BBSClientReadyEvent event)
    {
        FilmPlaybackTracker.initialize();
    }

    private static void cleanup()
    {
        AAAParticleFormRenderer.cleanupAll();
        BBSEffectLoader.clearCache();
        FilmPlaybackTracker.clear();
    }

    private static void tickCleanup()
    {
        if (BBSEffectLoader.beginReload())
        {
            try
            {
                AAAParticleFormRenderer.cleanupAll();
                BBSEffectLoader.clearCache();
            }
            finally
            {
                BBSEffectLoader.endReload();
            }
        }

        AAAParticleFormRenderer.checkActiveRenderers();
    }

    @Subscribe
    public void languages(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> List.of(
            Link.create(BBSAAaddon.MOD_ID + ":strings/" + L10n.DEFAULT_LANGUAGE + ".json"),
            Link.create(BBSAAaddon.MOD_ID + ":strings/" + lang + ".json")
        ));
    }

    @Subscribe
    public void sections(RegisterFormSectionsEvent event)
    {
        event.register(AAAParticleSection::new);
    }

    @Subscribe
    public void renderers(RegisterFormRenderersEvent event)
    {
        event.register(AAAParticleForm.class, AAAParticleFormRenderer::new);
    }

    @Subscribe
    public void editors(RegisterFormEditorsEvent event)
    {
        event.register(AAAParticleForm.class, UIAAAParticleForm::new);
    }

    @Subscribe
    public void tracks(RegisterTrackStylesEvent event)
    {
        /* Smooth gradient from #d00093 to #00ef77 */
        int start = 0xD00093;
        int end = 0x00EF77;

        for (int i = 0; i < TRACKS.length; i++)
        {
            float ratio = (float) i / (TRACKS.length - 1);
            int r = (int) (((start >> 16) & 0xFF) + ratio * (((end >> 16) & 0xFF) - ((start >> 16) & 0xFF)));
            int g = (int) (((start >> 8) & 0xFF) + ratio * (((end >> 8) & 0xFF) - ((start >> 8) & 0xFF)));
            int b = (int) ((start & 0xFF) + ratio * ((end & 0xFF) - (start & 0xFF)));

            event.register(TRACKS[i], Icons.PARTICLE, (r << 16) | (g << 8) | b, L10n.lang("bbsaaa.track." + TRACKS[i]));
        }
    }
}
