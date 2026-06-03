package com.mkpro.graph.viz;

import com.mkpro.graph.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.List;
import java.util.Map;

public class GraphCanvas extends JPanel {
    private List<Entity> entities;
    private List<Relationship> relationships;
    private float[] positions;
    private Map<String, Integer> entityIdToIndex;
    private String selectedEntityId;

    public GraphCanvas() {
        setBackground(new Color(245, 245, 245));
    }

    public void updateData(List<Entity> entities, List<Relationship> relationships, float[] positions, Map<String, Integer> entityIdToIndex) {
        this.entities = entities;
        this.relationships = relationships;
        this.positions = positions;
        this.entityIdToIndex = entityIdToIndex;
        repaint();
    }

    public void setSelectedEntityId(String selectedEntityId) {
        this.selectedEntityId = selectedEntityId;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (entities == null || positions == null || entityIdToIndex == null) return;

        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        float centerX = w / 2f;
        float centerY = h / 2f;

        // Auto-calculate scale factor to fit graph in window
        float maxCoord = 1.0f;
        for (int i = 0; i < entities.size(); i++) {
            maxCoord = Math.max(maxCoord, Math.abs(positions[i * 2]));
            maxCoord = Math.max(maxCoord, Math.abs(positions[i * 2 + 1]));
        }
        // Use 90% of the available space to provide some margin
        float scale = (Math.min(w, h) / 2.0f) / maxCoord * 0.9f;

        // Draw relationships
        g2d.setColor(new Color(180, 180, 180, 150));
        g2d.setStroke(new BasicStroke(1.2f));
        for (Relationship rel : relationships) {
            Integer sIdx = entityIdToIndex.get(rel.sourceId());
            Integer tIdx = entityIdToIndex.get(rel.targetId());
            if (sIdx != null && tIdx != null) {
                float x1 = centerX + positions[sIdx * 2] * scale;
                float y1 = centerY + positions[sIdx * 2 + 1] * scale;
                float x2 = centerX + positions[tIdx * 2] * scale;
                float y2 = centerY + positions[tIdx * 2 + 1] * scale;
                
                // Highlight edges connected to selected entity
                if (rel.sourceId().equals(selectedEntityId) || rel.targetId().equals(selectedEntityId)) {
                    g2d.setColor(new Color(255, 140, 0, 200));
                    g2d.setStroke(new BasicStroke(2.0f));
                    g2d.draw(new Line2D.Float(x1, y1, x2, y2));
                    g2d.setColor(new Color(180, 180, 180, 150));
                    g2d.setStroke(new BasicStroke(1.2f));
                } else {
                    g2d.draw(new Line2D.Float(x1, y1, x2, y2));
                }
            }
        }

        // Draw entities
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            float x = centerX + positions[i * 2] * scale;
            float y = centerY + positions[i * 2 + 1] * scale;

            boolean isSelected = entity.id().equals(selectedEntityId);
            
            if (isSelected) {
                g2d.setColor(new Color(255, 69, 0)); // Orange-Red for selection
                g2d.fill(new Ellipse2D.Float(x - 10, y - 10, 20, 20));
                g2d.setColor(Color.BLACK);
                g2d.draw(new Ellipse2D.Float(x - 10, y - 10, 20, 20));
            } else {
                g2d.setColor(new Color(70, 130, 180)); // Steel Blue
                g2d.fill(new Ellipse2D.Float(x - 8, y - 8, 16, 16));
            }

            g2d.setColor(isSelected ? Color.RED : Color.BLACK);
            g2d.setFont(new Font("SansSerif", isSelected ? Font.BOLD : Font.PLAIN, 12));
            g2d.drawString(entity.name(), x + 12, y + 5);
        }
    }
}
