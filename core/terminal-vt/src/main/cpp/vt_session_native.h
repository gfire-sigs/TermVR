#ifndef TERMVR_VT_SESSION_NATIVE_H
#define TERMVR_VT_SESSION_NATIVE_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "ghostty_vt_api.h"
#include "termvr_vt_types.h"

namespace termvr::vt {

constexpr size_t kMaxVtWriteChunkBytes = 64u * 1024u;
constexpr uint32_t kDefaultScrollbackLines = 10000u;

struct ExtractedFrameSnapshot {
    uint16_t cols = 0;
    uint16_t rows = 0;
    bool full_redraw = false;
    bool any_dirty = false;
    PaletteColors palette;
    TerminalCursorSnapshot cursor;
    std::vector<ExtractedRow> dirty_rows;
    uint64_t kitty_revision = 0;
    std::vector<KittyGraphicPlacement> kitty_placements;
};

class VtSessionNative {
public:
    VtSessionNative(uint16_t cols, uint16_t rows, uint32_t max_scrollback = kDefaultScrollbackLines);
    ~VtSessionNative();

    VtSessionNative(const VtSessionNative&) = delete;
    VtSessionNative& operator=(const VtSessionNative&) = delete;

    bool IsValid() const noexcept;
    void Close();

    bool ResizeGrid(uint16_t cols, uint16_t rows, uint32_t cell_width_px, uint32_t cell_height_px);
    void WritePtyBytes(const uint8_t* data, size_t len);
    std::vector<uint8_t> DrainPtyReplies();

    void SetPalette(const PaletteColors& palette);
    PaletteColors GetPalette() const;

    void SetScrollbackOffset(uint32_t lines_from_bottom);
    uint32_t GetScrollbackOffset() const;
    uint32_t GetScrollbackCount() const;

    void SetSelection(bool active, int32_t start_row, int32_t start_col, int32_t end_row, int32_t end_col);
    void ClearSelection();

    bool IsMouseTrackingActive() const;
    bool IsBracketedPasteActive() const;
    bool IsApplicationCursorActive() const;
    std::string GetWindowTitle() const;
    TerminalCursorSnapshot GetCursorSnapshot() const;

    std::vector<uint8_t> EncodeKey(const TvGhosttyKeyEvent& event);
    std::vector<uint8_t> EncodeMouse(const TvGhosttyMouseEvent& event);

    static bool IsPasteSafe(const char* data, size_t len);
    bool EncodePaste(const char* data, size_t len, bool allow_unsafe, std::vector<uint8_t>* out_bytes);

    std::string FormatText(
        bool emit_vt,
        bool use_selection,
        int32_t start_row,
        int32_t start_col,
        int32_t end_row,
        int32_t end_col,
        bool trim_trailing_ws,
        bool unwrap_soft_wraps);

    std::vector<SearchMatchNative> SearchScrollback(
        const std::string& query,
        bool use_regex,
        bool case_sensitive);

    bool DetectLinkAt(uint16_t viewport_row, uint16_t viewport_col, DetectedLinkNative* out_link);

    bool ExtractDirtyFrame(bool force_full, ExtractedFrameSnapshot* out_snapshot);

    bool IsDirty() const noexcept {
        return dirty_flag_.load(std::memory_order_acquire);
    }

    bool ConsumeGridResized() noexcept {
        return grid_resized_.exchange(false, std::memory_order_acq_rel);
    }

    uint16_t Cols() const noexcept { return cols_.load(std::memory_order_acquire); }
    uint16_t Rows() const noexcept { return rows_.load(std::memory_order_acquire); }

private:
    mutable std::mutex vt_mu_;
    TvGhosttyTerminal terminal_ = nullptr;
    TvGhosttyRenderState render_state_ = nullptr;
    TvGhosttyRenderStateRowIterator row_iter_ = nullptr;
    TvGhosttyRenderStateCellIterator cell_iter_ = nullptr;
    TvGhosttyKeyEncoder key_encoder_ = nullptr;
    TvGhosttyMouseEncoder mouse_encoder_ = nullptr;

    PaletteColors palette_;
    std::atomic<uint16_t> cols_{80};
    std::atomic<uint16_t> rows_{24};
    uint32_t cell_width_px_ = 10;
    uint32_t cell_height_px_ = 20;

    // Written under vt_mu_ with release semantics; consumed by render thread with acquire/acq_rel.
    std::atomic<bool> dirty_flag_{true};
    std::atomic<bool> grid_resized_{true};
    std::atomic<bool> closed_{false};

    uint64_t last_seen_kitty_rev_ = 0;

    uint32_t ResolveColor(const TvGhosttyColorValue& c, uint32_t default_rgba) const noexcept;
};

}  // namespace termvr::vt

#endif  // TERMVR_VT_SESSION_NATIVE_H
