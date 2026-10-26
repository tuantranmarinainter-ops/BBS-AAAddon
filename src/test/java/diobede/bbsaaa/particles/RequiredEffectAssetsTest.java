package diobede.bbsaaa.particles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RequiredEffectAssetsTest
{
    @TempDir Path root;

    @Test
    void missingDependencyNeverCallsNativeDecoder()
    {
        AtomicBoolean called = new AtomicBoolean();
        assertThrows(IOException.class, () -> RequiredEffectAssets.load(root.toFile(), "missing.png", (bytes, length) ->
        {
            called.set(true);
            return true;
        }));
        assertFalse(called.get());
    }

    @Test
    void windowsRelativePathLoadsExactBytes() throws IOException
    {
        Path textures = Files.createDirectory(root.resolve("textures"));
        byte[] expected = {1, 2, 3, 4};
        Files.write(textures.resolve("smoke.png"), expected);
        RequiredEffectAssets.load(root.toFile(), "textures\\smoke.png", (bytes, length) ->
        {
            assertArrayEquals(expected, bytes);
            assertEquals(expected.length, length);
            return true;
        });
    }

    @Test
    void rejectedDependencyFailsWholeLoad() throws IOException
    {
        Files.write(root.resolve("broken.model"), new byte[] {0});
        IOException error = assertThrows(IOException.class,
            () -> RequiredEffectAssets.load(root.toFile(), "broken.model", (bytes, length) -> false));
        assertTrue(error.getMessage().contains("broken.model"));
    }

    @Test
    void decoderProgrammingErrorsRemainVisible() throws IOException
    {
        Files.write(root.resolve("texture.png"), new byte[] {0});
        IllegalStateException error = new IllegalStateException("decoder bug");
        assertSame(error, assertThrows(IllegalStateException.class,
            () -> RequiredEffectAssets.load(root.toFile(), "texture.png", (bytes, length) -> { throw error; })));
    }
}
