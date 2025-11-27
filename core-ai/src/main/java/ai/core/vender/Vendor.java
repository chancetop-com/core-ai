package ai.core.vender;

import ai.core.utils.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author stephen
 */
public abstract class Vendor {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Path customVendorHome;
    protected volatile Path vendorHome;
    protected volatile boolean initialized = false;

    protected Vendor() {
        this(null);
    }

    protected Vendor(Path customVendorHome) {
        this.customVendorHome = customVendorHome;
    }

    /**
     * Get or create the vendor home directory.
     * This is called during initialization to avoid this-escape issues in constructor.
     */
    protected final Path getVendorHomeDirectory() {
        if (vendorHome != null) {
            return vendorHome;
        }

        Path baseDir;
        if (customVendorHome != null) {
            baseDir = customVendorHome;
        } else {
            var userHome = System.getProperty("user.home");
            baseDir = Paths.get(userHome, ".core-ai", "vendors");
        }

        var vendorDir = baseDir.resolve(getVendorName());
        try {
            Files.createDirectories(vendorDir);
        } catch (IOException e) {
            throw new VendorException("Failed to create vendor directory: " + vendorDir, e);
        }
        vendorHome = vendorDir;
        return vendorDir;
    }

    public void initialize() {
        synchronized (this) {
            if (initialized) {
                return;
            }

            logger.info("Initializing vendor: {}", getVendorName());

            try {
                // Ensure vendor home directory is created
                getVendorHomeDirectory();
                if (!isInstalled()) {
                    logger.info("Vendor {} not found, starting installation...", getVendorName());
                    download();
                    install();
                    verify();
                } else {
                    logger.info("Vendor {} already installed at: {}", getVendorName(), vendorHome);
                    verify();
                }
                initialized = true;
                logger.info("Vendor {} initialized successfully", getVendorName());
            } catch (Exception e) {
                throw new VendorException("Failed to initialize vendor: " + getVendorName(), e);
            }
        }
    }

    public abstract String getVendorName();

    public abstract String getVersion();

    protected abstract boolean isInstalled();

    protected abstract void download() throws Exception;

    protected abstract void install() throws Exception;

    protected abstract void verify();

    public Path getExecutablePath() {
        if (!initialized) {
            throw new VendorException("Vendor not initialized: " + getVendorName() + ". Call initialize() first.");
        }
        return getExecutablePathInternal();
    }

    protected abstract Path getExecutablePathInternal();

    public void cleanup() {
        try {
            logger.info("Cleaning up vendor: {}", getVendorName());
            if (Files.exists(vendorHome)) {
                SystemUtil.deleteDirectory(vendorHome);
            }
            initialized = false;
        } catch (IOException e) {
            throw new VendorException("Failed to cleanup vendor: " + getVendorName(), e);
        }
    }
}
