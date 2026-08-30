package me.ash.resonance.album;

import android.net.Uri;

public record Album(long id, String name, String artist, int songCount, Uri artUri) {
}