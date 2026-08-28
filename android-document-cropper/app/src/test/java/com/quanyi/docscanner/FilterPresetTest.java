package com.quanyi.docscanner;

import org.junit.Test;
import static org.junit.Assert.*;

public class FilterPresetTest {
    @Test public void idsAreStableAndNamed() {
        assertEquals(FilterPreset.ORIGINAL, FilterPreset.fromId(0));
        assertEquals(FilterPreset.CLEAR_DOCUMENT, FilterPreset.fromId(1));
        assertEquals(FilterPreset.ENHANCED_SHARP, FilterPreset.fromId(7));
        assertEquals(FilterPreset.CLEAR_DOCUMENT, FilterPreset.fromId(999));
        assertEquals("增強銳化", FilterPreset.ENHANCED_SHARP.label);
    }
}
