package ai.core.vender;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author stephen
 */
public final class VendorConfig {
    // Static methods
    public static Builder builder() {
        return new Builder();
    }

    public static VendorConfig defaultConfig() {
        var userHome = System.getProperty("user.home");
        var defaultVendorHome = Paths.get(userHome, ".core-ai", "vendors");
        return new VendorConfig(defaultVendorHome);
    }

    // Instance fields
    private final Path vendorHome;

    // Constructor
    private VendorConfig(Path vendorHome) {
        this.vendorHome = vendorHome;
    }

    // Instance methods
    public Path getVendorHome() {
        return vendorHome;
    }

    public static class Builder {
        private Path vendorHome;

        public Builder vendorHome(String vendorHome) {
            this.vendorHome = Paths.get(vendorHome);
            return this;
        }

        public Builder vendorHome(Path vendorHome) {
            this.vendorHome = vendorHome;
            return this;
        }

        public VendorConfig build() {
            if (vendorHome == null) {
                return defaultConfig();
            }
            return new VendorConfig(vendorHome);
        }
    }
}
