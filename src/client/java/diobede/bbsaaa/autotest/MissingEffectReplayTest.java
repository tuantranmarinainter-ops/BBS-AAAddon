package diobede.bbsaaa.autotest;

import diobede.bbsaaa.forms.AAAParticleForm;
import diobede.bbsaaa.forms.renderers.AAAParticleFormRenderer;
import diobede.bbsaaa.forms.renderers.BBSEffectLoader;
import diobede.bbsaaa.particles.ParticleEffectIds;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import mod.chloeprime.aaaparticles.client.loader.EffekAssetLoader;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in regression adapted from fs-2.7-autotest's world/tick harness.
 * Enable only in a disposable dev client with -Dbbsaaa.autotest=missing-effect.
 * No changes to a user's existing worlds, films or asset files.
 */
public final class MissingEffectReplayTest
{
    private static int ticks;
    private static int stage;
    private static UIFilmPanel panel;
    private static Replay replay;
    private static AAAParticleForm form;
    private static boolean previousOrbitSetting;
    private static boolean changedOrbitSetting;

    private MissingEffectReplayTest()
    {}

    public static void install()
    {
        try
        {
            Files.writeString(Path.of(MinecraftClient.getInstance().runDirectory.getPath(), "aaa-missing-effect-report.txt"),
                "RUNNING: awaiting world and replay selection regression\n");
        }
        catch (java.io.IOException error)
        {
            throw new IllegalStateException("Cannot initialize regression report", error);
        }
        ClientTickEvents.END_CLIENT_TICK.register(MissingEffectReplayTest::tick);
    }

    private static void tick(MinecraftClient client)
    {
        if (stage == 99)
        {
            return;
        }
        ticks++;
        try
        {
            if (ticks > 2400)
            {
                throw new AssertionError("Timed out waiting for missing-effect regression");
            }
            if (stage == 0 && client.world == null && ticks > 120 && client.getOverlay() == null)
            {
                String name = "aaa-missing-effect-" + System.currentTimeMillis();
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(name, info,
                    new GeneratorOptions(15L, false, false), WorldPresets::createDemoOptions);
                stage = 1;
                ticks = 0;
            }
            else if (stage == 1 && client.world != null && client.player != null && client.getOverlay() == null && ticks > 200)
            {
                require(BBSEffectLoader.canLoadExternalEffects(), "AAA native runtime must be available to exercise loading");
                require(EffekAssetLoader.get() != null, "AAA resource registry must be ready");
                Film film = new Film();
                film.setId("aaa_missing_effect_regression");
                replay = film.replays.addReplay();
                form = new AAAParticleForm();
                form.effect.set(new Link(Link.ASSETS, "effeks/__bbsaaa_missing__/does_not_exist.efkefc"));
                replay.form.set(form);
                replay.keyframes.x.insert(0, client.player.getX());
                replay.keyframes.y.insert(0, client.player.getY());
                replay.keyframes.z.insert(0, client.player.getZ() + 6);
                replay.keyframes.x.insert(200, client.player.getX());
                replay.keyframes.y.insert(200, client.player.getY());
                replay.keyframes.z.insert(200, client.player.getZ() + 6);
                require(FormUtilsClient.getRenderer(form) instanceof AAAParticleFormRenderer,
                    "AAA form renderer must be registered before resource lookup");
                previousOrbitSetting = BBSSettings.editorOrbitTeleportOnSwitch.get();
                changedOrbitSetting = true;
                BBSSettings.editorOrbitTeleportOnSwitch.set(true);
                panel = BBSModClient.getDashboard().getPanels().getPanel(UIFilmPanel.class);
                require(panel != null, "Film editor panel must exist");
                BBSModClient.getDashboard().getPanels().setPanel(panel);
                panel.fill(film);
                panel.showPanel(panel.replayEditor);
                panel.replayEditor.setReplay(null);
                panel.replayEditor.setReplay(replay);
                require(panel.replayEditor.getReplay() == replay, "Replay selection did not complete");
                stage = 2;
                ticks = 0;
            }
            else if (stage == 2)
            {
                // Repeat the actual selection/orbit path across client frames.
                panel.replayEditor.setReplay(null);
                panel.replayEditor.setReplay(replay);
                require(BBSEffectLoader.getOrLoad(ParticleEffectIds.fromLink(form.effect.get())) == null,
                    "Reserved missing fixture unexpectedly resolves to an effect");
                require(FormUtilsClient.getRenderer(form) instanceof AAAParticleFormRenderer,
                    "Missing resource must not remove the registered renderer");
                if (ticks >= 40)
                {
                    finish(client, "PASS: missing effect returns no definition; renderer registered; replay selected/orbit collected 40 frames", null);
                }
            }
        }
        catch (Exception | AssertionError error)
        {
            finish(client, "FAIL: " + error, error);
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void finish(MinecraftClient client, String result, Throwable error)
    {
        stage = 99;
        if (changedOrbitSetting)
        {
            BBSSettings.editorOrbitTeleportOnSwitch.set(previousOrbitSetting);
        }
        if (error != null)
        {
            error.printStackTrace();
        }
        System.out.println("[bbsaaa-missing-effect] " + result);
        try
        {
            Files.writeString(Path.of(client.runDirectory.getPath(), "aaa-missing-effect-report.txt"), result + "\n");
        }
        catch (java.io.IOException writeError)
        {
            writeError.printStackTrace();
        }
        client.scheduleStop();
    }
}
