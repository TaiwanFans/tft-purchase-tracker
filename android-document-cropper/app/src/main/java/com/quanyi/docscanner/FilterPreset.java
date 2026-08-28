package com.quanyi.docscanner;

public enum FilterPreset {
    ORIGINAL(0, "原色"),
    CLEAR_DOCUMENT(1, "清晰文件"),
    SOFT_WHITE(2, "柔白文件"),
    BLACK_WHITE(3, "黑白掃描"),
    TEXT_SHARP(4, "文字銳利"),
    NATURAL_GRAY(5, "自然灰階"),
    GRAY_CLEAR(6, "灰階清晰"),
    ENHANCED_SHARP(7, "增強銳化");

    public final int id;
    public final String label;
    FilterPreset(int id, String label) { this.id = id; this.label = label; }

    public static FilterPreset fromId(int id) {
        for (FilterPreset preset : values()) if (preset.id == id) return preset;
        return CLEAR_DOCUMENT;
    }
}
