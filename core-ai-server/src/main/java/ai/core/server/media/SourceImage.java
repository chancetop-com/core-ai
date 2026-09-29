package ai.core.server.media;

import core.framework.web.exception.BadRequestException;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The image being edited, normalized to the working resolution so it always matches its mask. Also builds
 * the marked copy the annotation fallback sends: a model without a mask field has no other spatial channel,
 * and painting the region into the picture is the shape that carries there (see design §3.7).
 *
 * @author stephen
 */
public record SourceImage(byte[] png, int width, int height) {
    static final long MAX_SOURCE_PIXELS = 16_000_000L;
    private static final Color MARK_COLOR = new Color(255, 106, 0);
    private static final float FILL_ALPHA = 0.35f;
    private static final int ALPHA_THRESHOLD = 128;

    public static SourceImage read(byte[] bytes, int width, int height) {
        if ((long) width * height > MAX_SOURCE_PIXELS) {
            throw new BadRequestException("image is too large for region editing, max 16 megapixels");
        }
        var image = AwtPngs.read(bytes);
        if (image == null) throw new BadRequestException("image could not be read");
        var working = EditGeometry.workingSize(width, height);
        var png = AwtPngs.png(AwtPngs.scale(image, working[0], working[1]));
        if (png == null) throw new BadRequestException("image could not be prepared for editing");
        return new SourceImage(png, working[0], working[1]);
    }

    public byte[] annotated(MaskImage mask) {
        var image = AwtPngs.read(png);
        var selection = AwtPngs.read(mask.png());
        if (image == null || selection == null) throw new BadRequestException("image could not be marked for editing");
        paintSelection(image, selection, mask);
        paintOutline(image, mask);
        var marked = AwtPngs.png(image);
        if (marked == null) throw new BadRequestException("image could not be marked for editing");
        return marked;
    }

    private static void paintSelection(BufferedImage image, BufferedImage mask, MaskImage bounds) {
        var maxX = Math.min(Math.min(bounds.selectionX() + bounds.selectionWidth(), image.getWidth()), mask.getWidth());
        var maxY = Math.min(Math.min(bounds.selectionY() + bounds.selectionHeight(), image.getHeight()), mask.getHeight());
        for (var y = bounds.selectionY(); y < maxY; y++) {
            for (var x = bounds.selectionX(); x < maxX; x++) {
                if ((mask.getRGB(x, y) >>> 24) >= ALPHA_THRESHOLD) continue;
                image.setRGB(x, y, blend(image.getRGB(x, y)));
            }
        }
    }

    private static int blend(int rgb) {
        var red = (int) (((rgb >> 16) & 0xFF) * (1 - FILL_ALPHA) + MARK_COLOR.getRed() * FILL_ALPHA);
        var green = (int) (((rgb >> 8) & 0xFF) * (1 - FILL_ALPHA) + MARK_COLOR.getGreen() * FILL_ALPHA);
        var blue = (int) ((rgb & 0xFF) * (1 - FILL_ALPHA) + MARK_COLOR.getBlue() * FILL_ALPHA);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static void paintOutline(BufferedImage image, MaskImage mask) {
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var stroke = Math.max(4f, mask.width() / 256f);
            graphics.setColor(MARK_COLOR);
            graphics.setStroke(new BasicStroke(stroke));
            graphics.drawRect(mask.selectionX(), mask.selectionY(), mask.selectionWidth(), mask.selectionHeight());
            var badge = Math.max(28, mask.width() / 24);
            var inset = (int) stroke + 6;
            graphics.setColor(Color.WHITE);
            graphics.fillRect(mask.selectionX() + inset, mask.selectionY() + inset, badge, badge);
            graphics.setColor(MARK_COLOR);
            graphics.setStroke(new BasicStroke(Math.max(2f, stroke / 2)));
            graphics.drawRect(mask.selectionX() + inset, mask.selectionY() + inset, badge, badge);
        } finally {
            graphics.dispose();
        }
    }
}
