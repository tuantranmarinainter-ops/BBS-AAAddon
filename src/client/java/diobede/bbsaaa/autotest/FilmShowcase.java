package diobede.bbsaaa.autotest;

import diobede.bbsaaa.forms.AAAParticleForm;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.repos.IRepository;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only showcase (-Dbbsaaa.autotest=films -Dbbsaaa.films=<dir with *.dat>): installs App 2 film exports into a fresh
 * world, adds AAA particle replays (App 4 effects) to each film, plays them with their camera and saves one screenshot
 * per client tick so the frames can be encoded to a video. Never active in normal play.
 */
public final class FilmShowcase
{
    /** One AAA particle replay: effect, scene position, yaw, and optional start tick (paused + restart before it). */
    private record Effect(String effect, double x, double y, double z, double yaw, float scale, int startTick) {}
    private record Show(String film, int ticks, List<Effect> effects) {}

    private static final List<Show> SHOWS = List.of(
        new Show("e2e_aura", 100, List.of(
            new Effect("simple_ring_shape1", 0, 0.05, 0, 0, 1.2F, 0),
            new Effect("simple_sprite_billboard", 0, 1.2, 0, 0, 0.8F, 0))),
        new Show("e2e_kiem", 100, List.of(
            new Effect("simple_ribbon_sword", 40.4, 1.1, 0.4, 0, 0.6F, 0))),
        new Show("e2e_phep", 100, List.of(
            new Effect("simple_ring_shape1", 78, 0.05, 0, 0, 1F, 0),
            new Effect("laser01", 78.6, 1.3, 0, -90, 0.5F, 30))));

    private static int ticks;
    private static int stage;
    private static int showIndex;
    private static int showTick;
    private static final List<String> log = new ArrayList<>();

    private FilmShowcase()
    {}

    public static void install()
    {
        ClientTickEvents.END_CLIENT_TICK.register(FilmShowcase::tick);
        note("film showcase installed");
    }

    private static void note(String line)
    {
        log.add(line);
        System.out.println("[bbsaaa-showcase] " + line);
    }

    private static void command(MinecraftClient client, String command)
    {
        client.player.networkHandler.sendChatCommand(command);
    }

    private static void tick(MinecraftClient client)
    {
        ticks++;

        try
        {
            if (stage == 0 && client.world == null && ticks > 120 && client.getOverlay() == null)
            {
                client.getWindow().setWindowedSize(1280, 720);
                String name = "showcase-" + System.currentTimeMillis();
                LevelInfo info = new LevelInfo(name, GameMode.SPECTATOR, false, Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE);
                /* Superflat: the ground sits at y=-61, so the film stage around y=0 is open to the sky. */
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(15L, false, false),
                    (registries) -> registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.FLAT).value().createDimensionsRegistryHolder());
                note("creating world " + name);
                stage = 1;
                ticks = 0;
            }
            else if (stage == 1 && client.world != null && client.player != null && ticks > 100)
            {
                command(client, "time set noon");
                command(client, "weather clear");
                command(client, "gamerule doDaylightCycle false");
                command(client, "gamerule doWeatherCycle false");
                command(client, "tp @s 40 40 30");
                stage = 2;
                ticks = 0;
            }
            else if (stage == 2 && ticks == 60)
            {
                /* A flat stage around the scene origin: films use scene coordinates as world coordinates. */
                for (int x = -20; x <= 100; x += 24)
                {
                    int x2 = Math.min(x + 23, 100);
                    command(client, "fill " + x + " -1 -16 " + x2 + " -1 16 grass_block");
                    command(client, "fill " + x + " -3 -16 " + x2 + " -2 16 dirt");
                }
                installFilms(client);
            }
            else if (stage == 2 && ticks == 160)
            {
                client.options.hudHidden = true;
                decorate(client);
            }
            else if (stage == 2 && ticks == 220)
            {
                startShow(client);
                stage = 3;
            }
            else if (stage == 3)
            {
                runShow(client);
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

    private static void installFilms(MinecraftClient client) throws Exception
    {
        Path source = Path.of(System.getProperty("bbsaaa.films"));
        Path target = client.getServer().getSavePath(WorldSavePath.ROOT).resolve("bbs/films");
        Files.createDirectories(target);

        for (Show show : SHOWS)
        {
            Files.copy(source.resolve(show.film() + ".dat"), target.resolve(show.film() + ".dat"), StandardCopyOption.REPLACE_EXISTING);
        }

        note("installed films into " + target);
    }

    /** App 4: add one AAA particle replay per effect to each film, through the film repository like the editor. */
    private static void decorate(MinecraftClient client)
    {
        IRepository<Film> repository = (IRepository<Film>) (IRepository<?>) ContentType.FILMS.getRepository();

        for (Show show : SHOWS)
        {
            repository.load(show.film(), (film) -> client.execute(() ->
            {
                for (Effect effect : show.effects())
                {
                    Replay replay = film.replays.addReplay();
                    AAAParticleForm form = new AAAParticleForm();

                    form.effect.set(new Link(Link.ASSETS, "effeks/samples/" + effect.effect() + ".efkefc"));
                    form.loop.set(true);
                    form.particleScale.set(effect.scale());
                    replay.form.set(form);
                    replay.label.set("VFX " + effect.effect());

                    for (int tick : new int[] {0, show.ticks() + 20})
                    {
                        replay.keyframes.x.insert(tick, effect.x());
                        replay.keyframes.y.insert(tick, effect.y());
                        replay.keyframes.z.insert(tick, effect.z());
                        replay.keyframes.yaw.insert(tick, effect.yaw());
                        replay.keyframes.bodyYaw.insert(tick, effect.yaw());
                        replay.keyframes.headYaw.insert(tick, effect.yaw());
                    }

                    if (effect.startTick() > 0)
                    {
                        KeyframeChannel paused = replay.properties.getOrCreate(form, "visible");
                        paused.insert(0, false);
                        paused.insert(effect.startTick(), true);
                        KeyframeChannel restart = replay.properties.getOrCreate(form, "restart");
                        restart.insert(0, false);
                        restart.insert(effect.startTick(), true);
                        restart.insert(effect.startTick() + 2, false);
                    }
                }

                for (Replay replay : film.replays.getList())
                {
                    note("  replay '" + replay.label.get() + "' form=" + (replay.form.get() == null ? "null" : replay.form.get().getClass().getSimpleName())
                        + " pos0=" + replay.keyframes.x.interpolate(0) + "," + replay.keyframes.y.interpolate(0) + "," + replay.keyframes.z.interpolate(0)
                        + " yaw0=" + replay.keyframes.yaw.interpolate(0));
                }
                for (Object clip : film.camera.get())
                {
                    if (clip instanceof mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip keyframes)
                    {
                        var first = keyframes.yaw.getKeyframes().isEmpty() ? null : keyframes.yaw.getKeyframes().get(0);
                        note("  camera yaw[0]=" + (first == null ? "none" : first.getValue()) + " pitch[0]=" + keyframes.pitch.interpolate(0));
                        double offset = Double.parseDouble(System.getProperty("bbsaaa.yawOffset", "0"));
                        if (offset != 0)
                        {
                            for (var keyframe : keyframes.yaw.getKeyframes())
                            {
                                keyframe.setValue(((Double) keyframe.getValue()) + offset);
                            }
                            note("  camera yaw shifted by " + offset);
                        }
                    }

                    String data = ((mchorse.bbs_mod.utils.clips.Clip) clip).toData().toString();
                    note("  camera " + clip.getClass().getSimpleName() + " " + data.substring(0, Math.min(400, data.length())));
                }

                repository.save(show.film(), film.toData().asMap());
                note("decorated " + show.film() + ": replays=" + film.replays.getList().size() + " cameraClips=" + film.camera.get().size());
            }));
        }
    }

    private static void startShow(MinecraftClient client)
    {
        Show show = SHOWS.get(showIndex);

        showTick = 0;
        Films.playFilm(show.film(), true);
        note("playing " + show.film());
    }

    private static void runShow(MinecraftClient client)
    {
        Show show = SHOWS.get(showIndex);

        showTick++;

        if (showTick >= 3 && showTick < show.ticks() + 3)
        {
            String name = String.format("showcase_%s_%04d.png", show.film(), showTick - 3);
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), (message) -> {});
        }

        if (showTick == show.ticks() / 2)
        {
            var camera = client.gameRenderer.getCamera();
            note(show.film() + " camera pos=" + camera.getPos() + " yaw=" + camera.getYaw() + " pitch=" + camera.getPitch());
            for (var controller : BBSModClient.getFilms().getControllers())
            {
                controller.getEntities().forEach((key, entity) -> note("  entity " + key + " at " + entity.getX() + "," + entity.getY() + "," + entity.getZ()
                    + " form=" + (entity.getForm() == null ? "null" : entity.getForm().getClass().getSimpleName())));
            }
        }

        if (showTick == 3 || showTick == show.ticks() / 2)
        {
            note(show.film() + " tick " + showTick + ": active=" + BBSModClient.getFilms().has(show.film())
                + " controllers=" + BBSModClient.getFilms().getControllers().size());
        }

        if (showTick >= show.ticks() + 6)
        {
            Films.stopFilm(show.film());
            showIndex++;

            if (showIndex >= SHOWS.size())
            {
                finish(client);
            }
            else
            {
                startShow(client);
            }
        }
    }

    private static void finish(MinecraftClient client)
    {
        stage = 99;

        try
        {
            File out = new File(client.runDirectory, "showcase-report.txt");
            Files.writeString(out.toPath(), String.join("\n", log) + "\n", StandardCharsets.UTF_8);
        }
        catch (Exception ignored)
        {}

        client.scheduleStop();
    }
}
