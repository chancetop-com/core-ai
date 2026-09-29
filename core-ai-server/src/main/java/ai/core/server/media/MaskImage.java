package ai.core.server.media;

import core.framework.web.exception.BadRequestException;

import java.awt.image.BufferedImage;
import java.util.Base64;

/**
 * The painted region, normalized to what an upstream edit call can take: a png whose transparent pixels
 * are the ones the model may repaint (the OpenAI/Azure convention), sized exactly like the image it
 * travels with. A protocol with the opposite polarity flips it inside its own adapter - this type only
 * ever produces "transparent = repaint".
 *
 * @author stephen
 */
public record MaskImage(byte[] png, int width, int height,
                        int selectionX, int selectionY, int selectionWidth, int selectionHeight) {
    // the request body budget is 10 MB (core-ng maxEntitySize, multipart included) and base64 inflates by
    // 4/3, so the decoded png is capped well below it instead of failing at the HTTP layer with no body
    static final int MAX_ENCODED_LENGTH = 8 * 1024 * 1024;
    static final int MAX_DECODED_BYTES = 6 * 1024 * 1024;
    static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final int ALPHA_THRESHOLD = 128;
    private static final double MAX_RATIO_DRIFT = 0.01;

    public static MaskImage decode(String value, int sourceWidth, int sourceHeight) {
        var bytes = decodeBase64(encoded(value));
        var image = AwtPngs.read(bytes);
        if (image == null) throw new BadRequestException("mask is not a valid image");
        if (!image.getColorModel().hasAlpha()) throw new BadRequestException("mask must be a png with a transparent selection");
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) throw new BadRequestException("mask is too large");
        var selection = selectionBounds(image);
        if (selection == null) throw new BadRequestException("paint the area to change first");
        requireSameRatio(image.getWidth(), image.getHeight(), sourceWidth, sourceHeight);
        return scaled(image, selection, sourceWidth, sourceHeight);
    }

    private static String encoded(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("mask is required");
        var encoded = value.trim();
        if (encoded.startsWith("data:")) {
            var comma = encoded.indexOf(',');
            if (comma < 0) throw new BadRequestException("mask is not a valid data url");
            encoded = encoded.substring(comma + 1);
        }
        // checked before decoding: this is the length that has to fit the request body
        if (encoded.length() > MAX_ENCODED_LENGTH) throw new BadRequestException("mask is too large, paint a smaller area and try again");
        return encoded;
    }

    private static byte[] decodeBase64(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("mask is not valid base64", "BAD_REQUEST", e);
        }
    }

    /** {x, y, width, height} of the transparent pixels, or null when nothing was painted. */
    private static int[] selectionBounds(BufferedImage image) {
        var minX = image.getWidth();
        var minY = image.getHeight();
        var maxX = -1;
        var maxY = -1;
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < ALPHA_THRESHOLD) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    private static void requireSameRatio(int maskWidth, int maskHeight, int sourceWidth, int sourceHeight) {
        var maskRatio = (double) maskWidth / maskHeight;
        var sourceRatio = (double) sourceWidth / sourceHeight;
        if (Math.abs(maskRatio - sourceRatio) / sourceRatio > MAX_RATIO_DRIFT) {
            throw new BadRequestException("mask and image have different proportions");
        }
    }

    private static MaskImage scaled(BufferedImage image, int[] selection, int sourceWidth, int sourceHeight) {
        var working = EditGeometry.workingSize(sourceWidth, sourceHeight);
        var png = AwtPngs.png(AwtPngs.scale(image, working[0], working[1]));
        if (png == null) throw new BadRequestException("mask could not be encoded");
        if (png.length > MAX_PNG_BYTES) throw new BadRequestException("the painted area is too complex, reduce it and try again");
        var ratioX = (double) working[0] / image.getWidth();
        var ratioY = (double) working[1] / image.getHeight();
        return new MaskImage(png, working[0], working[1],
                (int) Math.floor(selection[0] * ratioX), (int) Math.floor(selection[1] * ratioY),
                Math.max(1, (int) Math.round(selection[2] * ratioX)), Math.max(1, (int) Math.round(selection[3] * ratioY)));
    }
}
