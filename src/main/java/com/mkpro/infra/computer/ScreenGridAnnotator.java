package com.mkpro.infra.computer;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure Java Graphics2D utility for:
 * 1. Normalizing [0..1000] coordinates to/from pixel coordinates.
 * 2. Drawing subtle 100-unit normalized coordinate grids + high-contrast target crosshairs/pills.
 * 3. Drawing Set-of-Mark (SoM) interactive bounding boxes and numbered badges for Browser Use.
 */
public final class ScreenGridAnnotator {

    private ScreenGridAnnotator() {}

    /**
     * Represents an interactive DOM element with its Set-of-Mark ID and pixel bounding box.
     */
    public record SomElement(
            int id,
            String tag,
            String role,
            String label,
            int x,
            int y,
            int width,
            int height
    ) {}

    private static final Color[] SOM_PALETTE = new Color[]{
            new Color(230, 25, 75),    // Red
            new Color(60, 180, 75),    // Green
            new Color(0, 130, 200),    // Blue
            new Color(245, 130, 48),   // Orange
            new Color(145, 30, 180),   // Purple
            new Color(240, 50, 230),   // Magenta
            new Color(0, 128, 128),    // Teal
            new Color(170, 110, 40)    // Brown
    };

    /**
     * Clamps normX to [0, 1000] and converts to pixel X in [0, screenWidth].
     */
    public static int normToPixelX(int normX, int screenWidth) {
        int clamped = Math.max(0, Math.min(1000, normX));
        if (screenWidth <= 0) return 0;
        return Math.round((clamped / 1000.0f) * screenWidth);
    }

    /**
     * Clamps normY to [0, 1000] and converts to pixel Y in [0, screenHeight].
     */
    public static int normToPixelY(int normY, int screenHeight) {
        int clamped = Math.max(0, Math.min(1000, normY));
        if (screenHeight <= 0) return 0;
        return Math.round((clamped / 1000.0f) * screenHeight);
    }

    /**
     * Converts pixel X to normalized [0, 1000] coordinate.
     */
    public static int pixelToNormX(int px, int screenWidth) {
        if (screenWidth <= 0) return 0;
        int norm = Math.round((px * 1000.0f) / screenWidth);
        return Math.max(0, Math.min(1000, norm));
    }

    /**
     * Converts pixel Y to normalized [0, 1000] coordinate.
     */
    public static int pixelToNormY(int py, int screenHeight) {
        if (screenHeight <= 0) return 0;
        int norm = Math.round((py * 1000.0f) / screenHeight);
        return Math.max(0, Math.min(1000, norm));
    }

    /**
     * Copies src into an ARGB BufferedImage, optionally draws a 0..1000 coordinate grid
     * every 100 normalized units, and optionally draws a red target crosshair + action label pill.
     */
    public static BufferedImage annotateWithGridAndCrosshair(
            BufferedImage src,
            Integer normX,
            Integer normY,
            String actionLabel,
            boolean showGrid
    ) {
        if (src == null) {
            throw new IllegalArgumentException("Source image cannot be null");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, null);

            if (showGrid) {
                drawNormalizedGrid(g, w, h);
            }

            if (normX != null && normY != null) {
                int px = normToPixelX(normX, w);
                int py = normToPixelY(normY, h);
                drawTargetCrosshair(g, px, py, w, h, actionLabel);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void drawNormalizedGrid(Graphics2D g, int w, int h) {
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        Font tickFont = new Font(Font.MONOSPACED, Font.BOLD, 11);
        g.setFont(tickFont);
        FontMetrics fm = g.getFontMetrics();

        BasicStroke dashed = new BasicStroke(
                1.0f,
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER,
                10.0f,
                new float[]{4.0f, 4.0f},
                0.0f
        );

        for (int n = 100; n <= 900; n += 100) {
            int gx = normToPixelX(n, w);
            int gy = normToPixelY(n, h);
            String label = String.valueOf(n);

            // Subtle dark + light line for visibility on any background
            g.setStroke(dashed);
            g.setColor(new Color(0, 0, 0, 70));
            g.drawLine(gx + 1, 0, gx + 1, h);
            g.drawLine(0, gy + 1, w, gy + 1);

            g.setColor(new Color(0, 220, 255, 95));
            g.drawLine(gx, 0, gx, h);
            g.drawLine(0, gy, w, gy);

            // Top edge tick label
            int tw = fm.stringWidth(label);
            int th = fm.getAscent();
            int tx = Math.max(2, Math.min(w - tw - 6, gx - tw / 2));
            g.setColor(new Color(0, 0, 0, 165));
            g.fillRoundRect(tx - 2, 2, tw + 4, th + 4, 4, 4);
            g.setColor(new Color(120, 240, 255));
            g.drawString(label, tx, 2 + th);

            // Left edge tick label
            int ly = Math.max(th + 2, Math.min(h - 4, gy + th / 2 - 1));
            g.setColor(new Color(0, 0, 0, 165));
            g.fillRoundRect(2, ly - th, tw + 4, th + 4, 4, 4);
            g.setColor(new Color(120, 240, 255));
            g.drawString(label, 4, ly);
        }

        g.setStroke(oldStroke);
        g.setFont(oldFont);
    }

    private static void drawTargetCrosshair(Graphics2D g, int px, int py, int w, int h, String actionLabel) {
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();

        int outerRadius = 16;
        int innerRadius = 5;
        int arm = 24;

        // White outer halo for contrast on dark backgrounds
        g.setStroke(new BasicStroke(4.0f));
        g.setColor(new Color(255, 255, 255, 220));
        g.drawOval(px - outerRadius, py - outerRadius, outerRadius * 2, outerRadius * 2);
        g.drawLine(px - arm, py, px + arm, py);
        g.drawLine(px, py - arm, px, py + arm);

        // High-contrast red target ring + crosshair
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(new Color(255, 30, 50, 245));
        g.drawOval(px - outerRadius, py - outerRadius, outerRadius * 2, outerRadius * 2);
        g.drawLine(px - arm, py, px + arm, py);
        g.drawLine(px, py - arm, px, py + arm);
        g.fillOval(px - innerRadius, py - innerRadius, innerRadius * 2, innerRadius * 2);

        if (actionLabel != null && !actionLabel.isBlank()) {
            Font pillFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            g.setFont(pillFont);
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(actionLabel);
            int textH = fm.getAscent();
            int padX = 6;
            int padY = 4;
            int boxW = textW + padX * 2;
            int boxH = textH + padY * 2;

            int bx = px + outerRadius + 6;
            if (bx + boxW > w - 4) {
                bx = Math.max(4, px - outerRadius - boxW - 6);
            }
            int by = py - boxH / 2;
            by = Math.max(4, Math.min(h - boxH - 4, by));

            g.setColor(new Color(220, 20, 40, 230));
            g.fill(new RoundRectangle2D.Float(bx, by, boxW, boxH, 8, 8));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(bx, by, boxW, boxH, 8, 8));
            g.drawString(actionLabel, bx + padX, by + padY + textH - 1);
        }

        g.setStroke(oldStroke);
        g.setFont(oldFont);
    }

    /**
     * Copies src and draws high-contrast colored bounding boxes and [id] corner number badges
     * for each SomElement.
     */
    public static BufferedImage annotateWithSomBadges(BufferedImage src, List<SomElement> elements) {
        if (src == null) {
            throw new IllegalArgumentException("Source image cannot be null");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, null);

            if (elements == null || elements.isEmpty()) {
                return out;
            }

            Font badgeFont = new Font(Font.MONOSPACED, Font.BOLD, 12);
            g.setFont(badgeFont);
            FontMetrics fm = g.getFontMetrics();

            for (SomElement el : elements) {
                if (el == null || el.width() <= 0 || el.height() <= 0) continue;
                Color color = SOM_PALETTE[Math.abs(el.id()) % SOM_PALETTE.length];

                int ex = Math.max(0, Math.min(w - 1, el.x()));
                int ey = Math.max(0, Math.min(h - 1, el.y()));
                int ew = Math.min(w - ex, el.width());
                int eh = Math.min(h - ey, el.height());

                // Subtle translucent tint inside bounding box
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
                g.fillRect(ex, ey, ew, eh);

                // High-contrast border
                g.setStroke(new BasicStroke(2.0f));
                g.setColor(color);
                g.drawRect(ex, ey, ew, eh);

                // Corner badge [id]
                String badgeText = String.valueOf(el.id());
                int tw = fm.stringWidth(badgeText);
                int th = fm.getAscent();
                int bw = Math.max(18, tw + 8);
                int bh = th + 4;

                int bx = ex;
                int by = (ey >= bh) ? (ey - bh) : ey;
                bx = Math.max(0, Math.min(w - bw, bx));
                by = Math.max(0, Math.min(h - bh, by));

                g.setColor(color);
                g.fillRoundRect(bx, by, bw, bh, 4, 4);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.0f));
                g.drawRoundRect(bx, by, bw, bh, 4, 4);
                g.drawString(badgeText, bx + (bw - tw) / 2, by + th + 1);
            }
        } finally {
            g.dispose();
        }
        return out;
    }
}
