package ai.core.example.site.service;

import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.util.ASCII;
import core.framework.util.Encodings;
import core.framework.util.Files;
import core.framework.util.StopWatch;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * @author stephen
 */
public class ImageService {
    private static final Long LIMIT_FILE_LENGTH = 200000L;
    private static final String PNG_EXTENSION = "png";
    private static final String JPG_EXTENSION = "jpg";
    private static final String JPEG_EXTENSION = "jpeg";
    private static final String WEBP_EXTENSION = "webp";
    private final Path localImagePath = Files.tempDir();
    @Inject
    StorageService storageService;

    public String create(String modulePath, String fileName, String imageBase64) {
        Path path = saveToLocalPath(imageBase64);
        String extension = extension(modulePath, fileName).orElseThrow(() -> new BadRequestException("unsupported file"));
        if (!(PNG_EXTENSION.equals(extension) || JPG_EXTENSION.equals(extension) || JPEG_EXTENSION.equals(extension) || WEBP_EXTENSION.equals(extension))) {
            throw new ConflictException("unsupported file extension");
        }
        boolean fileIsTooBig = path.toFile().length() > LIMIT_FILE_LENGTH;
        String storageFileName = Strings.format("{}.{}", UUID.randomUUID().toString(), fileIsTooBig && PNG_EXTENSION.equals(extension) ? JPG_EXTENSION : extension);
        return storageService.put(modulePath, storageFileName, path.toFile());
    }

    private Path saveToLocalPath(String imageBase64) {
        StopWatch watch = new StopWatch();
        Path path = localImagePath.resolve(UUID.randomUUID().toString());
        try (BufferedOutputStream bufferedOutput = new BufferedOutputStream(java.nio.file.Files.newOutputStream(path))) {
            bufferedOutput.write(Encodings.decodeBase64(imageBase64));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            ActionLogContext.put("temp_path_elapsed", watch.elapsed());
        }
        return path;
    }

    private Optional<String> extension(String modulePath, String fileName) {
        if (Strings.isBlank(modulePath) || Strings.isBlank(fileName)) return Optional.empty();
        String[] splits = Strings.split(fileName, '.');
        if (splits.length > 1) {
            String extension = ASCII.toLowerCase(splits[splits.length - 1]);
            return Optional.of(extension);
        } else {
            return Optional.empty();
        }
    }
}
