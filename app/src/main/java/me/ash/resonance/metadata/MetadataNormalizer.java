package me.ash.resonance.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MetadataNormalizer {

    private static final Map<String, String> GENRE_MAPPING = new HashMap<>();
    private static final Set<String> JUNK_TAGS = new HashSet<>();

    static {
        // Normalization mappings
        GENRE_MAPPING.put("hip-hop", "hip hop");
        GENRE_MAPPING.put("hiphop", "hip hop");
        GENRE_MAPPING.put("rap", "hip hop");
        GENRE_MAPPING.put("r&b", "rnb");
        GENRE_MAPPING.put("r n b", "rnb");
        GENRE_MAPPING.put("rock n roll", "rock");
        GENRE_MAPPING.put("prog rock", "progressive rock");

        // Junk tags to ignore
        JUNK_TAGS.addAll(Arrays.asList(
            "seen live", "favorite", "awesome", "male vocalist", "female vocalist",
            "my favorites", "love", "beautiful", "favorites", "best", "cool",
            "chill", "relax", "music", "songs", "tracks"
        ));
    }

    public static String normalizeGenre(String tag) {
        if (tag == null) return null;
        String lower = tag.toLowerCase().trim();
        if (JUNK_TAGS.contains(lower)) return null;
        return GENRE_MAPPING.getOrDefault(lower, lower);
    }

    public static List<String> normalizeTags(List<String> tags) {
        if (tags == null) return Collections.emptyList();
        Set<String> normalized = new HashSet<>();
        for (String tag : tags) {
            String norm = normalizeGenre(tag);
            if (norm != null && !norm.isEmpty()) {
                normalized.add(norm);
            }
        }
        List<String> result = new ArrayList<>(normalized);
        Collections.sort(result);
        return result;
    }
}
