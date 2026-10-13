package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lexical relevance for ticket search and recurring-pattern keywords: TF-IDF with cosine
 * similarity, computed in memory over the filtered set. No external service and no embeddings --
 * ticket text is customer data and never leaves the process on this path.
 *
 * <p>It matches WORDS, not meaning: "decline" finds "decline" and its inflections via a light
 * suffix strip, not "rejected". The UI labels it as relevance search, not semantic search.
 */
public final class TextRelevance {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Indonesian + English filler that carries no signal in a ticket subject. */
    private static final Set<String> STOP = Set.of(
            "yang", "dan", "di", "ke", "dari", "pada", "untuk", "dengan", "ini", "itu", "atau",
            "dalam", "saat", "karena", "oleh", "ada", "tidak", "akan", "the", "and", "for", "of",
            "to", "in", "on", "is", "are", "a", "an", "at", "by", "with", "mengalami", "terjadi");

    private TextRelevance() {
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (raw.length() < 2 || STOP.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    /** Strips a few common suffixes so "timeouts"/"timeout" and "declined"/"decline" meet. */
    private static String stem(String word) {
        for (String suffix : new String[] {"nya", "kan", "ing", "ed", "es", "s"}) {
            if (word.length() > suffix.length() + 3 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    /** A ranked hit: index into the document list and its cosine score in (0, 1]. */
    public record Hit(int index, double score) {
    }

    /** Top {@code limit} documents for {@code query}, best first; ties break on lower index. */
    public static List<Hit> search(List<String> documents, String query, int limit) {
        List<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> termFreqs = new ArrayList<>();
        Map<String, Integer> docFreq = new HashMap<>();
        for (String doc : documents) {
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens(doc)) {
                tf.merge(token, 1, Integer::sum);
            }
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
            termFreqs.add(tf);
        }
        int n = documents.size();
        Map<String, Double> queryVec = new HashMap<>();
        for (String token : queryTokens) {
            queryVec.merge(token, idf(n, docFreq.getOrDefault(token, 0)), Double::sum);
        }
        double queryNorm = norm(queryVec.values());

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            double dot = 0;
            double docNorm = 0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double weight = e.getValue() * idf(n, docFreq.get(e.getKey()));
                docNorm += weight * weight;
                Double q = queryVec.get(e.getKey());
                if (q != null) {
                    dot += weight * q;
                }
            }
            if (dot > 0 && docNorm > 0) {
                hits.add(new Hit(i, dot / (Math.sqrt(docNorm) * queryNorm)));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparingInt(Hit::index));
        return hits.size() > limit ? List.copyOf(hits.subList(0, Math.max(limit, 0)))
                : List.copyOf(hits);
    }

    /** The {@code limit} most frequent distinct tokens across {@code texts}. */
    public static List<String> keywords(List<String> texts, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (String text : texts) {
            Set<String> once = new HashSet<>(tokens(text));
            once.forEach(t -> counts.merge(t, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    private static double idf(int n, int df) {
        return Math.log(1.0 + (double) n / (1 + df));
    }

    private static double norm(java.util.Collection<Double> values) {
        double sum = 0;
        for (double v : values) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }
}
