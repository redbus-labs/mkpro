package com.mkpro.knowledge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TF-IDF based bag-of-words search index for topic reports.
 * Thread-safe implementation using ConcurrentHashMap for storage.
 */
public class TopicIndex {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "can", "may", "might", "shall", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "this", "that", "these",
            "those", "it", "its", "i", "me", "my", "we", "our", "you", "your",
            "he", "him", "his", "she", "her", "they", "them", "their", "what",
            "which", "who", "whom", "when", "where", "why", "how", "all", "each",
            "every", "both", "few", "more", "most", "other", "some", "such", "no",
            "not", "only", "same", "so", "than", "too", "very", "just", "also",
            "and", "but", "or", "if", "then", "else", "about", "up", "out", "off",
            "over", "under"
    );

    /** Stores TF vectors per topic: topicName -> (term -> tf) */
    private final ConcurrentHashMap<String, Map<String, Double>> tfVectors = new ConcurrentHashMap<>();

    /** Stores raw text per topic for snippet generation */
    private final ConcurrentHashMap<String, String> rawTexts = new ConcurrentHashMap<>();

    /** Global IDF values: term -> idf */
    private final ConcurrentHashMap<String, Double> idfValues = new ConcurrentHashMap<>();

    /**
     * Indexes a topic by tokenizing the text and computing its TF vector.
     *
     * @param topicName unique name for the topic
     * @param text      the text content to index
     */
    public void indexTopic(String topicName, String text) {
        if (topicName == null || text == null) {
            return;
        }
        List<String> tokens = tokenize(text);
        Map<String, Double> tfVector = computeTf(tokens);
        tfVectors.put(topicName, tfVector);
        rawTexts.put(topicName, text);
    }

    /**
     * Removes a topic from the index.
     *
     * @param topicName the topic to remove
     */
    public void removeTopic(String topicName) {
        if (topicName == null) {
            return;
        }
        tfVectors.remove(topicName);
        rawTexts.remove(topicName);
    }

    /**
     * Searches the index using the query string and returns top-K results
     * ranked by cosine similarity of TF-IDF vectors.
     *
     * @param query the search query
     * @param topK  maximum number of results to return
     * @return list of SearchResult ordered by descending score
     */
    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> queryTf = computeTf(queryTokens);
        Map<String, Double> queryTfIdf = applyIdf(queryTf);

        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : tfVectors.entrySet()) {
            String topicName = entry.getKey();
            Map<String, Double> docTf = entry.getValue();
            Map<String, Double> docTfIdf = applyIdf(docTf);

            double score = cosineSimilarity(queryTfIdf, docTfIdf);
            if (score > 0.0) {
                String snippet = generateSnippet(topicName);
                results.add(new SearchResult(topicName, score, snippet));
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));

        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    /**
     * Recomputes IDF values across all currently indexed documents.
     * Should be called after batch indexing operations.
     */
    public void rebuildIdf() {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalDocs = tfVectors.size();

        for (Map<String, Double> tfVector : tfVectors.values()) {
            for (String term : tfVector.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        ConcurrentHashMap<String, Double> newIdf = new ConcurrentHashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            // IDF = log(totalDocs / df) + 1 (smoothed)
            double idf = Math.log((double) totalDocs / entry.getValue()) + 1.0;
            newIdf.put(entry.getKey(), idf);
        }

        idfValues.clear();
        idfValues.putAll(newIdf);
    }

    /**
     * Returns the number of indexed topics.
     */
    public int size() {
        return tfVectors.size();
    }

    // --- Private helpers ---

    private List<String> tokenize(String text) {
        String[] parts = text.toLowerCase().split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= 2 && !STOPWORDS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private Map<String, Double> computeTf(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        int totalTokens = tokens.size();
        Map<String, Double> tf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            tf.put(entry.getKey(), (double) entry.getValue() / totalTokens);
        }
        return tf;
    }

    private Map<String, Double> applyIdf(Map<String, Double> tfVector) {
        Map<String, Double> tfIdf = new HashMap<>();
        for (Map.Entry<String, Double> entry : tfVector.entrySet()) {
            String term = entry.getKey();
            double tf = entry.getValue();
            double idf = idfValues.getOrDefault(term, 1.0);
            tfIdf.put(term, tf * idf);
        }
        return tfIdf;
    }

    private double cosineSimilarity(Map<String, Double> vecA, Map<String, Double> vecB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        Set<String> allTerms = new HashSet<>(vecA.keySet());
        allTerms.addAll(vecB.keySet());

        for (String term : allTerms) {
            double a = vecA.getOrDefault(term, 0.0);
            double b = vecB.getOrDefault(term, 0.0);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String generateSnippet(String topicName) {
        String text = rawTexts.get(topicName);
        if (text == null) {
            return "";
        }
        if (text.length() <= 200) {
            return text;
        }
        return text.substring(0, 200);
    }

    // --- Inner class ---

    /**
     * Represents a search result with topic name, relevance score, and text snippet.
     */
    public static class SearchResult {
        private final String topicName;
        private final double score;
        private final String snippet;

        public SearchResult(String topicName, double score, String snippet) {
            this.topicName = topicName;
            this.score = score;
            this.snippet = snippet;
        }

        public String getTopicName() {
            return topicName;
        }

        public double getScore() {
            return score;
        }

        public String getSnippet() {
            return snippet;
        }

        @Override
        public String toString() {
            return String.format("SearchResult{topic='%s', score=%.4f, snippet='%s'}",
                    topicName, score, snippet.length() > 50 ? snippet.substring(0, 50) + "..." : snippet);
        }
    }
}
