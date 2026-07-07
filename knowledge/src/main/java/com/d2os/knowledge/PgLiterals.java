package com.d2os.knowledge;

import java.util.List;

/**
 * Builds the two Postgres literal strings that JPA/JdbcTemplate cannot bind as typed params here:
 * a {@code vector} literal for pgvector and a {@code text[]} array literal. Both are bound as plain
 * {@code String} params and cast in SQL ({@code ?::vector}, {@code ?::text[]}) by the callers
 * ({@link KnowledgeRetrievalService}, {@link EmbeddingIndexer}).
 */
final class PgLiterals {

    private PgLiterals() {}

    /** pgvector literal: {@code [f0,f1,...]}. */
    static String vector(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Postgres {@code text[]} array literal: {@code {"tag1","tag2"}}, each element quoted and escaped. */
    static String textArray(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values.get(i)));
        }
        sb.append('}');
        return sb.toString();
    }

    /** Quote + escape one array element (backslash and double-quote must be escaped inside {@code {...}}). */
    private static String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
