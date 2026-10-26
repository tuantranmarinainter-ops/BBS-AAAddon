package diobede.bbsaaa.particles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Required external dependencies must all load before an effect can be registered. */
public final class RequiredEffectAssets
{
    private RequiredEffectAssets()
    {}

    public static void load(File parent, String path, Decoder decoder) throws IOException
    {
        File file = new File(parent, path.replace('\\', '/'));
        byte[] data = Files.readAllBytes(file.toPath());
        if (!decoder.load(data, data.length))
        {
            throw new IOException("Effekseer rejected required asset: " + file.getAbsolutePath());
        }
    }

    @FunctionalInterface
    public interface Decoder
    {
        boolean load(byte[] data, int length);
    }
}
