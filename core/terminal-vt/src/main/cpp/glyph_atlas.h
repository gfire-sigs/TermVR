#ifndef TERMVR_GLYPH_ATLAS_H
#define TERMVR_GLYPH_ATLAS_H

#include <cstdint>
#include <unordered_map>
#include <vector>

namespace termvr::vt {

constexpr uint16_t kAtlasWidth = 2048;
constexpr uint16_t kAtlasHeight = 2048;
constexpr uint32_t kAtlasArea = static_cast<uint32_t>(kAtlasWidth) * static_cast<uint32_t>(kAtlasHeight);
constexpr uint8_t kEvictionOccupancyPercent = 90;

struct GlyphKey {
    uint64_t cluster_hash = 0;
    uint32_t codepoint = 0;
    uint16_t font_size_px = 0;
    uint16_t style_bits = 0;  // bold, italic, tier hint
    uint8_t span_cols = 1;
    uint8_t is_color = 0;

    bool operator==(const GlyphKey& other) const noexcept {
        return cluster_hash == other.cluster_hash &&
               codepoint == other.codepoint &&
               font_size_px == other.font_size_px &&
               style_bits == other.style_bits &&
               span_cols == other.span_cols &&
               is_color == other.is_color;
    }
};

struct GlyphKeyHash {
    size_t operator()(const GlyphKey& k) const noexcept {
        uint64_t h = k.cluster_hash * 11400714819323198485ull;
        h ^= static_cast<uint64_t>(k.codepoint) * 0x9E3779B97F4A7C15ull;
        h ^= (static_cast<uint64_t>(k.font_size_px) << 32u) |
             (static_cast<uint64_t>(k.style_bits) << 16u) |
             (static_cast<uint64_t>(k.span_cols) << 8u) |
             static_cast<uint64_t>(k.is_color);
        return static_cast<size_t>(h ^ (h >> 33u));
    }
};

struct GlyphAtlasEntry {
    uint8_t atlas_page = 0;  // 1 = R8, 2 = RGBA8
    uint16_t uv_x = 0;
    uint16_t uv_y = 0;
    uint16_t uv_w = 0;
    uint16_t uv_h = 0;
    int16_t bearing_x = 0;
    int16_t bearing_y = 0;
    uint8_t span_cols = 1;
    uint32_t shelf_index = 0;
};

struct AtlasUploadRect {
    uint8_t atlas_page = 1;
    uint16_t x = 0;
    uint16_t y = 0;
    uint16_t w = 0;
    uint16_t h = 0;
    std::vector<uint8_t> pixels;
};

class ShelfAtlasPage {
public:
    explicit ShelfAtlasPage(uint8_t page_id);

    const GlyphAtlasEntry* Find(const GlyphKey& key, uint64_t frame_id);

    bool Insert(
        const GlyphKey& key,
        uint16_t width,
        uint16_t height,
        int16_t bearing_x,
        int16_t bearing_y,
        uint8_t span_cols,
        const uint8_t* pixels,
        uint64_t frame_id,
        GlyphAtlasEntry* out_entry,
        bool* out_evicted);

    void Reset();

    uint32_t OccupiedArea() const noexcept { return occupied_area_; }
    float OccupancyRatio() const noexcept {
        return static_cast<float>(occupied_area_) / static_cast<float>(kAtlasArea);
    }
    size_t EntryCount() const noexcept { return entries_.size(); }
    size_t ShelfCount() const noexcept { return shelves_.size(); }
    uint64_t EvictionCount() const noexcept { return eviction_count_; }

    std::vector<AtlasUploadRect> DrainPendingUploads();
    bool ConsumeClearPending() noexcept;

private:
    struct Shelf {
        uint16_t y = 0;
        uint16_t height = 0;
        uint16_t next_x = 0;
        uint64_t last_used_frame = 0;
        bool active = true;
        std::vector<GlyphKey> keys;
    };

    uint8_t page_id_ = 1;
    uint16_t next_shelf_y_ = 0;
    uint32_t occupied_area_ = 0;
    uint64_t eviction_count_ = 0;
    bool clear_pending_ = true;

    std::vector<Shelf> shelves_;
    std::unordered_map<GlyphKey, GlyphAtlasEntry, GlyphKeyHash> entries_;
    std::vector<AtlasUploadRect> pending_uploads_;

    bool EvictOldestShelf(uint64_t current_frame);
    int32_t FindReusableOrAllocateShelf(uint16_t padded_h, uint64_t frame_id, bool* out_evicted);
};

class GlyphAtlasManager {
public:
    GlyphAtlasManager();

    const GlyphAtlasEntry* Find(const GlyphKey& key);

    bool InsertMonoR8(
        const GlyphKey& key,
        uint16_t width,
        uint16_t height,
        int16_t bearing_x,
        int16_t bearing_y,
        uint8_t span_cols,
        const uint8_t* pixels_r8,
        GlyphAtlasEntry* out_entry);

    bool InsertColorRgba8(
        const GlyphKey& key,
        uint16_t width,
        uint16_t height,
        int16_t bearing_x,
        int16_t bearing_y,
        uint8_t span_cols,
        const uint8_t* pixels_rgba8,
        GlyphAtlasEntry* out_entry);

    void BeginFrame() noexcept { ++frame_counter_; }
    uint64_t CurrentFrame() const noexcept { return frame_counter_; }

    void InvalidateForContextLoss(uint64_t new_epoch);
    uint64_t ContextEpoch() const noexcept { return context_epoch_; }

    bool ConsumeNeedsRebuild() noexcept {
        bool v = needs_visible_rebuild_;
        needs_visible_rebuild_ = false;
        return v;
    }

    ShelfAtlasPage& MonoPage() noexcept { return mono_page_; }
    ShelfAtlasPage& ColorPage() noexcept { return color_page_; }
    const ShelfAtlasPage& MonoPage() const noexcept { return mono_page_; }
    const ShelfAtlasPage& ColorPage() const noexcept { return color_page_; }

private:
    ShelfAtlasPage mono_page_;
    ShelfAtlasPage color_page_;
    uint64_t frame_counter_ = 1;
    uint64_t context_epoch_ = 1;
    bool needs_visible_rebuild_ = false;
};

}  // namespace termvr::vt

#endif  // TERMVR_GLYPH_ATLAS_H
