package diobede.bbsaaa.forms.renderers;

import mchorse.bbs_mod.BBSMod;
import diobede.bbsaaa.particles.AAAEffectRegistry;
import diobede.bbsaaa.particles.RequiredEffectAssets;

import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mod.chloeprime.aaaparticles.api.client.EffectDefinition;
import mod.chloeprime.aaaparticles.api.client.EffectHolder;
import mod.chloeprime.aaaparticles.api.client.EffectMetadata;
import mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerEffect;
import mod.chloeprime.aaaparticles.api.client.effekseer.TextureType;
import mod.chloeprime.aaaparticles.client.loader.EffekAssetLoader;
import mod.chloeprime.aaaparticles.client.installer.NativePlatform;
import mod.chloeprime.aaaparticles.client.render.RenderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BBS Effect Loader
 *
 * Custom loader for Effekseer effects from BBS external assets folder.
 * Injects loaded effects into AAA Particles' EffekAssetLoader so they
 * are rendered automatically by the AAA Particles rendering pipeline.
 */
public class BBSEffectLoader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSEffectLoader.class);


    /* Track which effects we've loaded so we can clean them up */
    private static final Set<Identifier> bbsLoadedEffects = new HashSet<>();
    private static final Set<Identifier> failedEffects = new HashSet<>();

    private static volatile boolean reloadRequested;
    private static volatile boolean reloading;
    private static boolean warnedUnsupportedPlatform;

    public static void requestReload()
    {
        reloadRequested = true;
    }

    public static boolean beginReload()
    {
        if (!reloadRequested)
        {
            return false;
        }

        reloadRequested = false;
        reloading = true;

        return true;
    }

    public static void endReload()
    {
        reloading = false;
    }

    public static boolean isReloading()
    {
        return reloading;
    }

    public static boolean canLoadExternalEffects()
    {
        if (NativePlatform.isRunningOnUnsupportedPlatform())
        {
            if (!warnedUnsupportedPlatform)
            {
                warnedUnsupportedPlatform = true;
                LOGGER.warn("AAA native runtime is unavailable; particle forms keep their renderer but draw no effect");
            }
            return false;
        }
        return !reloading;
    }

    /**
     * Get or load an effect definition from BBS external assets.
     * Effects are injected into AAA Particles' EffekAssetLoader for automatic rendering.
     */
    public static EffectDefinition getOrLoad(Identifier id)
    {
        if (!canLoadExternalEffects() || failedEffects.contains(id))
        {
            return null;
        }

        /* First check if AAA Particles already has this effect */
        EffekAssetLoader loader = EffekAssetLoader.get();

        if (loader != null)
        {
            EffectHolder holder = loader.get(id);

            if (holder != null)
            {
                /* Try lazy get (already loaded) first, otherwise trigger load */
                EffectDefinition existing;
                try
                {
                    existing = holder.lazyGet().orElseGet(() -> holder.load().join().orElse(null));
                }
                catch (RuntimeException e)
                {
                    // AAA 2.2.3 can throw synchronously from holder.load(), wrapping
                    // an IOException. Do not hide unrelated integration/programming errors.
                    if (!(e.getCause() instanceof IOException))
                    {
                        throw e;
                    }
                    failedEffects.add(id);
                    LOGGER.warn("AAA effect resource load failed; skipping {} until Reload Effects", id, e);
                    return null;
                }

                if (existing != null)
                {
                    return existing;
                }
            }
        }
        else
        {
            // Native resources must not be created until AAA's registry is ready.
            return null;
        }

        // AAA may have unloaded our holder during a resource reload. A stale ID
        // must not prevent the effect from being loaded again.
        bbsLoadedEffects.remove(id);

        if (!AAAEffectRegistry.isAvailable() || failedEffects.contains(id))
        {
            return null;
        }

        LOGGER.info("Attempting to load effect from BBS assets: {}", id);

        /* Try to load from external assets */
        File assetsFolder = BBSMod.getAssetsFolder();
        String path = id.getPath();

        /* If path doesn't include effeks/, add it */
        if (!path.startsWith("effeks/"))
        {
            path = "effeks/" + path;
        }

        /* Add .efkefc extension if not present */
        if (!path.endsWith(".efkefc"))
        {
            path = path + ".efkefc";
        }

        File effectFile = new File(assetsFolder, path);

        /* Fallback logic: check effekseer/ prefix if file not found */
        if (!effectFile.exists())
        {
            String altPath = id.getPath();
            
            if (!altPath.startsWith("effekseer/"))
            {
                altPath = "effekseer/" + altPath;
            }
            
            if (!altPath.endsWith(".efkefc"))
            {
                altPath = altPath + ".efkefc";
            }
            
            File altEffectFile = new File(assetsFolder, altPath);
            
            if (altEffectFile.exists())
            {
                effectFile = altEffectFile;
                // We don't update 'path' here because it might be used for logging relative to what we expected,
                // or maybe we should? The original code uses 'path' only for logging in loadEffect if it fails.
                // But loadEffect takes effectFile, so it should be fine.
            }
        }

        if (!effectFile.exists())
        {
            LOGGER.warn("Effect file not found; skipping {} until Reload Effects: {}", id, effectFile.getAbsolutePath());
            failedEffects.add(id);

            return null;
        }

        EffectDefinition definition = loadEffect(effectFile, id);

        if (definition != null)
        {
            if (AAAEffectRegistry.register(id, definition))
            {
                bbsLoadedEffects.add(id);
            }
            else
            {
                definition.close();
                failedEffects.add(id);
                return null;
            }
        }
        else
        {
            failedEffects.add(id);
        }

        return definition;
    }

    /**
     * Load an effect from file
     */
    private static EffectDefinition loadEffect(File effectFile, Identifier id)
    {
        EffekseerEffect effect = new EffekseerEffect();
        boolean retained = false;
        try (FileInputStream input = new FileInputStream(effectFile))
        {
            boolean success = effect.load(input, 1);

            if (!success)
            {
                LOGGER.error("Failed to load effect: {}", effectFile.getAbsolutePath());

                return null;
            }

            File parentDir = effectFile.getParentFile();

            /* Wrap all GPU uploads in GL state protection.
             * Minecraft sets GL_UNPACK_ROW_LENGTH and similar pixel store state when
             * loading its own textures. Without this wrapper, texture/model uploads
             * happening mid-frame pick up that dirty state, causing UV corruption. */
            RenderUtil.runEffekLoadCodeHealthily(() ->
            {
                /* Load textures */
                for (TextureType texType : TextureType.values())
                {
                    int count = effect.textureCount(texType);

                    for (int i = 0; i < count; i++)
                    {
                        final int index = i;
                        String texturePath = effect.getTexturePath(i, texType);

                        if (texturePath != null && !texturePath.isEmpty())
                        {
                            loadAsset(parentDir, texturePath, (data, len) ->
                                effect.loadTexture(data, len, index, texType));
                        }
                    }
                }

                /* Load models */
                int modelCount = effect.modelCount();

                for (int i = 0; i < modelCount; i++)
                {
                    final int index = i;
                    String modelPath = effect.getModelPath(i);

                    if (modelPath != null && !modelPath.isEmpty())
                    {
                        loadAsset(parentDir, modelPath, (data, len) ->
                            effect.loadModel(data, len, index));
                    }
                }

                /* Load curves */
                int curveCount = effect.curveCount();

                for (int i = 0; i < curveCount; i++)
                {
                    final int index = i;
                    String curvePath = effect.getCurvePath(i);

                    if (curvePath != null && !curvePath.isEmpty())
                    {
                        loadAsset(parentDir, curvePath, (data, len) ->
                            effect.loadCurve(data, len, index));
                    }
                }

                /* Load materials */
                int materialCount = effect.materialCount();

                for (int i = 0; i < materialCount; i++)
                {
                    final int index = i;
                    String materialPath = effect.getMaterialPath(i);

                    if (materialPath != null && !materialPath.isEmpty())
                    {
                        loadAsset(parentDir, materialPath, (data, len) ->
                            effect.loadMaterial(data, len, index));
                    }
                }
            });

            EffectDefinition definition = new EffectDefinition(EffectMetadata.DEFAULT);

            definition.setEffect(effect);
            retained = true;

            LOGGER.info("Loaded BBS effect: {} from {}", id, effectFile.getAbsolutePath());

            return definition;
        }
        catch (IOException | UncheckedIOException e)
        {
            LOGGER.warn("Cannot load effect; drawing no effect until Reload Effects: {}", effectFile.getAbsolutePath(), e);

            return null;
        }
        finally
        {
            if (!retained)
            {
                effect.close();
            }
        }
    }

    /**
     * Load an asset file relative to the effect file
     */
    private static void loadAsset(File parentDir, String assetPath, AssetLoader loader)
    {
        try
        {
            RequiredEffectAssets.load(parentDir, assetPath, loader::load);
        }
        catch (IOException e)
        {
            // The outer effect-load boundary logs the cause and closes the effect.
            // Never register a partially loaded native effect.
            throw new UncheckedIOException("Cannot load required effect asset: " + assetPath, e);
        }
    }

    public static int preloadExternalEffects()
    {
        if (!canLoadExternalEffects())
        {
            return 0;
        }

        File assetsFolder = BBSMod.getAssetsFolder();
        File effeksFolder = new File(assetsFolder, "effeks");

        if (!effeksFolder.exists() || !effeksFolder.isDirectory())
        {
            return 0;
        }

        List<File> files = new ArrayList<>();
        collectEfks(files, effeksFolder);

        int loaded = 0;

        for (File file : files)
        {
            String relative = effeksFolder.toPath().relativize(file.toPath()).toString().replace("\\", "/");

            if (!relative.endsWith(".efkefc"))
            {
                continue;
            }

            relative = relative.substring(0, relative.length() - 7);

            Identifier id;

            try
            {
                id = new Identifier("bbs", relative);
            }
            catch (Exception e)
            {
                continue;
            }

            if (bbsLoadedEffects.contains(id))
            {
                continue;
            }

            EffectDefinition definition = getOrLoad(id);

            if (definition != null)
            {
                loaded += 1;
            }
        }

        return loaded;
    }

    private static void collectEfks(List<File> list, File folder)
    {
        File[] files = folder.listFiles();

        if (files == null)
        {
            return;
        }

        for (File file : files)
        {
            if (file.isDirectory())
            {
                collectEfks(list, file);
            }
            else if (file.getName().endsWith(".efkefc"))
            {
                list.add(file);
            }
        }
    }

    /**
     * Clear all BBS-loaded effects from AAA Particles
     */
    public static void clearCache()
    {
        AAAEffectRegistry.clear();
        bbsLoadedEffects.clear();
        failedEffects.clear();
    }

    /**
     * Check if an effect is loaded
     */
    public static boolean isLoaded(Identifier id)
    {
        return bbsLoadedEffects.contains(id);
    }

    /**
     * Check if there are any BBS effects loaded
     */
    public static boolean hasLoadedEffects()
    {
        return !bbsLoadedEffects.isEmpty();
    }

    @FunctionalInterface
    private interface AssetLoader
    {
        boolean load(byte[] data, int length);
    }
}
