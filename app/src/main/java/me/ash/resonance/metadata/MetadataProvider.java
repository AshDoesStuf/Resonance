package me.ash.resonance.metadata;

import me.ash.resonance.db.SongMetadataEntity;

public interface MetadataProvider {
    /**
     * Enriches the entity with data from the provider.
     * Should not throw exceptions; handle errors internally.
     */
    void enrich(String title, String artist, SongMetadataEntity entity);
}
