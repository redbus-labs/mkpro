package com.mkpro;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.mkpro.models.AgentConfig;
import com.mkpro.agents.AgentManager;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Map;

public class SwingCompanion {

    private final Runner runner;
    private final Session session;
 
 
    
    private JFrame frame;
    private JTextArea chatHistory;
    private JTextField inputField;
    private JButton sendButton;
    private JProgressBar progressBar;

    public SwingCompanion(Runner runner, Session mkSession) {
   
        this.runner = runner;
        this.session = runner.sessionService().createSession("mkpro", "Coordinator").blockingGet();
        initializeUI();
    }

    private void initializeUI() {
        // Main Frame
        frame = new JFrame("mkpro Companion - " );
        frame.setSize(600, 800);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        // Chat History Area (Non-editable)
        chatHistory = new JTextArea();
        chatHistory.setEditable(false);
        chatHistory.setLineWrap(true);
        chatHistory.setWrapStyleWord(true);
        chatHistory.setFont(new Font("Consolas", Font.PLAIN, 14));
        chatHistory.setMargin(new Insets(10, 10, 10, 10));
        
        // Auto-scroll to bottom
        DefaultCaret caret = (DefaultCaret) chatHistory.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(chatHistory);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        frame.add(scrollPane, BorderLayout.CENTER);

        // Input Panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        inputField = new JTextField();
        inputField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        inputField.addActionListener(this::sendMessage); // Enter key triggers send

        sendButton = new JButton("Send");
        sendButton.addActionListener(this::sendMessage);

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        // Progress Bar (Indeterminate)
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(100, 4));
        inputPanel.add(progressBar, BorderLayout.NORTH);

        frame.add(inputPanel, BorderLayout.SOUTH);

        // Welcome Message
        appendToHistory("System", "mkpro Companion ( ) ready. Type your message below.");
    }

    public void setVisible(boolean visible) {
        SwingUtilities.invokeLater(() -> frame.setVisible(visible));
    }

    public void show() {
        setVisible(true);
    }

    private void sendMessage(ActionEvent e) {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        progressBar.setVisible(true);

        appendToHistory("User", text);

        java.util.List<Part> parts = new java.util.ArrayList<>();
        parts.add(Part.fromText(text));

        // Detect image paths in the input
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            String lowerToken = token.toLowerCase();
            if (lowerToken.endsWith(".jpg") || lowerToken.endsWith(".jpeg") || 
                lowerToken.endsWith(".png") || lowerToken.endsWith(".webp")) {
                try {
                    java.nio.file.Path imagePath = java.nio.file.Paths.get(token);
                    if (java.nio.file.Files.exists(imagePath)) {
                        byte[] rawBytes = java.nio.file.Files.readAllBytes(imagePath);
                        String mimeType = "image/jpeg";
                        if (lowerToken.endsWith(".png")) mimeType = "image/png";
                        else if (lowerToken.endsWith(".webp")) mimeType = "image/webp";
                        
                        parts.add(Part.builder().inlineData(
                            com.google.genai.types.Blob.builder()
                                .mimeType(mimeType)
                                .data(rawBytes)
                                .build()
                        ).build());
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        Content content = Content.builder()
                .role("user")
                .parts(parts)
                .build();

        // Run agent asynchronously
        Disposable d = runner.runAsync("Coordinator", session.id(), content)
                .observeOn(Schedulers.io()) // Process on IO thread
                .subscribe(
                        event -> {
                            String responseText = event.stringifyContent();
                            if (responseText != null) {
                                SwingUtilities.invokeLater(() -> appendText(responseText));
                            }
                        },
                        error -> {
                            SwingUtilities.invokeLater(() -> {
                                appendToHistory("Error", error.getMessage());
                                resetInput();
                            });
                            error.printStackTrace();
                        },
                        () -> {
                            SwingUtilities.invokeLater(() -> {
                                appendText("\n\n"); // End of message spacing
                                resetInput();
                            });
                        }
                );
    }

    private void resetInput() {
        inputField.setEnabled(true);
        sendButton.setEnabled(true);
        progressBar.setVisible(false);
        inputField.requestFocusInWindow();
    }

    private void appendToHistory(String role, String message) {
        chatHistory.append("\n[" + role + "]: " + message + "\n");
    }
    
    private void appendText(String text) {
        chatHistory.append(text);
    }
}
