package com.mkpro.plugins;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ToolOutputPruner {
    public static final String TRUNCATION_MARKER_PREFIX = "... [TRUNCATED ";
    public static final String TRUNCATION_MARKER_SUFFIX = " characters to prevent context bloat] ...";

    public static String pruneText(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int headChars = (int) (maxChars * 0.6);
        int tailChars = maxChars - headChars;
        if (headChars + tailChars >= text.length()) {
            return text;
        }
        int truncatedCount = text.length() - (headChars + tailChars);
        return text.substring(0, headChars) + "\n\n" + TRUNCATION_MARKER_PREFIX + truncatedCount + TRUNCATION_MARKER_SUFFIX + "\n\n" + text.substring(text.length() - tailChars);
    }

    @SuppressWarnings("unchecked")
    public static Object prunePayloadObject(Object obj, int maxChars) {
        if (obj == null) return null;
        if (obj instanceof String) return pruneText((String) obj, maxChars);
        if (obj instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                result.put(entry.getKey(), prunePayloadObject(entry.getValue(), maxChars));
            }
            return result;
        }
        if (obj instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                result.add(prunePayloadObject(item, maxChars));
            }
            return result;
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    public static Content pruneContent(Content content, int maxChars) {
        if (content == null || !content.parts().isPresent() || content.parts().get().isEmpty()) {
            return content;
        }
        List<Part> originalParts = content.parts().get();
        List<Part> newParts = new ArrayList<>(originalParts.size());
        boolean changed = false;

        for (Part p : originalParts) {
            if (p.functionResponse().isPresent()) {
                FunctionResponse fn = p.functionResponse().get();
                if (fn.response().isPresent()) {
                    Map<String, Object> resp = fn.response().get();
                    Map<String, Object> prunedResp = (Map<String, Object>) prunePayloadObject(resp, maxChars);
                    if (!Objects.equals(resp, prunedResp)) {
                        FunctionResponse.Builder fnBuilder = FunctionResponse.builder().response(prunedResp);
                        fn.name().ifPresent(fnBuilder::name);
                        fn.id().ifPresent(fnBuilder::id);
                        FunctionResponse newFn = fnBuilder.build();
                        newParts.add(Part.builder().functionResponse(newFn).build());
                        changed = true;
                        continue;
                    }
                }
            } else if (p.text().isPresent()) {
                String txt = p.text().get();
                if (txt.length() > maxChars) {
                    newParts.add(Part.fromText(pruneText(txt, maxChars)));
                    changed = true;
                    continue;
                }
            }
            newParts.add(p);
        }
        if (!changed) return content;
        Content.Builder contentBuilder = Content.builder().parts(newParts);
        content.role().ifPresent(contentBuilder::role);
        return contentBuilder.build();
    }
}
