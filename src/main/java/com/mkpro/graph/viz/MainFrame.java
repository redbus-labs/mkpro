package com.mkpro.graph.viz;

import com.mkpro.graph.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MainFrame extends JFrame {
    private final GraphCanvas canvas;
    private final JTextArea propertyArea;
    private final JTree entityTree;
    private final JToggleButton hierarchicalToggle;
    private final JTextField searchField;
    private List<Entity> allEntities;
    private HybridSearcher searcher;

    public MainFrame() {
        setTitle("MkPro Graph Visualizer (OpenCL Powered)");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1400, 900);
        setLayout(new BorderLayout());

        // Setup Left Panel (Search & Tree)
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(350, 0));
        leftPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Search Bar
        JPanel searchPanel = new JPanel(new BorderLayout(5, 5));
        searchPanel.setBorder(new EmptyBorder(0, 0, 10, 0));
        searchField = new JTextField();
        searchField.setToolTipText("Search entities (token match, CamelCase support)");
        searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                updateTreeWithSearch(searchField.getText());
            }
        });

        entityTree = new JTree(new DefaultMutableTreeNode("Entities"));
        JScrollPane treeScroll = new JScrollPane(entityTree);
        
        leftPanel.add(searchPanel, BorderLayout.NORTH);
        leftPanel.add(treeScroll, BorderLayout.CENTER);

        // Setup Right Panel (Canvas & Properties)
        canvas = new GraphCanvas();
        propertyArea = new JTextArea();
        propertyArea.setEditable(false);
        propertyArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JScrollPane propScroll = new JScrollPane(propertyArea);
        propScroll.setPreferredSize(new Dimension(300, 0));

        // Controls
        JPanel controlPanel = new JPanel();
        hierarchicalToggle = new JToggleButton("Hierarchical View");
        controlPanel.add(hierarchicalToggle);

        add(leftPanel, BorderLayout.WEST);
        add(canvas, BorderLayout.CENTER);
        add(propScroll, BorderLayout.EAST);
        add(controlPanel, BorderLayout.SOUTH);
    }

    public void updateGraph(List<Entity> entities, List<Relationship> relationships, float[] positions, Map<String, Integer> entityIdToIndex) {
        if (allEntities == null) {
            this.allEntities = entities;
            this.searcher = new HybridSearcher(entities);
            updateTreeWithSearch("");
        }
        canvas.updateData(entities, relationships, positions, entityIdToIndex);
    }

    private void updateTreeWithSearch(String query) {
        if (allEntities == null) return;
        
        List<Entity> filtered = searcher.search(query);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Entities (" + filtered.size() + ")");
        
        // Group by type
        Map<String, List<Entity>> grouped = filtered.stream()
                .sorted(Comparator.comparing(Entity::name))
                .collect(Collectors.groupingBy(e -> e.type().name()));

        grouped.keySet().stream().sorted().forEach(type -> {
            DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(type);
            for (Entity e : grouped.get(type)) {
                typeNode.add(new DefaultMutableTreeNode(e));
            }
            root.add(typeNode);
        });

        entityTree.setModel(new DefaultTreeModel(root));
        // Expand first level
        for (int i = 0; i < entityTree.getRowCount(); i++) {
            entityTree.expandRow(i);
        }
    }

    public void updateProperties(Entity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Entity Details ---\n");
        sb.append("Name: ").append(entity.name()).append("\n");
        sb.append("Type: ").append(entity.type()).append("\n");
        sb.append("ID: ").append(entity.id()).append("\n\n");
        
        if (entity.metadata() != null && !entity.metadata().isEmpty()) {
            sb.append("Metadata:\n");
            entity.metadata().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }
        propertyArea.setText(sb.toString());
    }

    public void clearProperties() {
        propertyArea.setText("");
    }

    public GraphCanvas getCanvas() { return canvas; }
    public JTree getEntityTree() { return entityTree; }
    public JToggleButton getHierarchicalToggle() { return hierarchicalToggle; }
}
