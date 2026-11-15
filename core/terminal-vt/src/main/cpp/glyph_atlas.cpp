#include "glyph_atlas.h"

#include <algorithm>
#include <cstring>
#include <limits>

namespace termvr::vt {

namespace {
constexpr uint16_t kGlyphPaddingPx = 1;
}  // namespace

ShelfAtlasPage::ShelfAtlasPage(uint8_t page_id) : page_id_(page_id) {}

const GlyphAtlasEntry* ShelfAtlasPage::Find(const GlyphKey& key, uint64_t frame_id) {
    auto it = entries_.find(key);
    if (it == entries_.end()) {
        return nullptr;
    }
    if (it->second.shelf_index < shelves_.size()) {
        shelves_[it->second.shelf_index].last_used_frame = frame_id;
    }
    return &it->second;
}

bool ShelfAtlasPage::EvictOldestShelf(uint64_t current_frame) {
    int32_t victim_idx = -1;
    uint64_t oldest_frame = std::numeric_limits<uint64_t>::max();
    for (size_t i = 0; i < shelves_.size(); ++i) {
        const Shelf& s = shelves_[i];
        if (!s.active || s.keys.empty()) {
            continue;
        }
        // Prefer evicting shelves not touched on the current frame first.
        uint64_t score = (s.last_used_frame == current_frame)
            ? std::numeric_limits<uint64_t>::max() - 1ull
            : s.last_used_frame;
        if (score < oldest_frame) {
            oldest_frame = score;
            victim_idx = static_cast<int32_t>(i);
        }
    }
    if (victim_idx < 0) {
        return false;
    }

    Shelf& victim = shelves_[static_cast<size_t>(victim_idx)];
    for (const GlyphKey& k : victim.keys) {
        entries_.erase(k);
    }
    victim.keys.clear();
    victim.next_x = kGlyphPaddingPx;
    victim.last_used_frame = 0;
    victim.active = false;
    uint32_t band_area = static_cast<uint32_t>(victim.height) * static_cast<uint32_t>(kAtlasWidth);
    occupied_area_ = (occupied_area_ >= band_area) ? (occupied_area_ - band_area) : 0u;
    ++eviction_count_;
    return true;
}

int32_t ShelfAtlasPage::FindReusableOrAllocateShelf(uint16_t padded_h, uint64_t frame_id, bool* out_evicted) {
    uint64_t max_area = (static_cast<uint64_t>(kAtlasArea) * kEvictionOccupancyPercent) / 100ull;
    while (occupied_area_ > max_area) {
        if (!EvictOldestShelf(frame_id)) {
            break;
        }
        if (out_evicted != nullptr) {
            *out_evicted = true;
        }
    }

    // Reuse an empty evicted shelf whose band height fits the glyph cleanly.
    int32_t best_reusable = -1;
    uint16_t best_height = std::numeric_limits<uint16_t>::max();
    for (size_t i = 0; i < shelves_.size(); ++i) {
        const Shelf& s = shelves_[i];
        if (!s.active && s.height >= padded_h && s.height < best_height) {
            best_height = s.height;
            best_reusable = static_cast<int32_t>(i);
        }
    }
    if (best_reusable >= 0) {
        Shelf& s = shelves_[static_cast<size_t>(best_reusable)];
        s.active = true;
        s.next_x = kGlyphPaddingPx;
        s.last_used_frame = frame_id;
        occupied_area_ += static_cast<uint32_t>(s.height) * static_cast<uint32_t>(kAtlasWidth);
        return best_reusable;
    }

    if (static_cast<uint32_t>(next_shelf_y_) + static_cast<uint32_t>(padded_h) <= kAtlasHeight) {
        Shelf s;
        s.y = next_shelf_y_;
        s.height = padded_h;
        s.next_x = kGlyphPaddingPx;
        s.last_used_frame = frame_id;
        s.active = true;
        next_shelf_y_ = static_cast<uint16_t>(next_shelf_y_ + padded_h);
        occupied_area_ += static_cast<uint32_t>(s.height) * static_cast<uint32_t>(kAtlasWidth);
        shelves_.push_back(std::move(s));
        return static_cast<int32_t>(shelves_.size() - 1u);
    }

    // Atlas vertical budget exhausted: evict LRU shelves until one fits.
    while (EvictOldestShelf(frame_id)) {
        if (out_evicted != nullptr) {
            *out_evicted = true;
        }
        for (size_t i = 0; i < shelves_.size(); ++i) {
            Shelf& s = shelves_[i];
            if (!s.active && s.height >= padded_h) {
                s.active = true;
                s.next_x = kGlyphPaddingPx;
                s.last_used_frame = frame_id;
                occupied_area_ += static_cast<uint32_t>(s.height) * static_cast<uint32_t>(kAtlasWidth);
                return static_cast<int32_t>(i);
            }
        }
    }

    // Last resort: reset page completely so rendering never stalls on fragmented shelves.
    Reset();
    if (out_evicted != nullptr) {
        *out_evicted = true;
    }
    if (padded_h <= kAtlasHeight) {
        Shelf s;
        s.y = 0;
        s.height = padded_h;
        s.next_x = kGlyphPaddingPx;
        s.last_used_frame = frame_id;
        s.active = true;
        next_shelf_y_ = padded_h;
        occupied_area_ = static_cast<uint32_t>(padded_h) * static_cast<uint32_t>(kAtlasWidth);
        shelves_.push_back(std::move(s));
        return 0;
    }
    return -1;
}

bool ShelfAtlasPage::Insert(
    const GlyphKey& key,
    uint16_t width,
    uint16_t height,
    int16_t bearing_x,
    int16_t bearing_y,
    uint8_t span_cols,
    const uint8_t* pixels,
    uint64_t frame_id,
    GlyphAtlasEntry* out_entry,
    bool* out_evicted) {
    if (out_evicted != nullptr) {
        *out_evicted = false;
    }
    if (const GlyphAtlasEntry* existing = Find(key, frame_id)) {
        if (out_entry != nullptr) {
            *out_entry = *existing;
        }
        return true;
    }

    if (width == 0 || height == 0 || pixels == nullptr) {
        GlyphAtlasEntry blank;
        blank.atlas_page = 0;
        blank.span_cols = span_cols;
        entries_[key] = blank;
        if (out_entry != nullptr) {
            *out_entry = blank;
        }
        return true;
    }

    uint16_t padded_w = static_cast<uint16_t>(std::min<uint32_t>(kAtlasWidth, width + kGlyphPaddingPx * 2u));
    uint16_t padded_h = static_cast<uint16_t>(std::min<uint32_t>(kAtlasHeight, height + kGlyphPaddingPx * 2u));

    int32_t chosen_idx = -1;
    uint16_t best_slack = std::numeric_limits<uint16_t>::max();
    for (size_t i = 0; i < shelves_.size(); ++i) {
        const Shelf& s = shelves_[i];
        if (!s.active) {
            continue;
        }
        if (s.height >= padded_h &&
            static_cast<uint32_t>(s.next_x) + static_cast<uint32_t>(padded_w) <= kAtlasWidth) {
            uint16_t slack = static_cast<uint16_t>(s.height - padded_h);
            if (slack < best_slack && slack <= 16u) {
                best_slack = slack;
                chosen_idx = static_cast<int32_t>(i);
            }
        }
    }

    if (chosen_idx < 0) {
        chosen_idx = FindReusableOrAllocateShelf(padded_h, frame_id, out_evicted);
        if (chosen_idx < 0) {
            return false;
        }
    }

    Shelf& shelf = shelves_[static_cast<size_t>(chosen_idx)];
    uint16_t dst_x = shelf.next_x;
    uint16_t dst_y = static_cast<uint16_t>(shelf.y + kGlyphPaddingPx);
    shelf.next_x = static_cast<uint16_t>(shelf.next_x + padded_w);
    shelf.last_used_frame = frame_id;
    shelf.keys.push_back(key);

    GlyphAtlasEntry entry;
    entry.atlas_page = page_id_;
    entry.uv_x = dst_x;
    entry.uv_y = dst_y;
    entry.uv_w = width;
    entry.uv_h = height;
    entry.bearing_x = bearing_x;
    entry.bearing_y = bearing_y;
    entry.span_cols = span_cols;
    entry.shelf_index = static_cast<uint32_t>(chosen_idx);

    entries_[key] = entry;

    AtlasUploadRect rect;
    rect.atlas_page = page_id_;
    rect.x = dst_x;
    rect.y = dst_y;
    rect.w = width;
    rect.h = height;
    size_t bpp = (page_id_ == 2) ? 4u : 1u;
    size_t byte_len = static_cast<size_t>(width) * static_cast<size_t>(height) * bpp;
    rect.pixels.resize(byte_len);
    std::memcpy(rect.pixels.data(), pixels, byte_len);
    pending_uploads_.push_back(std::move(rect));

    if (out_entry != nullptr) {
        *out_entry = entry;
    }
    return true;
}

void ShelfAtlasPage::Reset() {
    shelves_.clear();
    entries_.clear();
    pending_uploads_.clear();
    next_shelf_y_ = 0;
    occupied_area_ = 0;
    clear_pending_ = true;
}

std::vector<AtlasUploadRect> ShelfAtlasPage::DrainPendingUploads() {
    std::vector<AtlasUploadRect> out;
    out.swap(pending_uploads_);
    return out;
}

bool ShelfAtlasPage::ConsumeClearPending() noexcept {
    bool v = clear_pending_;
    clear_pending_ = false;
    return v;
}

GlyphAtlasManager::GlyphAtlasManager() : mono_page_(1), color_page_(2) {}

const GlyphAtlasEntry* GlyphAtlasManager::Find(const GlyphKey& key) {
    if (key.is_color != 0) {
        return color_page_.Find(key, frame_counter_);
    }
    return mono_page_.Find(key, frame_counter_);
}

bool GlyphAtlasManager::InsertMonoR8(
    const GlyphKey& key,
    uint16_t width,
    uint16_t height,
    int16_t bearing_x,
    int16_t bearing_y,
    uint8_t span_cols,
    const uint8_t* pixels_r8,
    GlyphAtlasEntry* out_entry) {
    bool evicted = false;
    bool ok = mono_page_.Insert(
        key, width, height, bearing_x, bearing_y, span_cols, pixels_r8,
        frame_counter_, out_entry, &evicted);
    if (evicted) {
        needs_visible_rebuild_ = true;
    }
    return ok;
}

bool GlyphAtlasManager::InsertColorRgba8(
    const GlyphKey& key,
    uint16_t width,
    uint16_t height,
    int16_t bearing_x,
    int16_t bearing_y,
    uint8_t span_cols,
    const uint8_t* pixels_rgba8,
    GlyphAtlasEntry* out_entry) {
    bool evicted = false;
    bool ok = color_page_.Insert(
        key, width, height, bearing_x, bearing_y, span_cols, pixels_rgba8,
        frame_counter_, out_entry, &evicted);
    if (evicted) {
        needs_visible_rebuild_ = true;
    }
    return ok;
}

void GlyphAtlasManager::InvalidateForContextLoss(uint64_t new_epoch) {
    context_epoch_ = new_epoch;
    mono_page_.Reset();
    color_page_.Reset();
    needs_visible_rebuild_ = true;
}

}  // namespace termvr::vt
