package com.example.mangatranslator.overlay

enum class FontScale(val multiplier: Float, val label: String) {
    COMPACT(0.85f, "Compact (85%)"),
    NORMAL(1.15f, "Normal (115%)"),
    LARGE(1.45f, "Large (145%)"),
    EXTRA_LARGE(1.8f, "Extra Large (180%)")
}
