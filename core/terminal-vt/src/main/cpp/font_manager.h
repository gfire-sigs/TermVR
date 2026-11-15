#ifndef TERMVR_FONT_MANAGER_H
#define TERMVR_FONT_MANAGER_H

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ghostty_vt_api.h"
#include "glyph_atlas.h"
#include "termvr_vt_types.h"

namespace termvr::vt {

struct FontConfigNative {
    std::string family_id = "jetbrains-mono";
    std::string custom_font_path;
    std::string custom_font_sha256;
    float size_sp = 13.0f;
    float cell_height_multiplier = 1.15f;
    bool ligatures_enabled = true;
    bool nerd_font_symbols_enabled = true;
    float density = 2.0f;
};

struct BundledFontAssets {
    std::vector<uint8_t> jetbrains_regular;
    std::vector<uint8_t> jetbrains_bold;
    std::vector<uint8_t> jetbrains_italic;
    std::vector<uint8_t> jetbrains_bold_italic;
    std::vector<uint8_t> nerd_symbols;
};

struct ShapedRowCell {
    uint16_t grid_col = 0;
    uint8_t span_cols = 1;
    uint16_t style_flags = 0;
    uint32_t fg_rgba = 0;
    uint32_t bg_rgba = 0;
    uint32_t deco_rgba = 0;
    GlyphAtlasEntry glyph;
};

class FontManager {
public:
    FontManager();
    ~FontManager();

    FontManager(const FontManager&) = delete;
    FontManager& operator=(const FontManager&) = delete;

    void SetBundledAssets(BundledFontAssets assets);
    bool Configure(const FontConfigNative& config);

    CellMetrics Metrics() const noexcept { return metrics_; }
    const FontConfigNative& Config() const noexcept { return config_; }

    void ShapeRow(
        uint16_t row_index,
        const std::vector<ExtractedCell>& cells,
        int32_t cursor_col_if_on_row,
        CursorShape cursor_shape,
        uint32_t cursor_rgba,
        uint32_t selection_rgba,
        GlyphAtlasManager* atlas,
        std::vector<CellGpuInstance>* out_instances);

    bool EnsureGraphemeGlyph(
        const std::string& utf8,
        uint32_t primary_cp,
        uint16_t style_flags,
        uint8_t span_cols,
        GlyphAtlasManager* atlas,
        GlyphAtlasEntry* out_entry);

    bool HasTierLoaded(int tier) const noexcept;
    static bool IsBoxOrBlockOrBraille(uint32_t cp) noexcept;
    static bool IsNerdFontPua(uint32_t cp) noexcept;
    static bool IsCjkOrHangulWide(uint32_t cp) noexcept;
    static bool IsEmojiCodepoint(uint32_t cp, const std::string& utf8) noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    FontConfigNative config_;
    BundledFontAssets assets_;
    CellMetrics metrics_;

    void RecomputeMetrics();
    void DiscoverAndroidSystemFonts();
    bool RasterizeProceduralBoxBlockBraille(
        uint32_t cp,
        uint32_t cell_w,
        uint32_t cell_h,
        std::vector<uint8_t>* out_r8) const;
    bool RasterizeFallbackGlyph(
        const std::string& utf8,
        uint32_t cp,
        uint16_t style_flags,
        uint8_t span_cols,
        uint32_t cell_w,
        uint32_t cell_h,
        int32_t baseline_px,
        std::vector<uint8_t>* out_pixels,
        bool* out_is_color,
        uint16_t* out_w,
        uint16_t* out_h,
        int16_t* out_bearing_x,
        int16_t* out_bearing_y) const;
};

}  // namespace termvr::vt

#endif  // TERMVR_FONT_MANAGER_H
