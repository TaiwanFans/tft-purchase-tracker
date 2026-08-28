package com.quanyi.docscanner;

import android.graphics.Bitmap;

/** Semantic filter facade. UI/export code never encodes filter meaning as booleans. */
public final class DocumentFilterEngine {
    public Bitmap apply(Bitmap input, FilterPreset preset) {
        if (input == null || input.isRecycled()) return input;
        if (preset == null) preset = FilterPreset.CLEAR_DOCUMENT;
        switch (preset) {
            case ORIGINAL: return input.copy(Bitmap.Config.ARGB_8888, false);
            case SOFT_WHITE: return ImageUtils.softWhiteDocument(input);
            case BLACK_WHITE: return ImageUtils.blackWhiteScan(input);
            case TEXT_SHARP: return ImageUtils.textSharp(input);
            case NATURAL_GRAY: return ImageUtils.naturalGray(input);
            case GRAY_CLEAR: return ImageUtils.grayClear(input);
            case ENHANCED_SHARP: return ImageUtils.enhancedSharpen(input);
            case CLEAR_DOCUMENT:
            default: return ImageUtils.clearDocument(input);
        }
    }
}
