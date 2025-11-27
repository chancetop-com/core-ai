package ai.core.vender;

import ai.core.utils.SystemUtil;
import ai.core.vender.vendors.RipgrepVendor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for VendorManagement and vendor lifecycle.
 *
 * @author stephen
 */
class VendorManagementTest {
    private final Logger logger = LoggerFactory.getLogger(SystemUtil.class);

    @Test
    void testRipgrepVendorInitialization() {
        VendorManagement manager = VendorManagement.getInstance();

        // Register and initialize ripgrep vendor
        RipgrepVendor ripgrep = manager.getVendor(RipgrepVendor.class);

        assertNotNull(ripgrep, "Ripgrep vendor should not be null");
        assertTrue(ripgrep.initialized, "Ripgrep should be initialized");

        // Get executable path
        Path execPath = ripgrep.getExecutablePath();
        assertNotNull(execPath, "Executable path should not be null");
        assertTrue(Files.exists(execPath), "Executable should exist at: " + execPath);
        assertTrue(Files.isExecutable(execPath), "File should be executable: " + execPath);

        logger.info("Ripgrep executable found at: " + execPath);
    }

    @Test
    void testVendorManagementConvenience() {
        VendorManagement manager = VendorManagement.getInstance();

        // Use convenience method to get executable path directly
        Path execPath = manager.getExecutablePath(RipgrepVendor.class);

        assertNotNull(execPath, "Executable path should not be null");
        assertTrue(Files.exists(execPath), "Executable should exist");

        // Verify vendor is registered
        assertTrue(manager.isRegistered(RipgrepVendor.class), "Ripgrep should be registered");
    }

    @Test
    void testRipgrepExecution() throws Exception {
        VendorManagement manager = VendorManagement.getInstance();
        Path rgPath = manager.getExecutablePath(RipgrepVendor.class);

        // Execute ripgrep --version
        ProcessBuilder pb = new ProcessBuilder(rgPath.toString(), "--version");
        Process process = pb.start();

        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "Ripgrep should execute successfully");

        // Read version output
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(output.contains("ripgrep"), "Output should contain 'ripgrep'");
        logger.info("Ripgrep version: " + output.trim());
    }

    @Test
    void testMultipleVendorAccess() {
        VendorManagement manager = VendorManagement.getInstance();

        // Get vendor multiple times - should return same instance
        RipgrepVendor vendor1 = manager.getVendor(RipgrepVendor.class);
        RipgrepVendor vendor2 = manager.getVendor(RipgrepVendor.class);

        assertSame(vendor1, vendor2, "Should return same vendor instance");
        assertEquals(1, manager.getVendorCount(), "Should have exactly one vendor registered");
    }
}
