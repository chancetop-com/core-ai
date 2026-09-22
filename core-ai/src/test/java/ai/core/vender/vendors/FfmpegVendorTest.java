package ai.core.vender.vendors;

import ai.core.utils.Platform;
import ai.core.vender.VendorException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class FfmpegVendorTest {
    @Test
    void picksWindowsBinary() {
        assertEquals("ffmpeg-win32-x64.gz", FfmpegVendor.assetFor(Platform.WINDOWS_X64, false).fileName());
    }

    @Test
    void picksArmBuildForArmCpus() {
        assertEquals("ffmpeg-darwin-arm64.gz", FfmpegVendor.assetFor(Platform.MACOS_X64, true).fileName());
        assertEquals("ffmpeg-linux-arm64.gz", FfmpegVendor.assetFor(Platform.LINUX_X64, true).fileName());
    }

    @Test
    void picksX64BuildOtherwise() {
        assertEquals("ffmpeg-darwin-x64.gz", FfmpegVendor.assetFor(Platform.MACOS_X64, false).fileName());
        assertEquals("ffmpeg-linux-x64.gz", FfmpegVendor.assetFor(Platform.LINUX_X64, false).fileName());
    }

    @Test
    void everyAssetIsPinnedBySha256() {
        var assets = List.of(
                FfmpegVendor.assetFor(Platform.WINDOWS_X64, false),
                FfmpegVendor.assetFor(Platform.LINUX_X64, false),
                FfmpegVendor.assetFor(Platform.LINUX_X64, true),
                FfmpegVendor.assetFor(Platform.MACOS_X64, false),
                FfmpegVendor.assetFor(Platform.MACOS_X64, true));

        for (var asset : assets) {
            assertTrue(asset.sha256().matches("[0-9a-f]{64}"), asset.fileName());
            assertTrue(asset.fileName().endsWith(".gz"), asset.fileName());
        }
    }

    @Test
    void rejectsUnknownPlatform() {
        assertThrows(VendorException.class, () -> FfmpegVendor.assetFor(Platform.UNKNOWN, false));
    }
}
