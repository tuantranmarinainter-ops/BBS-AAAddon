package diobede.bbsaaa.autotest;

import diobede.bbsaaa.forms.AAAParticleForm;
import diobede.bbsaaa.forms.renderers.AAAParticleFormRenderer;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.repos.IRepository;
import mchorse.bbs_mod.ui.ContentType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only automated in-game check (enabled with -Dbbsaaa.autotest). Creates a world, plays films with an
 * AAA particle actor through the real play paths, captures screenshots at fixed ticks, toggles the animated
 * paused/restart/speed tracks, logs emitter state and quits. Never active in normal play.
 */
public final class AutoTest
{
    private record Scenario(String name, String effect, boolean serverPath, boolean actor, boolean keyframes) {}

    private static final List<Scenario> SCENARIOS = List.of(
        new Scenario("laser_client", "laser01", false, false, false),
        new Scenario("laser_server", "laser01", true, false, false),
        new Scenario("laser_server_actor", "laser01", true, true, false),
        new Scenario("sword_server", "simple_ribbon_sword", true, false, false),
        new Scenario("ring_server", "simple_ring_shape1", true, false, false),
        new Scenario("laser_keyframes", "laser01", true, false, true));
    private static final int[] CAPTURES = {10, 30, 60, 90};
    private static final int SCENARIO_TICKS = 110;

    private static int ticks;
    private static int stage;
    private static int scenarioIndex;
    private static int scenarioTick;
    private static Vec3d origin;
    private static final List<String> log = new ArrayList<>();

    private AutoTest()
    {}

    public static void install()
    {
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
        note("autotest installed");
    }

    private static void note(String line)
    {
        log.add(line);
        System.out.println("[bbsaaa-autotest] " + line);
    }

    private static void tick(MinecraftClient client)
    {
        ticks++;

        try
        {
            if (stage == 0 && ticks % 100 == 0)
            {
                note("waiting, screen=" + (client.currentScreen == null ? "null" : client.currentScreen.getClass().getName()));
            }

            if (stage == 0 && client.world == null && ticks > 120 && client.getOverlay() == null)
            {
                String name = "autotest-" + System.currentTimeMillis();
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(15L, false, false), WorldPresets::createDemoOptions);
                note("creating world " + name);
                stage = 1;
                ticks = 0;
            }
            else if (stage == 1 && client.world != null && client.player != null && ticks > 200)
            {
                client.player.networkHandler.sendChatCommand("time set noon");
                client.player.networkHandler.sendChatCommand("weather clear");
                client.player.networkHandler.sendChatCommand("gamerule doDaylightCycle false");
                client.player.getAbilities().flying = true;
                client.options.hudHidden = true;
                client.player.setPitch(15F);
                client.player.setYaw(0F);
                origin = client.player.getPos();
                note("world ready at " + origin);
                stage = 2;
                ticks = 0;
            }
            else if (stage == 2 && ticks > 40)
            {
                capture(client, "baseline");
                startScenario(client);
                stage = 3;
            }
            else if (stage == 3)
            {
                runScenario(client);
            }
        }
        catch (Throwable error)
        {
            note("ERROR " + error);
            for (StackTraceElement element : error.getStackTrace())
            {
                note("  at " + element);
            }
            finish(client);
        }
    }

    private static Film buildFilm(Scenario scenario)
    {
        Film film = new Film();
        Replay replay = film.replays.addReplay();
        AAAParticleForm form = new AAAParticleForm();

        form.effect.set(new Link(Link.ASSETS, "effeks/samples/" + scenario.effect() + ".efkefc"));
        form.loop.set(true);
        replay.form.set(form);
        replay.actor.set(scenario.actor());

        /* Six blocks in front of the camera, which looks along +Z (yaw 0). */
        double x = origin.x, y = origin.y + 0.5, z = origin.z + 6;
        replay.keyframes.x.insert(0, x);
        replay.keyframes.y.insert(0, y);
        replay.keyframes.z.insert(0, z);
        /* A film lasts as long as its last keyframe: hold the position for 200 ticks. */
        replay.keyframes.x.insert(200, x);
        replay.keyframes.y.insert(200, y);
        replay.keyframes.z.insert(200, z);

        if (scenario.keyframes())
        {
            KeyframeChannel paused = replay.properties.getOrCreate(form, "paused");
            paused.insert(0, false);
            paused.insert(30, true);
            paused.insert(50, false);
            KeyframeChannel restart = replay.properties.getOrCreate(form, "restart");
            restart.insert(0, false);
            restart.insert(60, true);
            restart.insert(62, false);
            KeyframeChannel speed = replay.properties.getOrCreate(form, "speed");
            speed.insert(0, 1F);
            speed.insert(70, 2F);
        }

        return film;
    }

    private static void startScenario(MinecraftClient client)
    {
        Scenario scenario = SCENARIOS.get(scenarioIndex);
        Film film = buildFilm(scenario);
        String id = "autotest_" + scenario.name();

        scenarioTick = 0;

        if (scenario.serverPath())
        {
            /* The dashboard play button path: save to the film repository, then toggle on the integrated server. */
            IRepository<Film> repository = (IRepository<Film>) (IRepository<?>) ContentType.FILMS.getRepository();
            repository.save(id, film.toData().asMap());
            note("scenario " + scenario.name() + ": saved film " + id + ", playing via server");
        }
        else
        {
            film.setId(id);
            Films.playFilm(film, false);
            note("scenario " + scenario.name() + ": playing client-local film");
        }
    }

    private static void runScenario(MinecraftClient client)
    {
        Scenario scenario = SCENARIOS.get(scenarioIndex);
        String id = "autotest_" + scenario.name();

        scenarioTick++;

        if (scenario.serverPath() && scenarioTick == 5)
        {
            Films.playFilm(id, false);
        }

        int filmTick = scenario.serverPath() ? scenarioTick - 5 : scenarioTick;

        for (int capture : CAPTURES)
        {
            if (filmTick == capture)
            {
                capture(client, scenario.name() + "_t" + capture);
            }
        }

        if (scenarioTick >= SCENARIO_TICKS)
        {
            boolean playing = BBSModClient.getFilms().has(id);
            note("scenario " + scenario.name() + " end: film active=" + playing + " controllers=" + BBSModClient.getFilms().getControllers().size());
            Films.stopFilm(id);
            scenarioIndex++;

            if (scenarioIndex >= SCENARIOS.size())
            {
                finish(client);
            }
            else
            {
                startScenario(client);
            }
        }
    }

    private static void capture(MinecraftClient client, String name)
    {
        boolean emitters = AAAParticleFormRenderer.hasWorldEmitters();
        int controllers = BBSModClient.getFilms().getControllers().size();

        ScreenshotRecorder.saveScreenshot(client.runDirectory, "autotest_" + name + ".png", client.getFramebuffer(), (message) -> {});
        note("capture " + name + " worldEmitters=" + emitters + " filmControllers=" + controllers);
    }

    private static void finish(MinecraftClient client)
    {
        stage = 99;

        try
        {
            File out = new File(client.runDirectory, "autotest-report.txt");
            Files.writeString(out.toPath(), String.join("\n", log) + "\n", StandardCharsets.UTF_8);
        }
        catch (Exception ignored)
        {}

        client.scheduleStop();
    }
}
