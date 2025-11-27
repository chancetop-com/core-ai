package ai.core.vender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public final class VendorManagement {
    // Static fields
    private static final Logger LOGGER = LoggerFactory.getLogger(VendorManagement.class);
    private static volatile VendorManagement instance;

    // Static methods
    public static VendorManagement getInstance() {
        if (instance == null) {
            synchronized (VendorManagement.class) {
                if (instance == null) {
                    VendorConfig config = loadConfigFromSystemProperty();
                    instance = new VendorManagement(config);
                    LOGGER.info("VendorManagement lazily initialized with vendor home: {}", config.getVendorHome());
                }
            }
        }
        return instance;
    }

    public static void initialize(VendorConfig config) {
        synchronized (VendorManagement.class) {
            if (instance != null) {
                LOGGER.warn("VendorManagement already initialized, configuration will not be updated");
                return;
            }
            instance = new VendorManagement(config);
            LOGGER.info("VendorManagement initialized with custom vendor home: {}", config.getVendorHome());
        }
    }

    private static VendorConfig loadConfigFromSystemProperty() {
        var vendorHome = System.getProperty("sys.vendor.home");
        if (vendorHome != null && !vendorHome.isEmpty()) {
            LOGGER.info("Loading vendor home from system property: {}", vendorHome);
            return VendorConfig.builder()
                .vendorHome(vendorHome)
                .build();
        }
        LOGGER.info("No sys.vendor.home property set, using default: ~/.core-ai/vendors");
        return VendorConfig.defaultConfig();
    }

    // Instance fields
    private final Map<Class<? extends Vendor>, Vendor> vendors = new ConcurrentHashMap<>();
    private final VendorConfig config;

    // Constructors
    private VendorManagement() {
        this(VendorConfig.defaultConfig());
    }

    private VendorManagement(VendorConfig config) {
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public <T extends Vendor> T registerVendor(Class<T> vendorClass) {
        var vendor = vendors.computeIfAbsent(vendorClass, clazz -> {
            try {
                LOGGER.info("Registering vendor: {}", clazz.getSimpleName());
                Vendor v = createVendorInstance(clazz);
                v.initialize();
                return v;
            } catch (Exception e) {
                throw new VendorException("Failed to register vendor: " + clazz.getSimpleName(), e);
            }
        });
        return (T) vendor;
    }

    @SuppressWarnings("unchecked")
    public <T extends Vendor> T registerVendor(T vendor) {
        var vendorClass = vendor.getClass();
        vendors.putIfAbsent(vendorClass, vendor);

        T registered = (T) vendors.get(vendorClass);
        if (!registered.initialized) {
            registered.initialize();
        }
        return registered;
    }

    private Vendor createVendorInstance(Class<? extends Vendor> vendorClass) throws Exception {
        // Try to create with custom vendor home constructor first
        try {
            return vendorClass.getDeclaredConstructor(Path.class).newInstance(config.getVendorHome());
        } catch (NoSuchMethodException e) {
            // Fall back to default constructor
            return vendorClass.getDeclaredConstructor().newInstance();
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Vendor> T getVendor(Class<T> vendorClass) {
        T vendor = (T) vendors.get(vendorClass);
        if (vendor == null) {
            return registerVendor(vendorClass);
        }
        return vendor;
    }

    public <T extends Vendor> Path getExecutablePath(Class<T> vendorClass) {
        return getVendor(vendorClass).getExecutablePath();
    }

    public boolean isRegistered(Class<? extends Vendor> vendorClass) {
        return vendors.containsKey(vendorClass);
    }

    public void unregisterVendor(Class<? extends Vendor> vendorClass) {
        var vendor = vendors.remove(vendorClass);
        if (vendor != null) {
            LOGGER.info("Unregistering vendor: {}", vendorClass.getSimpleName());
            vendor.cleanup();
        }
    }

    public void cleanupAll() {
        LOGGER.info("Cleaning up all vendors");
        for (var entry : vendors.entrySet()) {
            try {
                entry.getValue().cleanup();
            } catch (Exception e) {
                LOGGER.error("Failed to cleanup vendor: {}", entry.getKey().getSimpleName(), e);
            }
        }
        vendors.clear();
    }

    public int getVendorCount() {
        return vendors.size();
    }
}
