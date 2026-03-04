package ai.core.cli.graalvm;

import ai.core.api.tool.function.CoreAiMethod;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author stephen
 */
public class NativeReflectionFeature implements Feature {
    private static final Logger LOGGER = Logger.getLogger(NativeReflectionFeature.class.getName());

    private static final String[] PACKAGES = {
        "ai/core/llm/domain",
        "ai/core/api/jsonschema"
    };

    private static final String[] EXTRA_CLASSES = {
        "core.framework.api.json.Property",
        "ai.core.api.tool.function.CoreAiParameter",
        "ai.core.api.tool.function.CoreAiMethod",
        "ai.core.agent.AgentPersistence$AgentPersistenceDomain",
        "ai.core.agent.NodeStatus",
        // Vendor management classes for GraalVM reflection
        "ai.core.vender.Vendor",
        "ai.core.vender.VendorConfig",
        "ai.core.vender.VendorManagement",
        "ai.core.vender.VendorException",
        "ai.core.vender.vendors.RipgrepVendor"
    };

    // base packages to scan recursively for @CoreAiMethod tool classes
    private static final String[] TOOL_SCAN_PACKAGES = {
        "ai/core"
    };

    private final Set<Class<?>> registered = new HashSet<>();

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (String pkg : PACKAGES) {
            scanPackage(access, pkg);
        }
        for (String className : EXTRA_CLASSES) {
            registerClass(access, className);
        }
        for (String pkg : TOOL_SCAN_PACKAGES) {
            scanToolClasses(access, pkg);
        }
    }

    private void scanPackage(BeforeAnalysisAccess access, String pkg) {
        ClassLoader cl = access.getApplicationClassLoader();
        try {
            Enumeration<java.net.URL> resources = cl.getResources(pkg);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(access, new File(url.getPath()), pkg);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJar(access, url, pkg);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to scan package: {0}", pkg.replace('/', '.'));
        }
    }

    private void scanDirectory(BeforeAnalysisAccess access, File dir, String pkg) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().endsWith(".class")) {
                String className = pkg.replace('/', '.') + "." + file.getName().replace(".class", "");
                registerClass(access, className);
            }
        }
    }

    private void scanJar(BeforeAnalysisAccess access, java.net.URL url, String pkg) throws IOException {
        String jarPath = url.getPath();
        int bangIndex = jarPath.indexOf('!');
        if (bangIndex > 0) jarPath = jarPath.substring(0, bangIndex);
        if (jarPath.startsWith("file:")) jarPath = jarPath.substring(5);

        try (JarFile jar = new JarFile(jarPath)) {
            String prefix = pkg + "/";
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(prefix) && name.endsWith(".class") && !name.substring(prefix.length()).contains("/")) {
                    String className = name.replace('/', '.').replace(".class", "");
                    registerClass(access, className);
                }
            }
        }
    }

    // --- @CoreAiMethod auto-discovery ---

    private void scanToolClasses(BeforeAnalysisAccess access, String basePkg) {
        ClassLoader cl = access.getApplicationClassLoader();
        try {
            Enumeration<java.net.URL> resources = cl.getResources(basePkg);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectoryForTools(access, new File(url.getPath()), basePkg);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarForTools(access, url, basePkg);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to scan tool package: {0}", basePkg.replace('/', '.'));
        }
    }

    private void scanDirectoryForTools(BeforeAnalysisAccess access, File dir, String pkg) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectoryForTools(access, file, pkg + "/" + file.getName());
            } else if (file.getName().endsWith(".class")) {
                String className = pkg.replace('/', '.') + "." + file.getName().replace(".class", "");
                registerIfTool(access, className);
            }
        }
    }

    private void scanJarForTools(BeforeAnalysisAccess access, java.net.URL url, String basePkg) throws IOException {
        String jarPath = url.getPath();
        int bangIndex = jarPath.indexOf('!');
        if (bangIndex > 0) jarPath = jarPath.substring(0, bangIndex);
        if (jarPath.startsWith("file:")) jarPath = jarPath.substring(5);

        try (JarFile jar = new JarFile(jarPath)) {
            String prefix = basePkg + "/";
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(prefix) && name.endsWith(".class")) {
                    String className = name.replace('/', '.').replace(".class", "");
                    registerIfTool(access, className);
                }
            }
        }
    }

    private void registerIfTool(BeforeAnalysisAccess access, String className) {
        var clazz = access.findClassByName(className);
        if (clazz == null) return;

        boolean hasCoreAiMethod = false;
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getAnnotation(CoreAiMethod.class) != null) {
                    hasCoreAiMethod = true;
                    registerMethodParameterTypes(access, method);
                }
            }
        } catch (Throwable ignored) {
            // class introspection may fail for some classes, safe to skip
        }

        if (hasCoreAiMethod) {
            registerClassDeep(access, clazz);
        }
    }

    private void registerMethodParameterTypes(BeforeAnalysisAccess access, Method method) {
        for (Type genericType : method.getGenericParameterTypes()) {
            registerType(access, genericType);
        }
    }

    private void registerType(BeforeAnalysisAccess access, Type type) {
        if (type instanceof Class<?> clazz) {
            registerClassDeep(access, clazz);
        } else if (type instanceof ParameterizedType pt) {
            registerType(access, pt.getRawType());
            for (Type arg : pt.getActualTypeArguments()) {
                registerType(access, arg);
            }
        }
    }

    private void registerClassDeep(BeforeAnalysisAccess access, Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz == Object.class) return;
        if (clazz.getPackageName().startsWith("java.")) return;
        if (!registered.add(clazz)) return;

        registerClassMembers(clazz);

        for (Class<?> inner : clazz.getDeclaredClasses()) {
            registerClassDeep(access, inner);
        }
        for (var field : clazz.getDeclaredFields()) {
            registerType(access, field.getGenericType());
        }
    }

    // --- common registration ---

    private void registerClass(BeforeAnalysisAccess access, String className) {
        var clazz = access.findClassByName(className);
        if (clazz == null) return;
        registerClassMembers(clazz);
    }

    private void registerClassMembers(Class<?> clazz) {
        RuntimeReflection.register(clazz);
        for (var c : clazz.getDeclaredConstructors()) {
            RuntimeReflection.register(c);
        }
        for (var m : clazz.getDeclaredMethods()) {
            RuntimeReflection.register(m);
        }
        for (var f : clazz.getDeclaredFields()) {
            RuntimeReflection.register(f);
        }
    }
}
