#include "ghostty_vt_api.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <regex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

using termvr::vt::CursorShape;
using termvr::vt::DetectedLinkKind;
using termvr::vt::DetectedLinkNative;
using termvr::vt::KittyGraphicPlacement;
using termvr::vt::PackUnderlineBits;
using termvr::vt::SearchMatchNative;
using termvr::vt::TerminalCursorSnapshot;
using termvr::vt::UnderlineStyle;

constexpr uint32_t kDefaultMaxScrollbackLines = 10000u;
constexpr size_t kMaxCsiParams = 32;
constexpr size_t kMaxOscPayload = 65536;
constexpr size_t kMaxApcPayload = 4 * 1024 * 1024;

struct CellData {
    std::string grapheme;
    uint32_t primary_cp = 0x20u;
    uint8_t width = 1;
    bool continuation = false;
    uint16_t style_flags = 0;
    TvGhosttyColorValue fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    uint32_t link_id = 0;
};

struct RowData {
    std::vector<CellData> cells;
    bool soft_wrapped = false;

    explicit RowData(uint16_t cols = 80) : cells(cols) {}
};

struct SavedCursorState {
    uint16_t col = 0;
    uint16_t row = 0;
    uint16_t style_flags = 0;
    TvGhosttyColorValue fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    bool origin_mode = false;
    bool autowrap = true;
};

enum class ParserState : uint8_t {
    kGround = 0,
    kEscape = 1,
    kEscapeIntermediate = 2,
    kCsiEntry = 3,
    kOscString = 4,
    kApcString = 5,
    kDcsIgnore = 6,
};

struct StoredKittyImage {
    uint32_t image_id = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> rgba;
};

int CodepointColumnWidth(uint32_t cp) noexcept {
    if (cp == 0) {
        return 0;
    }
    if (cp < 0x20u || (cp >= 0x7Fu && cp < 0xA0u)) {
        return 0;
    }
    if ((cp >= 0x0300u && cp <= 0x036Fu) ||
        (cp >= 0x1AB0u && cp <= 0x1AFFu) ||
        (cp >= 0x1DC0u && cp <= 0x1DFFu) ||
        (cp >= 0x20D0u && cp <= 0x20FFu) ||
        cp == 0x200Du ||
        (cp >= 0xFE00u && cp <= 0xFE0Fu) ||
        (cp >= 0xFE20u && cp <= 0xFE2Fu) ||
        (cp >= 0x1F3FBu && cp <= 0x1F3FFu)) {
        return 0;
    }
    if ((cp >= 0x1100u && cp <= 0x115Fu) ||
        cp == 0x2329u || cp == 0x232Au ||
        (cp >= 0x2E80u && cp <= 0x303Eu) ||
        (cp >= 0x3040u && cp <= 0x33BFu) ||
        (cp >= 0x3400u && cp <= 0x4DBFu) ||
        (cp >= 0x4E00u && cp <= 0xA4CFu) ||
        (cp >= 0xA960u && cp <= 0xA97Cu) ||
        (cp >= 0xAC00u && cp <= 0xD7A3u) ||
        (cp >= 0xF900u && cp <= 0xFAFFu) ||
        (cp >= 0xFE10u && cp <= 0xFE19u) ||
        (cp >= 0xFE30u && cp <= 0xFE6Bu) ||
        (cp >= 0xFF01u && cp <= 0xFF60u) ||
        (cp >= 0xFFE0u && cp <= 0xFFE6u) ||
        (cp >= 0x1F300u && cp <= 0x1F64Fu) ||
        (cp >= 0x1F680u && cp <= 0x1F6FFu) ||
        (cp >= 0x1F900u && cp <= 0x1FAFFu) ||
        (cp >= 0x20000u && cp <= 0x2FA1Fu)) {
        return 2;
    }
    return 1;
}

void AppendUtf8(uint32_t cp, std::string* out) {
    if (cp <= 0x7Fu) {
        out->push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FFu) {
        out->push_back(static_cast<char>(0xC0u | ((cp >> 6) & 0x1Fu)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0xFFFFu) {
        out->push_back(static_cast<char>(0xE0u | ((cp >> 12) & 0x0Fu)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0x10FFFFu) {
        out->push_back(static_cast<char>(0xF0u | ((cp >> 18) & 0x07u)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
}

void Palette256ToRgb(uint8_t idx, uint8_t* r, uint8_t* g, uint8_t* b) noexcept {
    static constexpr uint8_t kAnsi16[16][3] = {
        {0x16, 0x1B, 0x22}, {0xFF, 0x7B, 0x72}, {0x3F, 0xB9, 0x50}, {0xD2, 0x99, 0x22},
        {0x58, 0xA6, 0xFF}, {0xBC, 0x8C, 0xFF}, {0x39, 0xC5, 0xCF}, {0xB1, 0xBA, 0xC4},
        {0x48, 0x4F, 0x58}, {0xFF, 0xA1, 0x98}, {0x56, 0xD3, 0x64}, {0xE3, 0xB3, 0x41},
        {0x79, 0xC0, 0xFF}, {0xD2, 0xA8, 0xFF}, {0x56, 0xD4, 0xDD}, {0xF0, 0xF6, 0xFC},
    };
    if (idx < 16u) {
        *r = kAnsi16[idx][0];
        *g = kAnsi16[idx][1];
        *b = kAnsi16[idx][2];
        return;
    }
    if (idx < 232u) {
        uint8_t cube = static_cast<uint8_t>(idx - 16u);
        uint8_t rc = cube / 36u;
        uint8_t gc = (cube / 6u) % 6u;
        uint8_t bc = cube % 6u;
        auto scale = [](uint8_t v) -> uint8_t {
            return v == 0 ? 0 : static_cast<uint8_t>(55u + v * 40u);
        };
        *r = scale(rc);
        *g = scale(gc);
        *b = scale(bc);
        return;
    }
    uint8_t gray = static_cast<uint8_t>(8u + (idx - 232u) * 10u);
    *r = gray;
    *g = gray;
    *b = gray;
}

std::vector<uint8_t> DecodeBase64(const std::string& input) {
    std::vector<uint8_t> out;
    out.reserve((input.size() * 3u) / 4u);
    uint32_t accum = 0;
    int bits = 0;
    for (unsigned char ch : input) {
        int val = -1;
        if (ch >= 'A' && ch <= 'Z') {
            val = ch - 'A';
        } else if (ch >= 'a' && ch <= 'z') {
            val = ch - 'a' + 26;
        } else if (ch >= '0' && ch <= '9') {
            val = ch - '0' + 52;
        } else if (ch == '+') {
            val = 62;
        } else if (ch == '/') {
            val = 63;
        } else if (ch == '=') {
            break;
        } else {
            continue;
        }
        accum = (accum << 6u) | static_cast<uint32_t>(val);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((accum >> bits) & 0xFFu));
        }
    }
    return out;
}

}  // namespace

struct TvGhosttyTerminalImpl {
    uint16_t cols = 80;
    uint16_t rows = 24;
    uint32_t cell_width_px = 10;
    uint32_t cell_height_px = 20;
    uint32_t max_scrollback = kDefaultMaxScrollbackLines;

    std::deque<RowData> scrollback;
    std::vector<RowData> primary_grid;
    std::vector<RowData> alt_grid;
    bool using_alt_screen = false;

    uint16_t cursor_col = 0;
    uint16_t cursor_row = 0;
    bool pending_wrap = false;
    bool cursor_visible = true;
    bool cursor_blink = true;
    CursorShape cursor_shape = CursorShape::kBlock;

    uint16_t cur_style = 0;
    TvGhosttyColorValue cur_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue cur_bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    TvGhosttyColorValue cur_deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
    uint32_t cur_link_id = 0;

    uint16_t scroll_top = 0;
    uint16_t scroll_bottom = 23;
    std::vector<bool> tab_stops;

    SavedCursorState saved_primary;
    SavedCursorState saved_alt;

    bool mode_decckm = false;
    bool mode_origin = false;
    bool mode_autowrap = true;
    bool mode_bracketed_paste = false;
    uint16_t mouse_tracking_mode = 0;  // 0, 1000, 1002, 1003
    uint16_t mouse_format_mode = 0;    // 0 (X10), 1005 (UTF-8), 1006 (SGR)
    int modify_other_keys_level = 0;
    std::vector<uint32_t> kitty_kbd_stack_primary;
    std::vector<uint32_t> kitty_kbd_stack_alt;

    uint32_t scrollback_offset = 0;

    bool selection_active = false;
    int32_t sel_start_abs_row = 0;
    int32_t sel_start_col = 0;
    int32_t sel_end_abs_row = 0;
    int32_t sel_end_col = 0;

    bool full_dirty = true;
    std::vector<bool> dirty_rows;

    ParserState parser_state = ParserState::kGround;
    bool esc_saw_backslash_prefix = false;
    std::string csi_buf;
    std::string osc_buf;
    std::string apc_buf;

    uint32_t utf8_codepoint = 0;
    uint8_t utf8_expected_cont = 0;
    uint32_t utf8_min_cp = 0;
    uint32_t last_printed_cp = 0;
    uint16_t last_printed_row = 0;
    uint16_t last_printed_col = 0;
    bool last_was_zwj = false;

    std::string window_title;
    std::unordered_map<uint32_t, std::string> osc8_links;
    uint32_t next_link_id = 1;

    std::map<uint32_t, StoredKittyImage> kitty_images;
    std::vector<KittyGraphicPlacement> kitty_placements;
    std::string kitty_chunk_b64;
    std::map<std::string, std::string> kitty_chunk_params;
    uint32_t next_kitty_id = 1;
    uint64_t kitty_revision = 0;

    TvGhosttyTerminalImpl(uint16_t init_cols, uint16_t init_rows, uint32_t max_sb)
        : cols(std::max<uint16_t>(1, init_cols)),
          rows(std::max<uint16_t>(1, init_rows)),
          max_scrollback(max_sb == 0 ? kDefaultMaxScrollbackLines : max_sb),
          primary_grid(rows, RowData(cols)),
          alt_grid(rows, RowData(cols)),
          scroll_top(0),
          scroll_bottom(static_cast<uint16_t>(rows - 1)),
          tab_stops(cols, false),
          dirty_rows(rows, true) {
        ResetTabStops();
    }

    std::vector<RowData>& ActiveGrid() noexcept {
        return using_alt_screen ? alt_grid : primary_grid;
    }

    const std::vector<RowData>& ActiveGrid() const noexcept {
        return using_alt_screen ? alt_grid : primary_grid;
    }

    std::vector<uint32_t>& ActiveKittyKbdStack() noexcept {
        return using_alt_screen ? kitty_kbd_stack_alt : kitty_kbd_stack_primary;
    }

    uint32_t ActiveKittyKbdFlags() const noexcept {
        const auto& st = using_alt_screen ? kitty_kbd_stack_alt : kitty_kbd_stack_primary;
        return st.empty() ? 0u : st.back();
    }

    void ResetTabStops() {
        tab_stops.assign(cols, false);
        for (uint16_t c = 8; c < cols; c = static_cast<uint16_t>(c + 8)) {
            tab_stops[c] = true;
        }
    }

    void MarkRowDirty(uint16_t r) noexcept {
        if (r < dirty_rows.size()) {
            dirty_rows[r] = true;
        }
    }

    void MarkAllDirty() noexcept {
        full_dirty = true;
        std::fill(dirty_rows.begin(), dirty_rows.end(), true);
    }

    CellData MakeBlankCell() const {
        CellData cell;
        cell.primary_cp = 0x20u;
        cell.width = 1;
        cell.continuation = false;
        cell.style_flags = 0;
        cell.fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
        cell.bg = cur_bg;
        cell.deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
        cell.link_id = 0;
        return cell;
    }

    void ClearWideNeighbors(RowData& row, uint16_t col) {
        if (col >= row.cells.size()) {
            return;
        }
        if (row.cells[col].continuation && col > 0) {
            row.cells[col - 1] = MakeBlankCell();
            row.cells[col] = MakeBlankCell();
        } else if (row.cells[col].width == 2 && static_cast<size_t>(col + 1) < row.cells.size()) {
            row.cells[col + 1] = MakeBlankCell();
        }
    }

    void ScrollUpRegion(uint16_t top, uint16_t bottom, uint16_t count) {
        auto& grid = ActiveGrid();
        if (top >= bottom || bottom >= rows) {
            return;
        }
        uint16_t lines = std::min<uint16_t>(count, static_cast<uint16_t>(bottom - top + 1));
        for (uint16_t i = 0; i < lines; ++i) {
            if (!using_alt_screen && top == 0) {
                scrollback.push_back(std::move(grid[0]));
                while (scrollback.size() > max_scrollback) {
                    scrollback.pop_front();
                    if (selection_active) {
                        --sel_start_abs_row;
                        --sel_end_abs_row;
                    }
                }
                if (scrollback_offset > 0 && scrollback_offset < scrollback.size()) {
                    ++scrollback_offset;
                }
            }
            for (uint16_t r = top; r < bottom; ++r) {
                grid[r] = std::move(grid[r + 1]);
            }
            grid[bottom] = RowData(cols);
            for (auto& c : grid[bottom].cells) {
                c = MakeBlankCell();
            }
        }
        for (uint16_t r = top; r <= bottom; ++r) {
            MarkRowDirty(r);
        }
        if (top == 0) {
            full_dirty = true;
        }
    }

    void ScrollDownRegion(uint16_t top, uint16_t bottom, uint16_t count) {
        auto& grid = ActiveGrid();
        if (top >= bottom || bottom >= rows) {
            return;
        }
        uint16_t lines = std::min<uint16_t>(count, static_cast<uint16_t>(bottom - top + 1));
        for (uint16_t i = 0; i < lines; ++i) {
            for (uint16_t r = bottom; r > top; --r) {
                grid[r] = std::move(grid[r - 1]);
            }
            grid[top] = RowData(cols);
            for (auto& c : grid[top].cells) {
                c = MakeBlankCell();
            }
        }
        for (uint16_t r = top; r <= bottom; ++r) {
            MarkRowDirty(r);
        }
    }

    void LineFeed() {
        pending_wrap = false;
        MarkRowDirty(cursor_row);
        if (cursor_row == scroll_bottom) {
            ScrollUpRegion(scroll_top, scroll_bottom, 1);
        } else if (cursor_row + 1 < rows) {
            ++cursor_row;
        }
        MarkRowDirty(cursor_row);
    }

    void ReverseIndex() {
        pending_wrap = false;
        MarkRowDirty(cursor_row);
        if (cursor_row == scroll_top) {
            ScrollDownRegion(scroll_top, scroll_bottom, 1);
        } else if (cursor_row > 0) {
            --cursor_row;
        }
        MarkRowDirty(cursor_row);
    }

    void PutCodepoint(uint32_t cp) {
        auto& grid = ActiveGrid();
        int w = CodepointColumnWidth(cp);
        if (w == 0) {
            if (last_printed_row < grid.size() && last_printed_col < grid[last_printed_row].cells.size()) {
                auto& prev = grid[last_printed_row].cells[last_printed_col];
                if (prev.grapheme.size() < 28) {
                    if (prev.grapheme.empty()) {
                        AppendUtf8(prev.primary_cp, &prev.grapheme);
                    }
                    AppendUtf8(cp, &prev.grapheme);
                    if (cp == 0x200Du) {
                        last_was_zwj = true;
                    } else if (cp == 0xFE0Fu && prev.width == 1 &&
                               static_cast<size_t>(last_printed_col + 1) < grid[last_printed_row].cells.size()) {
                        prev.width = 2;
                        auto& cont = grid[last_printed_row].cells[last_printed_col + 1];
                        cont = MakeBlankCell();
                        cont.continuation = true;
                        cont.width = 0;
                    }
                    MarkRowDirty(last_printed_row);
                }
            }
            return;
        }

        if (last_was_zwj && last_printed_row < grid.size() &&
            last_printed_col < grid[last_printed_row].cells.size()) {
            last_was_zwj = false;
            auto& prev = grid[last_printed_row].cells[last_printed_col];
            if (prev.grapheme.size() < 28) {
                AppendUtf8(cp, &prev.grapheme);
                MarkRowDirty(last_printed_row);
                return;
            }
        }
        last_was_zwj = false;

        if (pending_wrap && mode_autowrap) {
            grid[cursor_row].soft_wrapped = true;
            cursor_col = 0;
            LineFeed();
        }

        if (w == 2 && cursor_col + 1 >= cols) {
            if (cols == 1) {
                w = 1;
            } else if (mode_autowrap) {
                ClearWideNeighbors(grid[cursor_row], cursor_col);
                grid[cursor_row].cells[cursor_col] = MakeBlankCell();
                grid[cursor_row].soft_wrapped = true;
                MarkRowDirty(cursor_row);
                cursor_col = 0;
                LineFeed();
            } else {
                cursor_col = static_cast<uint16_t>(cols - 2);
            }
        }

        RowData& row = grid[cursor_row];
        ClearWideNeighbors(row, cursor_col);
        if (w == 2 && cursor_col + 1 < cols) {
            ClearWideNeighbors(row, static_cast<uint16_t>(cursor_col + 1));
        }

        CellData& dst = row.cells[cursor_col];
        dst.grapheme.clear();
        AppendUtf8(cp, &dst.grapheme);
        dst.primary_cp = cp;
        dst.width = static_cast<uint8_t>(w);
        dst.continuation = false;
        dst.style_flags = cur_style;
        dst.fg = cur_fg;
        dst.bg = cur_bg;
        dst.deco = cur_deco;
        dst.link_id = cur_link_id;

        last_printed_cp = cp;
        last_printed_row = cursor_row;
        last_printed_col = cursor_col;

        if (w == 2 && cursor_col + 1 < cols) {
            CellData& cont = row.cells[cursor_col + 1];
            cont = MakeBlankCell();
            cont.continuation = true;
            cont.width = 0;
            cont.style_flags = cur_style;
            cont.fg = cur_fg;
            cont.bg = cur_bg;
            cont.deco = cur_deco;
            cont.link_id = cur_link_id;
        }

        MarkRowDirty(cursor_row);

        if (cursor_col + w >= cols) {
            cursor_col = static_cast<uint16_t>(cols - 1);
            pending_wrap = mode_autowrap;
        } else {
            cursor_col = static_cast<uint16_t>(cursor_col + w);
            pending_wrap = false;
        }
    }

    void SaveCursor(SavedCursorState* slot) const noexcept {
        slot->col = cursor_col;
        slot->row = cursor_row;
        slot->style_flags = cur_style;
        slot->fg = cur_fg;
        slot->bg = cur_bg;
        slot->deco = cur_deco;
        slot->origin_mode = mode_origin;
        slot->autowrap = mode_autowrap;
    }

    void RestoreCursor(const SavedCursorState& slot) noexcept {
        MarkRowDirty(cursor_row);
        cursor_col = std::min<uint16_t>(slot.col, static_cast<uint16_t>(cols - 1));
        cursor_row = std::min<uint16_t>(slot.row, static_cast<uint16_t>(rows - 1));
        cur_style = slot.style_flags;
        cur_fg = slot.fg;
        cur_bg = slot.bg;
        cur_deco = slot.deco;
        mode_origin = slot.origin_mode;
        mode_autowrap = slot.autowrap;
        pending_wrap = false;
        MarkRowDirty(cursor_row);
    }

    void EnterAltScreen(bool clear_screen, bool save_cursor) {
        if (save_cursor) {
            SaveCursor(&saved_primary);
        }
        if (!using_alt_screen) {
            using_alt_screen = true;
            scrollback_offset = 0;
            if (clear_screen) {
                alt_grid.assign(rows, RowData(cols));
            }
            MarkAllDirty();
        }
    }

    void ExitAltScreen(bool restore_cursor) {
        if (using_alt_screen) {
            using_alt_screen = false;
            MarkAllDirty();
        }
        if (restore_cursor) {
            RestoreCursor(saved_primary);
        }
    }

    void ResetTerminal() {
        primary_grid.assign(rows, RowData(cols));
        alt_grid.assign(rows, RowData(cols));
        using_alt_screen = false;
        cursor_col = 0;
        cursor_row = 0;
        pending_wrap = false;
        cursor_visible = true;
        cursor_blink = true;
        cursor_shape = CursorShape::kBlock;
        cur_style = 0;
        cur_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
        cur_bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
        cur_deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
        cur_link_id = 0;
        scroll_top = 0;
        scroll_bottom = static_cast<uint16_t>(rows - 1);
        mode_decckm = false;
        mode_origin = false;
        mode_autowrap = true;
        mode_bracketed_paste = false;
        mouse_tracking_mode = 0;
        mouse_format_mode = 0;
        modify_other_keys_level = 0;
        kitty_kbd_stack_primary.clear();
        kitty_kbd_stack_alt.clear();
        scrollback_offset = 0;
        selection_active = false;
        kitty_placements.clear();
        ++kitty_revision;
        ResetTabStops();
        MarkAllDirty();
    }

    void ApplySgr(const std::string& raw_params) {
        if (raw_params.empty()) {
            cur_style = 0;
            cur_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
            cur_bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
            cur_deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
            return;
        }

        struct SgrGroup {
            std::vector<int> subs;
        };
        std::vector<SgrGroup> groups;
        size_t start = 0;
        while (start <= raw_params.size()) {
            size_t semi = raw_params.find(';', start);
            std::string token = semi == std::string::npos
                ? raw_params.substr(start)
                : raw_params.substr(start, semi - start);
            SgrGroup g;
            size_t cstart = 0;
            while (cstart <= token.size()) {
                size_t colon = token.find(':', cstart);
                std::string part = colon == std::string::npos
                    ? token.substr(cstart)
                    : token.substr(cstart, colon - cstart);
                int val = part.empty() ? 0 : std::atoi(part.c_str());
                g.subs.push_back(val);
                if (colon == std::string::npos) {
                    break;
                }
                cstart = colon + 1;
            }
            groups.push_back(std::move(g));
            if (semi == std::string::npos) {
                break;
            }
            start = semi + 1;
        }

        for (size_t i = 0; i < groups.size(); ++i) {
            const auto& subs = groups[i].subs;
            int p = subs.empty() ? 0 : subs[0];
            switch (p) {
                case 0:
                    cur_style = 0;
                    cur_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    cur_bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    cur_deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    break;
                case 1:
                    cur_style = static_cast<uint16_t>((cur_style & ~termvr::vt::kStyleDim) | termvr::vt::kStyleBold);
                    break;
                case 2:
                    cur_style = static_cast<uint16_t>((cur_style & ~termvr::vt::kStyleBold) | termvr::vt::kStyleDim);
                    break;
                case 3:
                    cur_style |= termvr::vt::kStyleItalic;
                    break;
                case 4: {
                    UnderlineStyle ustyle = UnderlineStyle::kSingle;
                    if (subs.size() >= 2) {
                        switch (subs[1]) {
                            case 0: ustyle = UnderlineStyle::kNone; break;
                            case 1: ustyle = UnderlineStyle::kSingle; break;
                            case 2: ustyle = UnderlineStyle::kDouble; break;
                            case 3: ustyle = UnderlineStyle::kCurly; break;
                            case 4: ustyle = UnderlineStyle::kDotted; break;
                            case 5: ustyle = UnderlineStyle::kDashed; break;
                            default: ustyle = UnderlineStyle::kSingle; break;
                        }
                    }
                    cur_style = static_cast<uint16_t>(
                        (cur_style & ~termvr::vt::kStyleUnderlineMask) | PackUnderlineBits(ustyle));
                    break;
                }
                case 5:
                case 6:
                    cur_style |= termvr::vt::kStyleBlink;
                    break;
                case 7:
                    cur_style |= termvr::vt::kStyleInverse;
                    break;
                case 9:
                    cur_style |= termvr::vt::kStyleStrikethrough;
                    break;
                case 21:
                    cur_style = static_cast<uint16_t>(
                        (cur_style & ~termvr::vt::kStyleUnderlineMask) | PackUnderlineBits(UnderlineStyle::kDouble));
                    break;
                case 22:
                    cur_style &= static_cast<uint16_t>(~(termvr::vt::kStyleBold | termvr::vt::kStyleDim));
                    break;
                case 23:
                    cur_style &= static_cast<uint16_t>(~termvr::vt::kStyleItalic);
                    break;
                case 24:
                    cur_style &= static_cast<uint16_t>(~termvr::vt::kStyleUnderlineMask);
                    break;
                case 25:
                    cur_style &= static_cast<uint16_t>(~termvr::vt::kStyleBlink);
                    break;
                case 27:
                    cur_style &= static_cast<uint16_t>(~termvr::vt::kStyleInverse);
                    break;
                case 29:
                    cur_style &= static_cast<uint16_t>(~termvr::vt::kStyleStrikethrough);
                    break;
                case 39:
                    cur_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    break;
                case 49:
                    cur_bg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    break;
                case 59:
                    cur_deco = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};
                    break;
                case 38:
                case 48:
                case 58: {
                    TvGhosttyColorValue* target = (p == 38) ? &cur_fg : (p == 48 ? &cur_bg : &cur_deco);
                    if (subs.size() >= 3 && subs[1] == 5) {
                        int idx = std::clamp(subs[2], 0, 255);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(static_cast<uint8_t>(idx), &r, &g, &b);
                        *target = {idx < 16 ? TV_GHOSTTY_COLOR_PALETTE : TV_GHOSTTY_COLOR_RGB,
                                   static_cast<uint8_t>(idx), r, g, b};
                    } else if (subs.size() >= 5 && subs[1] == 2) {
                        size_t base = subs.size() >= 6 ? 3 : 2;
                        uint8_t r = static_cast<uint8_t>(std::clamp(subs[base], 0, 255));
                        uint8_t g = static_cast<uint8_t>(std::clamp(subs[base + 1], 0, 255));
                        uint8_t b = static_cast<uint8_t>(std::clamp(subs[base + 2], 0, 255));
                        *target = {TV_GHOSTTY_COLOR_RGB, 0, r, g, b};
                    } else if (i + 2 < groups.size() && groups[i + 1].subs[0] == 5) {
                        int idx = std::clamp(groups[i + 2].subs[0], 0, 255);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(static_cast<uint8_t>(idx), &r, &g, &b);
                        *target = {idx < 16 ? TV_GHOSTTY_COLOR_PALETTE : TV_GHOSTTY_COLOR_RGB,
                                   static_cast<uint8_t>(idx), r, g, b};
                        i += 2;
                    } else if (i + 4 < groups.size() && groups[i + 1].subs[0] == 2) {
                        uint8_t r = static_cast<uint8_t>(std::clamp(groups[i + 2].subs[0], 0, 255));
                        uint8_t g = static_cast<uint8_t>(std::clamp(groups[i + 3].subs[0], 0, 255));
                        uint8_t b = static_cast<uint8_t>(std::clamp(groups[i + 4].subs[0], 0, 255));
                        *target = {TV_GHOSTTY_COLOR_RGB, 0, r, g, b};
                        i += 4;
                    }
                    break;
                }
                default:
                    if (p >= 30 && p <= 37) {
                        uint8_t idx = static_cast<uint8_t>(p - 30);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(idx, &r, &g, &b);
                        cur_fg = {TV_GHOSTTY_COLOR_PALETTE, idx, r, g, b};
                    } else if (p >= 90 && p <= 97) {
                        uint8_t idx = static_cast<uint8_t>(p - 90 + 8);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(idx, &r, &g, &b);
                        cur_fg = {TV_GHOSTTY_COLOR_PALETTE, idx, r, g, b};
                    } else if (p >= 40 && p <= 47) {
                        uint8_t idx = static_cast<uint8_t>(p - 40);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(idx, &r, &g, &b);
                        cur_bg = {TV_GHOSTTY_COLOR_PALETTE, idx, r, g, b};
                    } else if (p >= 100 && p <= 107) {
                        uint8_t idx = static_cast<uint8_t>(p - 100 + 8);
                        uint8_t r = 0, g = 0, b = 0;
                        Palette256ToRgb(idx, &r, &g, &b);
                        cur_bg = {TV_GHOSTTY_COLOR_PALETTE, idx, r, g, b};
                    }
                    break;
            }
        }
    }

    void DispatchCsi(char final_byte) {
        char prefix = 0;
        char intermediate = 0;
        size_t idx = 0;
        if (idx < csi_buf.size() && (csi_buf[idx] == '?' || csi_buf[idx] == '>' ||
                                     csi_buf[idx] == '<' || csi_buf[idx] == '=')) {
            prefix = csi_buf[idx++];
        }
        std::string param_str;
        while (idx < csi_buf.size()) {
            char ch = csi_buf[idx++];
            if (ch >= 0x20 && ch <= 0x2F) {
                intermediate = ch;
            } else {
                param_str.push_back(ch);
            }
        }

        if (prefix == 0 && intermediate == 0 && final_byte == 'm') {
            ApplySgr(param_str);
            return;
        }

        std::vector<int> params;
        if (!param_str.empty()) {
            size_t start = 0;
            while (start <= param_str.size() && params.size() < kMaxCsiParams) {
                size_t semi = param_str.find(';', start);
                std::string tok = semi == std::string::npos
                    ? param_str.substr(start)
                    : param_str.substr(start, semi - start);
                params.push_back(tok.empty() ? 0 : std::atoi(tok.c_str()));
                if (semi == std::string::npos) {
                    break;
                }
                start = semi + 1;
            }
        }
        auto param_or = [&](size_t i, int def_val) -> int {
            if (i >= params.size() || params[i] == 0) {
                return def_val;
            }
            return params[i];
        };

        if (prefix == '?' && (final_byte == 'h' || final_byte == 'l')) {
            bool enable = (final_byte == 'h');
            for (int mode : params) {
                switch (mode) {
                    case 1: mode_decckm = enable; break;
                    case 6:
                        mode_origin = enable;
                        MarkRowDirty(cursor_row);
                        cursor_col = 0;
                        cursor_row = enable ? scroll_top : 0;
                        pending_wrap = false;
                        MarkRowDirty(cursor_row);
                        break;
                    case 7: mode_autowrap = enable; break;
                    case 12: cursor_blink = enable; MarkRowDirty(cursor_row); break;
                    case 25: cursor_visible = enable; MarkRowDirty(cursor_row); break;
                    case 47:
                    case 1047:
                        if (enable) {
                            EnterAltScreen(mode == 1047, false);
                        } else {
                            ExitAltScreen(false);
                        }
                        break;
                    case 1049:
                        if (enable) {
                            EnterAltScreen(true, true);
                        } else {
                            ExitAltScreen(true);
                        }
                        break;
                    case 1000:
                    case 1002:
                    case 1003:
                        if (enable) {
                            mouse_tracking_mode = static_cast<uint16_t>(mode);
                        } else if (mouse_tracking_mode == static_cast<uint16_t>(mode)) {
                            mouse_tracking_mode = 0;
                        }
                        break;
                    case 1005:
                    case 1006:
                        if (enable) {
                            mouse_format_mode = static_cast<uint16_t>(mode);
                        } else if (mouse_format_mode == static_cast<uint16_t>(mode)) {
                            mouse_format_mode = 0;
                        }
                        break;
                    case 2004:
                        mode_bracketed_paste = enable;
                        break;
                    default:
                        break;
                }
            }
            return;
        }

        if (prefix == '>' && final_byte == 'm') {
            if (!params.empty() && params[0] == 4) {
                modify_other_keys_level = params.size() >= 2 ? params[1] : 0;
            }
            return;
        }

        if (final_byte == 'u') {
            auto& st = ActiveKittyKbdStack();
            if (prefix == '>') {
                uint32_t flags = params.empty() ? 0u : static_cast<uint32_t>(std::max(0, params[0]));
                if (st.size() >= 16) {
                    st.erase(st.begin());
                }
                st.push_back(flags);
                return;
            }
            if (prefix == '<') {
                int pop_n = param_or(0, 1);
                while (pop_n-- > 0 && !st.empty()) {
                    st.pop_back();
                }
                return;
            }
            if (prefix == '=') {
                uint32_t flags = params.empty() ? 0u : static_cast<uint32_t>(std::max(0, params[0]));
                int mode = param_or(1, 1);
                if (st.empty()) {
                    st.push_back(0u);
                }
                if (mode == 1) {
                    st.back() = flags;
                } else if (mode == 2) {
                    st.back() |= flags;
                } else if (mode == 3) {
                    st.back() &= ~flags;
                }
                return;
            }
        }

        if (intermediate == ' ' && final_byte == 'q') {
            int ps = params.empty() ? 0 : params[0];
            switch (ps) {
                case 0:
                case 1: cursor_shape = CursorShape::kBlock; cursor_blink = true; break;
                case 2: cursor_shape = CursorShape::kBlock; cursor_blink = false; break;
                case 3: cursor_shape = CursorShape::kUnderline; cursor_blink = true; break;
                case 4: cursor_shape = CursorShape::kUnderline; cursor_blink = false; break;
                case 5: cursor_shape = CursorShape::kBar; cursor_blink = true; break;
                case 6: cursor_shape = CursorShape::kBar; cursor_blink = false; break;
                default: break;
            }
            MarkRowDirty(cursor_row);
            return;
        }

        auto& grid = ActiveGrid();
        MarkRowDirty(cursor_row);
        switch (final_byte) {
            case 'A': {
                int n = param_or(0, 1);
                int min_row = (mode_origin && cursor_row >= scroll_top) ? scroll_top : 0;
                cursor_row = static_cast<uint16_t>(std::max<int>(min_row, static_cast<int>(cursor_row) - n));
                pending_wrap = false;
                break;
            }
            case 'B': {
                int n = param_or(0, 1);
                int max_row = (mode_origin && cursor_row <= scroll_bottom) ? scroll_bottom : (rows - 1);
                cursor_row = static_cast<uint16_t>(std::min<int>(max_row, static_cast<int>(cursor_row) + n));
                pending_wrap = false;
                break;
            }
            case 'C': {
                int n = param_or(0, 1);
                cursor_col = static_cast<uint16_t>(std::min<int>(cols - 1, static_cast<int>(cursor_col) + n));
                pending_wrap = false;
                break;
            }
            case 'D': {
                int n = param_or(0, 1);
                cursor_col = static_cast<uint16_t>(std::max<int>(0, static_cast<int>(cursor_col) - n));
                pending_wrap = false;
                break;
            }
            case 'E': {
                int n = param_or(0, 1);
                cursor_row = static_cast<uint16_t>(std::min<int>(rows - 1, static_cast<int>(cursor_row) + n));
                cursor_col = 0;
                pending_wrap = false;
                break;
            }
            case 'F': {
                int n = param_or(0, 1);
                cursor_row = static_cast<uint16_t>(std::max<int>(0, static_cast<int>(cursor_row) - n));
                cursor_col = 0;
                pending_wrap = false;
                break;
            }
            case 'G':
            case '`': {
                int col1 = param_or(0, 1);
                cursor_col = static_cast<uint16_t>(std::clamp(col1 - 1, 0, static_cast<int>(cols - 1)));
                pending_wrap = false;
                break;
            }
            case 'd': {
                int row1 = param_or(0, 1);
                int base = mode_origin ? scroll_top : 0;
                int limit = mode_origin ? scroll_bottom : (rows - 1);
                cursor_row = static_cast<uint16_t>(std::clamp(base + row1 - 1, base, limit));
                pending_wrap = false;
                break;
            }
            case 'H':
            case 'f': {
                int row1 = param_or(0, 1);
                int col1 = param_or(1, 1);
                int base = mode_origin ? scroll_top : 0;
                int limit = mode_origin ? scroll_bottom : (rows - 1);
                cursor_row = static_cast<uint16_t>(std::clamp(base + row1 - 1, base, limit));
                cursor_col = static_cast<uint16_t>(std::clamp(col1 - 1, 0, static_cast<int>(cols - 1)));
                pending_wrap = false;
                break;
            }
            case 'J': {
                int mode = params.empty() ? 0 : params[0];
                if (mode == 0) {
                    ClearWideNeighbors(grid[cursor_row], cursor_col);
                    for (uint16_t c = cursor_col; c < cols; ++c) {
                        grid[cursor_row].cells[c] = MakeBlankCell();
                    }
                    grid[cursor_row].soft_wrapped = false;
                    MarkRowDirty(cursor_row);
                    for (uint16_t r = static_cast<uint16_t>(cursor_row + 1); r < rows; ++r) {
                        for (auto& cell : grid[r].cells) {
                            cell = MakeBlankCell();
                        }
                        grid[r].soft_wrapped = false;
                        MarkRowDirty(r);
                    }
                } else if (mode == 1) {
                    for (uint16_t r = 0; r < cursor_row; ++r) {
                        for (auto& cell : grid[r].cells) {
                            cell = MakeBlankCell();
                        }
                        grid[r].soft_wrapped = false;
                        MarkRowDirty(r);
                    }
                    uint16_t end_c = std::min<uint16_t>(static_cast<uint16_t>(cursor_col + 1), cols);
                    if (end_c < cols) {
                        ClearWideNeighbors(grid[cursor_row], end_c);
                    }
                    for (uint16_t c = 0; c < end_c; ++c) {
                        grid[cursor_row].cells[c] = MakeBlankCell();
                    }
                    MarkRowDirty(cursor_row);
                } else if (mode == 2 || mode == 3) {
                    for (uint16_t r = 0; r < rows; ++r) {
                        for (auto& cell : grid[r].cells) {
                            cell = MakeBlankCell();
                        }
                        grid[r].soft_wrapped = false;
                    }
                    if (mode == 3 && !using_alt_screen) {
                        scrollback.clear();
                        scrollback_offset = 0;
                        selection_active = false;
                    }
                    MarkAllDirty();
                }
                break;
            }
            case 'K': {
                int mode = params.empty() ? 0 : params[0];
                RowData& row = grid[cursor_row];
                if (mode == 0) {
                    ClearWideNeighbors(row, cursor_col);
                    for (uint16_t c = cursor_col; c < cols; ++c) {
                        row.cells[c] = MakeBlankCell();
                    }
                    row.soft_wrapped = false;
                } else if (mode == 1) {
                    uint16_t end_c = std::min<uint16_t>(static_cast<uint16_t>(cursor_col + 1), cols);
                    if (end_c < cols) {
                        ClearWideNeighbors(row, end_c);
                    }
                    for (uint16_t c = 0; c < end_c; ++c) {
                        row.cells[c] = MakeBlankCell();
                    }
                } else if (mode == 2) {
                    for (auto& cell : row.cells) {
                        cell = MakeBlankCell();
                    }
                    row.soft_wrapped = false;
                }
                MarkRowDirty(cursor_row);
                break;
            }
            case 'L': {
                if (cursor_row >= scroll_top && cursor_row <= scroll_bottom) {
                    ScrollDownRegion(cursor_row, scroll_bottom, static_cast<uint16_t>(param_or(0, 1)));
                }
                break;
            }
            case 'M': {
                if (cursor_row >= scroll_top && cursor_row <= scroll_bottom) {
                    ScrollUpRegion(cursor_row, scroll_bottom, static_cast<uint16_t>(param_or(0, 1)));
                }
                break;
            }
            case '@': {
                int count = std::min<int>(param_or(0, 1), cols - cursor_col);
                RowData& row = grid[cursor_row];
                ClearWideNeighbors(row, cursor_col);
                for (int c = cols - 1; c >= cursor_col + count; --c) {
                    row.cells[c] = std::move(row.cells[c - count]);
                }
                for (int c = cursor_col; c < cursor_col + count; ++c) {
                    row.cells[c] = MakeBlankCell();
                }
                MarkRowDirty(cursor_row);
                break;
            }
            case 'P': {
                int count = std::min<int>(param_or(0, 1), cols - cursor_col);
                RowData& row = grid[cursor_row];
                ClearWideNeighbors(row, cursor_col);
                if (cursor_col + count < cols) {
                    ClearWideNeighbors(row, static_cast<uint16_t>(cursor_col + count));
                }
                for (int c = cursor_col; c < cols - count; ++c) {
                    row.cells[c] = std::move(row.cells[c + count]);
                }
                for (int c = cols - count; c < cols; ++c) {
                    row.cells[c] = MakeBlankCell();
                }
                MarkRowDirty(cursor_row);
                break;
            }
            case 'X': {
                int count = std::min<int>(param_or(0, 1), cols - cursor_col);
                RowData& row = grid[cursor_row];
                ClearWideNeighbors(row, cursor_col);
                if (cursor_col + count < cols) {
                    ClearWideNeighbors(row, static_cast<uint16_t>(cursor_col + count));
                }
                for (int c = cursor_col; c < cursor_col + count; ++c) {
                    row.cells[c] = MakeBlankCell();
                }
                MarkRowDirty(cursor_row);
                break;
            }
            case 'S':
                ScrollUpRegion(scroll_top, scroll_bottom, static_cast<uint16_t>(param_or(0, 1)));
                break;
            case 'T':
                ScrollDownRegion(scroll_top, scroll_bottom, static_cast<uint16_t>(param_or(0, 1)));
                break;
            case 'r': {
                int top1 = param_or(0, 1);
                int bot1 = param_or(1, rows);
                uint16_t new_top = static_cast<uint16_t>(std::clamp(top1 - 1, 0, static_cast<int>(rows - 1)));
                uint16_t new_bot = static_cast<uint16_t>(std::clamp(bot1 - 1, 0, static_cast<int>(rows - 1)));
                if (new_top < new_bot) {
                    scroll_top = new_top;
                    scroll_bottom = new_bot;
                    cursor_col = 0;
                    cursor_row = mode_origin ? scroll_top : 0;
                    pending_wrap = false;
                }
                break;
            }
            case 's':
                SaveCursor(using_alt_screen ? &saved_alt : &saved_primary);
                break;
            case 'u':
                RestoreCursor(using_alt_screen ? saved_alt : saved_primary);
                break;
            case 'b': {
                if (last_printed_cp != 0) {
                    int rep = std::min<int>(param_or(0, 1), 4096);
                    for (int k = 0; k < rep; ++k) {
                        PutCodepoint(last_printed_cp);
                    }
                }
                break;
            }
            case 'Z': {
                int count = param_or(0, 1);
                while (count-- > 0 && cursor_col > 0) {
                    --cursor_col;
                    while (cursor_col > 0 && !tab_stops[cursor_col]) {
                        --cursor_col;
                    }
                }
                pending_wrap = false;
                break;
            }
            default:
                break;
        }
        MarkRowDirty(cursor_row);
    }

    void DispatchOsc() {
        size_t semi = osc_buf.find(';');
        if (semi == std::string::npos) {
            return;
        }
        int code = std::atoi(osc_buf.substr(0, semi).c_str());
        std::string rest = osc_buf.substr(semi + 1);
        if (code == 0 || code == 2) {
            window_title = std::move(rest);
        } else if (code == 8) {
            size_t second_semi = rest.find(';');
            if (second_semi != std::string::npos) {
                std::string uri = rest.substr(second_semi + 1);
                if (uri.empty()) {
                    cur_link_id = 0;
                } else {
                    uint32_t id = next_link_id++;
                    osc8_links[id] = std::move(uri);
                    cur_link_id = id;
                }
            }
        }
    }

    void DispatchApc() {
        if (apc_buf.empty() || apc_buf[0] != 'G') {
            return;
        }
        size_t semi = apc_buf.find(';');
        std::string header = semi == std::string::npos ? apc_buf.substr(1) : apc_buf.substr(1, semi - 1);
        std::string payload = semi == std::string::npos ? std::string() : apc_buf.substr(semi + 1);

        std::map<std::string, std::string> kv;
        size_t start = 0;
        while (start <= header.size()) {
            size_t comma = header.find(',', start);
            std::string item = comma == std::string::npos ? header.substr(start) : header.substr(start, comma - start);
            size_t eq = item.find('=');
            if (eq != std::string::npos) {
                kv[item.substr(0, eq)] = item.substr(eq + 1);
            }
            if (comma == std::string::npos) {
                break;
            }
            start = comma + 1;
        }

        bool more_chunks = (kv.count("m") > 0 && kv["m"] == "1");
        if (!kitty_chunk_params.empty()) {
            for (const auto& entry : kv) {
                if (entry.first != "m") {
                    kitty_chunk_params[entry.first] = entry.second;
                }
            }
            kitty_chunk_b64.append(payload);
            if (more_chunks) {
                return;
            }
            kv = std::move(kitty_chunk_params);
            kitty_chunk_params.clear();
            payload = std::move(kitty_chunk_b64);
            kitty_chunk_b64.clear();
        } else if (more_chunks) {
            kitty_chunk_params = kv;
            kitty_chunk_b64 = std::move(payload);
            return;
        }

        std::string action = kv.count("a") > 0 ? kv["a"] : "T";
        if (action == "d") {
            kitty_placements.clear();
            if (kv.count("d") > 0 && (kv["d"] == "A" || kv["d"] == "I")) {
                kitty_images.clear();
            }
            ++kitty_revision;
            MarkAllDirty();
            return;
        }

        uint32_t img_id = kv.count("i") > 0 ? static_cast<uint32_t>(std::strtoul(kv["i"].c_str(), nullptr, 10)) : 0u;
        if (img_id == 0u) {
            img_id = next_kitty_id++;
        }

        if (action == "t" || action == "T") {
            uint32_t w = kv.count("s") > 0 ? static_cast<uint32_t>(std::strtoul(kv["s"].c_str(), nullptr, 10)) : 0u;
            uint32_t h = kv.count("v") > 0 ? static_cast<uint32_t>(std::strtoul(kv["v"].c_str(), nullptr, 10)) : 0u;
            int fmt = kv.count("f") > 0 ? std::atoi(kv["f"].c_str()) : 32;
            std::vector<uint8_t> raw = DecodeBase64(payload);
            if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
                std::vector<uint8_t> rgba(static_cast<size_t>(w) * static_cast<size_t>(h) * 4u, 0xFFu);
                if (fmt == 32 && raw.size() >= rgba.size()) {
                    std::copy_n(raw.begin(), rgba.size(), rgba.begin());
                } else if (fmt == 24 && raw.size() >= static_cast<size_t>(w) * static_cast<size_t>(h) * 3u) {
                    for (size_t px = 0, total = static_cast<size_t>(w) * static_cast<size_t>(h); px < total; ++px) {
                        rgba[px * 4u + 0] = raw[px * 3u + 0];
                        rgba[px * 4u + 1] = raw[px * 3u + 1];
                        rgba[px * 4u + 2] = raw[px * 3u + 2];
                        rgba[px * 4u + 3] = 0xFFu;
                    }
                } else if (!raw.empty()) {
                    size_t n = std::min(raw.size(), rgba.size());
                    std::copy_n(raw.begin(), n, rgba.begin());
                }
                kitty_images[img_id] = StoredKittyImage{img_id, w, h, std::move(rgba)};
            }
        }

        if (action == "p" || action == "T") {
            auto it = kitty_images.find(img_id);
            if (it != kitty_images.end()) {
                KittyGraphicPlacement pl;
                pl.image_id = img_id;
                pl.placement_id = kv.count("p") > 0
                    ? static_cast<uint32_t>(std::strtoul(kv["p"].c_str(), nullptr, 10))
                    : 1u;
                pl.pixel_width = it->second.width;
                pl.pixel_height = it->second.height;
                pl.grid_col = cursor_col;
                pl.grid_row = cursor_row;
                uint32_t def_cols = std::max<uint32_t>(
                    1u, (it->second.width + cell_width_px - 1u) / std::max<uint32_t>(1u, cell_width_px));
                uint32_t def_rows = std::max<uint32_t>(
                    1u, (it->second.height + cell_height_px - 1u) / std::max<uint32_t>(1u, cell_height_px));
                pl.span_cols = kv.count("c") > 0
                    ? static_cast<uint16_t>(std::max(1, std::atoi(kv["c"].c_str())))
                    : static_cast<uint16_t>(std::min<uint32_t>(cols, def_cols));
                pl.span_rows = kv.count("r") > 0
                    ? static_cast<uint16_t>(std::max(1, std::atoi(kv["r"].c_str())))
                    : static_cast<uint16_t>(std::min<uint32_t>(rows, def_rows));
                pl.z_index = kv.count("z") > 0 ? std::atoi(kv["z"].c_str()) : -1;
                pl.revision = ++kitty_revision;
                pl.rgba_pixels = it->second.rgba;
                kitty_placements.push_back(std::move(pl));
                MarkAllDirty();
            }
        }
    }

    void FeedByte(uint8_t b) {
        if (parser_state == ParserState::kOscString) {
            if (b == 0x07u) {
                DispatchOsc();
                osc_buf.clear();
                parser_state = ParserState::kGround;
                esc_saw_backslash_prefix = false;
                return;
            }
            if (esc_saw_backslash_prefix) {
                esc_saw_backslash_prefix = false;
                if (b == '\\') {
                    DispatchOsc();
                    osc_buf.clear();
                    parser_state = ParserState::kGround;
                    return;
                }
                osc_buf.push_back('\x1b');
            }
            if (b == 0x1Bu) {
                esc_saw_backslash_prefix = true;
                return;
            }
            if (osc_buf.size() < kMaxOscPayload) {
                osc_buf.push_back(static_cast<char>(b));
            }
            return;
        }

        if (parser_state == ParserState::kApcString) {
            if (esc_saw_backslash_prefix) {
                esc_saw_backslash_prefix = false;
                if (b == '\\') {
                    DispatchApc();
                    apc_buf.clear();
                    parser_state = ParserState::kGround;
                    return;
                }
                apc_buf.push_back('\x1b');
            }
            if (b == 0x1Bu) {
                esc_saw_backslash_prefix = true;
                return;
            }
            if (apc_buf.size() < kMaxApcPayload) {
                apc_buf.push_back(static_cast<char>(b));
            }
            return;
        }

        if (parser_state == ParserState::kDcsIgnore) {
            if (esc_saw_backslash_prefix) {
                esc_saw_backslash_prefix = false;
                if (b == '\\') {
                    parser_state = ParserState::kGround;
                    return;
                }
            }
            if (b == 0x1Bu) {
                esc_saw_backslash_prefix = true;
            }
            return;
        }

        if (b == 0x1Bu) {
            utf8_expected_cont = 0;
            parser_state = ParserState::kEscape;
            esc_saw_backslash_prefix = false;
            return;
        }

        if (b < 0x20u) {
            switch (b) {
                case '\r':
                    MarkRowDirty(cursor_row);
                    cursor_col = 0;
                    pending_wrap = false;
                    break;
                case '\n':
                case '\v':
                case '\f':
                    LineFeed();
                    break;
                case '\b':
                    if (cursor_col > 0) {
                        MarkRowDirty(cursor_row);
                        --cursor_col;
                        pending_wrap = false;
                    }
                    break;
                case '\t': {
                    MarkRowDirty(cursor_row);
                    uint16_t next_col = static_cast<uint16_t>(cursor_col + 1);
                    while (next_col < cols && !tab_stops[next_col]) {
                        ++next_col;
                    }
                    cursor_col = std::min<uint16_t>(next_col, static_cast<uint16_t>(cols - 1));
                    pending_wrap = false;
                    break;
                }
                default:
                    break;
            }
            return;
        }

        if (parser_state == ParserState::kEscape) {
            switch (b) {
                case '[':
                    csi_buf.clear();
                    parser_state = ParserState::kCsiEntry;
                    return;
                case ']':
                    osc_buf.clear();
                    esc_saw_backslash_prefix = false;
                    parser_state = ParserState::kOscString;
                    return;
                case '_':
                    apc_buf.clear();
                    esc_saw_backslash_prefix = false;
                    parser_state = ParserState::kApcString;
                    return;
                case 'P':
                case '^':
                case 'X':
                    esc_saw_backslash_prefix = false;
                    parser_state = ParserState::kDcsIgnore;
                    return;
                case '7':
                    SaveCursor(using_alt_screen ? &saved_alt : &saved_primary);
                    parser_state = ParserState::kGround;
                    return;
                case '8':
                    RestoreCursor(using_alt_screen ? saved_alt : saved_primary);
                    parser_state = ParserState::kGround;
                    return;
                case 'D':
                    LineFeed();
                    parser_state = ParserState::kGround;
                    return;
                case 'E':
                    cursor_col = 0;
                    LineFeed();
                    parser_state = ParserState::kGround;
                    return;
                case 'H':
                    if (cursor_col < tab_stops.size()) {
                        tab_stops[cursor_col] = true;
                    }
                    parser_state = ParserState::kGround;
                    return;
                case 'M':
                    ReverseIndex();
                    parser_state = ParserState::kGround;
                    return;
                case 'c':
                    ResetTerminal();
                    parser_state = ParserState::kGround;
                    return;
                default:
                    if (b >= 0x20u && b <= 0x2Fu) {
                        parser_state = ParserState::kEscapeIntermediate;
                    } else {
                        parser_state = ParserState::kGround;
                    }
                    return;
            }
        }

        if (parser_state == ParserState::kEscapeIntermediate) {
            if (b >= 0x30u && b <= 0x7Eu) {
                parser_state = ParserState::kGround;
            }
            return;
        }

        if (parser_state == ParserState::kCsiEntry) {
            if (b >= 0x40u && b <= 0x7Eu) {
                DispatchCsi(static_cast<char>(b));
                csi_buf.clear();
                parser_state = ParserState::kGround;
            } else if (csi_buf.size() < 256) {
                csi_buf.push_back(static_cast<char>(b));
            } else {
                csi_buf.clear();
                parser_state = ParserState::kGround;
            }
            return;
        }

        if (b == 0x7Fu) {
            return;
        }

        if (utf8_expected_cont > 0) {
            if ((b & 0xC0u) == 0x80u) {
                utf8_codepoint = (utf8_codepoint << 6u) | static_cast<uint32_t>(b & 0x3Fu);
                --utf8_expected_cont;
                if (utf8_expected_cont == 0) {
                    if (utf8_codepoint < utf8_min_cp ||
                        (utf8_codepoint >= 0xD800u && utf8_codepoint <= 0xDFFFu) ||
                        utf8_codepoint > 0x10FFFFu) {
                        PutCodepoint(0xFFFDu);
                    } else {
                        PutCodepoint(utf8_codepoint);
                    }
                }
                return;
            }
            utf8_expected_cont = 0;
            PutCodepoint(0xFFFDu);
        }

        if (b < 0x80u) {
            PutCodepoint(static_cast<uint32_t>(b));
        } else if ((b & 0xE0u) == 0xC0u) {
            utf8_codepoint = static_cast<uint32_t>(b & 0x1Fu);
            utf8_expected_cont = 1;
            utf8_min_cp = 0x80u;
        } else if ((b & 0xF0u) == 0xE0u) {
            utf8_codepoint = static_cast<uint32_t>(b & 0x0Fu);
            utf8_expected_cont = 2;
            utf8_min_cp = 0x800u;
        } else if ((b & 0xF8u) == 0xF0u) {
            utf8_codepoint = static_cast<uint32_t>(b & 0x07u);
            utf8_expected_cont = 3;
            utf8_min_cp = 0x10000u;
        } else {
            PutCodepoint(0xFFFDu);
        }
    }

    const RowData& ViewportRow(uint16_t vrow, int32_t* out_abs_row = nullptr) const {
        const auto& grid = ActiveGrid();
        if (using_alt_screen || scrollback_offset == 0 || scrollback.empty()) {
            if (out_abs_row != nullptr) {
                *out_abs_row = static_cast<int32_t>(scrollback.size()) + static_cast<int32_t>(vrow);
            }
            return grid[std::min<size_t>(vrow, grid.size() - 1)];
        }
        uint32_t clamped_off = std::min<uint32_t>(scrollback_offset, static_cast<uint32_t>(scrollback.size()));
        int32_t abs_idx = static_cast<int32_t>(scrollback.size() - clamped_off) + static_cast<int32_t>(vrow);
        if (out_abs_row != nullptr) {
            *out_abs_row = abs_idx;
        }
        if (abs_idx < static_cast<int32_t>(scrollback.size())) {
            return scrollback[static_cast<size_t>(std::max<int32_t>(0, abs_idx))];
        }
        size_t grid_idx = static_cast<size_t>(abs_idx - static_cast<int32_t>(scrollback.size()));
        return grid[std::min<size_t>(grid_idx, grid.size() - 1)];
    }

    bool IsCellSelected(int32_t abs_row, uint16_t col) const noexcept {
        if (!selection_active) {
            return false;
        }
        int32_t r0 = sel_start_abs_row;
        int32_t c0 = sel_start_col;
        int32_t r1 = sel_end_abs_row;
        int32_t c1 = sel_end_col;
        if (r0 > r1 || (r0 == r1 && c0 > c1)) {
            std::swap(r0, r1);
            std::swap(c0, c1);
        }
        if (abs_row < r0 || abs_row > r1) {
            return false;
        }
        if (r0 == r1) {
            return static_cast<int32_t>(col) >= c0 && static_cast<int32_t>(col) <= c1;
        }
        if (abs_row == r0) {
            return static_cast<int32_t>(col) >= c0;
        }
        if (abs_row == r1) {
            return static_cast<int32_t>(col) <= c1;
        }
        return true;
    }
};

struct TvGhosttyRenderStateImpl {
    uint16_t cols = 0;
    uint16_t rows = 0;
    TvGhosttyRenderStateDirty dirty = TV_GHOSTTY_RENDER_STATE_DIRTY_FULL;
    std::vector<bool> dirty_rows;
    std::vector<std::vector<TvGhosttyCellSnapshot>> row_cells;
};

struct TvGhosttyRenderStateRowIteratorImpl {
    TvGhosttyRenderState state = nullptr;
    bool dirty_only = false;
    uint16_t next_row = 0;
};

struct TvGhosttyRenderStateCellIteratorImpl {
    TvGhosttyRenderState state = nullptr;
    uint16_t row = 0;
    uint16_t next_col = 0;
};

struct TvGhosttyKeyEncoderImpl {
    bool decckm = false;
    int modify_other_keys = 0;
    uint32_t kitty_flags = 0;
};

struct TvGhosttyMouseEncoderImpl {
    uint16_t tracking_mode = 0;
    uint16_t format_mode = 0;
    int32_t last_motion_col = -1;
    int32_t last_motion_row = -1;
};

struct TvGhosttyFormatterImpl {
    TvGhosttyTerminal terminal = nullptr;
    TvGhosttyFormatterTerminalOptions opts = {};
};

extern "C" {

TvGhosttyResult tv_ghostty_terminal_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyTerminal* out_terminal,
    TvGhosttyTerminalOptions opts) {
    (void)allocator;
    if (out_terminal == nullptr || opts.cols == 0 || opts.rows == 0) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* impl = new (std::nothrow) TvGhosttyTerminalImpl(opts.cols, opts.rows, opts.max_scrollback);
    if (impl == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_terminal = impl;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_terminal_free(TvGhosttyTerminal terminal) {
    delete terminal;
}

TvGhosttyResult tv_ghostty_terminal_resize(
    TvGhosttyTerminal terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width_px,
    uint32_t cell_height_px) {
    if (terminal == nullptr || cols == 0 || rows == 0) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    terminal->cell_width_px = std::max<uint32_t>(1u, cell_width_px);
    terminal->cell_height_px = std::max<uint32_t>(1u, cell_height_px);

    auto resize_grid = [&](std::vector<RowData>* grid) {
        grid->resize(rows, RowData(cols));
        for (auto& row : *grid) {
            if (cols < row.cells.size() && cols > 0) {
                if (row.cells[cols - 1].width == 2) {
                    row.cells[cols - 1] = terminal->MakeBlankCell();
                }
            }
            row.cells.resize(cols, terminal->MakeBlankCell());
        }
    };

    terminal->cols = cols;
    terminal->rows = rows;
    resize_grid(&terminal->primary_grid);
    resize_grid(&terminal->alt_grid);
    terminal->cursor_col = std::min<uint16_t>(terminal->cursor_col, static_cast<uint16_t>(cols - 1));
    terminal->cursor_row = std::min<uint16_t>(terminal->cursor_row, static_cast<uint16_t>(rows - 1));
    terminal->scroll_top = 0;
    terminal->scroll_bottom = static_cast<uint16_t>(rows - 1);
    terminal->pending_wrap = false;
    terminal->ResetTabStops();
    terminal->dirty_rows.assign(rows, true);
    terminal->full_dirty = true;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_terminal_vt_write(
    TvGhosttyTerminal terminal,
    const uint8_t* data,
    size_t len) {
    if (terminal == nullptr || data == nullptr || len == 0) {
        return;
    }
    for (size_t i = 0; i < len; ++i) {
        terminal->FeedByte(data[i]);
    }
}

TvGhosttyResult tv_ghostty_render_state_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderState* out_state) {
    (void)allocator;
    if (out_state == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* st = new (std::nothrow) TvGhosttyRenderStateImpl();
    if (st == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_state = st;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_free(TvGhosttyRenderState state) {
    delete state;
}

TvGhosttyResult tv_ghostty_render_state_update(
    TvGhosttyRenderState state,
    TvGhosttyTerminal terminal) {
    if (state == nullptr || terminal == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    bool dims_changed = (state->cols != terminal->cols || state->rows != terminal->rows);
    state->cols = terminal->cols;
    state->rows = terminal->rows;
    state->dirty_rows.resize(state->rows, false);
    state->row_cells.resize(state->rows);

    bool any_dirty = false;
    for (uint16_t r = 0; r < state->rows; ++r) {
        bool row_dirty = dims_changed || terminal->full_dirty ||
                         (r < terminal->dirty_rows.size() && terminal->dirty_rows[r]);
        state->dirty_rows[r] = row_dirty;
        if (!row_dirty) {
            continue;
        }
        any_dirty = true;
        auto& snap_row = state->row_cells[r];
        snap_row.resize(state->cols);

        int32_t abs_row = 0;
        const RowData& src_row = terminal->ViewportRow(r, &abs_row);
        for (uint16_t c = 0; c < state->cols; ++c) {
            TvGhosttyCellSnapshot& dst = snap_row[c];
            std::memset(&dst, 0, sizeof(dst));
            dst.col = c;
            if (c < src_row.cells.size()) {
                const CellData& src = src_row.cells[c];
                dst.width = src.width;
                dst.is_continuation = src.continuation;
                dst.style_flags = src.style_flags;
                if (terminal->IsCellSelected(abs_row, c)) {
                    dst.style_flags |= termvr::vt::kStyleSelected;
                }
                dst.fg = src.fg;
                dst.bg = src.bg;
                dst.underline_color = src.deco;
                dst.primary_codepoint = src.primary_cp;
                size_t copy_n = std::min<size_t>(src.grapheme.size(), sizeof(dst.grapheme_utf8) - 1u);
                if (copy_n > 0) {
                    std::memcpy(dst.grapheme_utf8, src.grapheme.data(), copy_n);
                }
                dst.grapheme_len = static_cast<uint8_t>(copy_n);
            } else {
                dst.width = 1;
                dst.primary_codepoint = 0x20u;
                if (terminal->IsCellSelected(abs_row, c)) {
                    dst.style_flags |= termvr::vt::kStyleSelected;
                }
            }
        }
    }

    if (dims_changed || terminal->full_dirty) {
        state->dirty = TV_GHOSTTY_RENDER_STATE_DIRTY_FULL;
    } else if (any_dirty) {
        state->dirty = TV_GHOSTTY_RENDER_STATE_DIRTY_PARTIAL;
    } else {
        state->dirty = TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    }

    terminal->full_dirty = false;
    std::fill(terminal->dirty_rows.begin(), terminal->dirty_rows.end(), false);
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyRenderStateDirty tv_ghostty_render_state_get_dirty(TvGhosttyRenderState state) {
    return state == nullptr ? TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE : state->dirty;
}

void tv_ghostty_render_state_clean(TvGhosttyRenderState state) {
    if (state == nullptr) {
        return;
    }
    state->dirty = TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    std::fill(state->dirty_rows.begin(), state->dirty_rows.end(), false);
}

TvGhosttyResult tv_ghostty_render_state_row_iterator_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderStateRowIterator* out_iter) {
    (void)allocator;
    if (out_iter == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* it = new (std::nothrow) TvGhosttyRenderStateRowIteratorImpl();
    if (it == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_iter = it;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_row_iterator_free(TvGhosttyRenderStateRowIterator iter) {
    delete iter;
}

void tv_ghostty_render_state_row_iterator_begin(
    TvGhosttyRenderStateRowIterator iter,
    TvGhosttyRenderState state,
    bool dirty_only) {
    if (iter == nullptr) {
        return;
    }
    iter->state = state;
    iter->dirty_only = dirty_only;
    iter->next_row = 0;
}

bool tv_ghostty_render_state_row_iterator_next(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row) {
    if (iter == nullptr || iter->state == nullptr || out_row == nullptr) {
        return false;
    }
    while (iter->next_row < iter->state->rows) {
        uint16_t r = iter->next_row++;
        if (!iter->dirty_only || iter->state->dirty == TV_GHOSTTY_RENDER_STATE_DIRTY_FULL ||
            (r < iter->state->dirty_rows.size() && iter->state->dirty_rows[r])) {
            *out_row = r;
            return true;
        }
    }
    return false;
}

bool tv_ghostty_render_state_row_iterator_next_dirty(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row) {
    if (iter == nullptr) {
        return false;
    }
    iter->dirty_only = true;
    return tv_ghostty_render_state_row_iterator_next(iter, out_row);
}

TvGhosttyResult tv_ghostty_render_state_cell_iterator_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderStateCellIterator* out_iter) {
    (void)allocator;
    if (out_iter == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* it = new (std::nothrow) TvGhosttyRenderStateCellIteratorImpl();
    if (it == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_iter = it;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_cell_iterator_free(TvGhosttyRenderStateCellIterator iter) {
    delete iter;
}

void tv_ghostty_render_state_cell_iterator_begin(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyRenderState state,
    uint16_t row) {
    if (iter == nullptr) {
        return;
    }
    iter->state = state;
    iter->row = row;
    iter->next_col = 0;
}

bool tv_ghostty_render_state_cell_iterator_next(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyCellSnapshot* out_cell) {
    if (iter == nullptr || iter->state == nullptr || out_cell == nullptr) {
        return false;
    }
    if (iter->row >= iter->state->row_cells.size()) {
        return false;
    }
    const auto& row_vec = iter->state->row_cells[iter->row];
    if (iter->next_col >= row_vec.size()) {
        return false;
    }
    *out_cell = row_vec[iter->next_col++];
    return true;
}

TvGhosttyResult tv_ghostty_key_encoder_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyKeyEncoder* out_encoder) {
    (void)allocator;
    if (out_encoder == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* enc = new (std::nothrow) TvGhosttyKeyEncoderImpl();
    if (enc == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_encoder = enc;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_key_encoder_free(TvGhosttyKeyEncoder encoder) {
    delete encoder;
}

void tv_ghostty_key_encoder_set_opts_from_terminal(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyTerminal terminal) {
    if (encoder == nullptr || terminal == nullptr) {
        return;
    }
    encoder->decckm = terminal->mode_decckm;
    encoder->modify_other_keys = terminal->modify_other_keys_level;
    encoder->kitty_flags = terminal->ActiveKittyKbdFlags();
}

TvGhosttyResult tv_ghostty_key_encoder_encode(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyKeyEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (encoder == nullptr || out_written == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    *out_written = 0;

    bool report_events = (encoder->kitty_flags & 0x2u) != 0u;
    if (event.action == TV_GHOSTTY_KEY_ACTION_RELEASE && !report_events) {
        return TV_GHOSTTY_SUCCESS;
    }

    uint32_t xterm_mod = 1u +
        ((event.mods & TV_GHOSTTY_MOD_SHIFT) ? 1u : 0u) +
        ((event.mods & TV_GHOSTTY_MOD_ALT) ? 2u : 0u) +
        ((event.mods & TV_GHOSTTY_MOD_CTRL) ? 4u : 0u) +
        ((event.mods & TV_GHOSTTY_MOD_SUPER) ? 8u : 0u);

    std::string seq;

    auto emit_kitty_csi_u = [&](uint32_t key_num) {
        char tmp[64];
        if (report_events && event.action != TV_GHOSTTY_KEY_ACTION_PRESS) {
            int act = (event.action == TV_GHOSTTY_KEY_ACTION_REPEAT) ? 2 : 3;
            std::snprintf(tmp, sizeof(tmp), "\x1b[%u;%u:%du", key_num, xterm_mod, act);
        } else if (xterm_mod > 1u) {
            std::snprintf(tmp, sizeof(tmp), "\x1b[%u;%uu", key_num, xterm_mod);
        } else {
            std::snprintf(tmp, sizeof(tmp), "\x1b[%uu", key_num);
        }
        seq.assign(tmp);
    };

    auto emit_tilde_key = [&](int code) {
        char tmp[32];
        if (xterm_mod > 1u) {
            std::snprintf(tmp, sizeof(tmp), "\x1b[%d;%u~", code, xterm_mod);
        } else {
            std::snprintf(tmp, sizeof(tmp), "\x1b[%d~", code);
        }
        seq.assign(tmp);
    };

    auto emit_cursor_letter = [&](char letter, bool allow_ss3) {
        char tmp[32];
        if (xterm_mod > 1u) {
            std::snprintf(tmp, sizeof(tmp), "\x1b[1;%u%c", xterm_mod, letter);
        } else if (allow_ss3 && encoder->decckm) {
            std::snprintf(tmp, sizeof(tmp), "\x1bO%c", letter);
        } else {
            std::snprintf(tmp, sizeof(tmp), "\x1b[%c", letter);
        }
        seq.assign(tmp);
    };

    bool kitty_disambiguate = (encoder->kitty_flags & 0x1u) != 0u;
    bool kitty_all_keys = (encoder->kitty_flags & 0x8u) != 0u;

    switch (event.key) {
        case TV_GHOSTTY_KEY_ESCAPE:
            if (kitty_disambiguate || kitty_all_keys || xterm_mod > 1u) {
                emit_kitty_csi_u(27u);
            } else {
                seq = "\x1b";
            }
            break;
        case TV_GHOSTTY_KEY_ENTER:
            if (kitty_all_keys || (kitty_disambiguate && xterm_mod > 1u)) {
                emit_kitty_csi_u(13u);
            } else if (encoder->modify_other_keys >= 1 && xterm_mod > 1u) {
                char tmp[32];
                std::snprintf(tmp, sizeof(tmp), "\x1b[27;%u;13~", xterm_mod);
                seq.assign(tmp);
            } else {
                if (event.mods & TV_GHOSTTY_MOD_ALT) {
                    seq.push_back('\x1b');
                }
                seq.push_back('\r');
            }
            break;
        case TV_GHOSTTY_KEY_TAB:
            if (kitty_all_keys || (kitty_disambiguate && (event.mods & (TV_GHOSTTY_MOD_CTRL | TV_GHOSTTY_MOD_ALT | TV_GHOSTTY_MOD_SUPER)))) {
                emit_kitty_csi_u(9u);
            } else if (event.mods & TV_GHOSTTY_MOD_SHIFT) {
                seq = "\x1b[Z";
            } else {
                if (event.mods & TV_GHOSTTY_MOD_ALT) {
                    seq.push_back('\x1b');
                }
                seq.push_back('\t');
            }
            break;
        case TV_GHOSTTY_KEY_BACKSPACE:
            if (kitty_all_keys) {
                emit_kitty_csi_u(127u);
            } else {
                if (event.mods & TV_GHOSTTY_MOD_ALT) {
                    seq.push_back('\x1b');
                }
                seq.push_back((event.mods & TV_GHOSTTY_MOD_CTRL) ? '\x08' : '\x7f');
            }
            break;
        case TV_GHOSTTY_KEY_UP: emit_cursor_letter('A', true); break;
        case TV_GHOSTTY_KEY_DOWN: emit_cursor_letter('B', true); break;
        case TV_GHOSTTY_KEY_RIGHT: emit_cursor_letter('C', true); break;
        case TV_GHOSTTY_KEY_LEFT: emit_cursor_letter('D', true); break;
        case TV_GHOSTTY_KEY_HOME: emit_cursor_letter('H', true); break;
        case TV_GHOSTTY_KEY_END: emit_cursor_letter('F', true); break;
        case TV_GHOSTTY_KEY_INSERT: emit_tilde_key(2); break;
        case TV_GHOSTTY_KEY_DELETE: emit_tilde_key(3); break;
        case TV_GHOSTTY_KEY_PAGE_UP: emit_tilde_key(5); break;
        case TV_GHOSTTY_KEY_PAGE_DOWN: emit_tilde_key(6); break;
        case TV_GHOSTTY_KEY_F1:
        case TV_GHOSTTY_KEY_F2:
        case TV_GHOSTTY_KEY_F3:
        case TV_GHOSTTY_KEY_F4: {
            char letter = static_cast<char>('P' + (event.key - TV_GHOSTTY_KEY_F1));
            char tmp[32];
            if (xterm_mod > 1u) {
                std::snprintf(tmp, sizeof(tmp), "\x1b[1;%u%c", xterm_mod, letter);
            } else {
                std::snprintf(tmp, sizeof(tmp), "\x1bO%c", letter);
            }
            seq.assign(tmp);
            break;
        }
        case TV_GHOSTTY_KEY_F5: emit_tilde_key(15); break;
        case TV_GHOSTTY_KEY_F6: emit_tilde_key(17); break;
        case TV_GHOSTTY_KEY_F7: emit_tilde_key(18); break;
        case TV_GHOSTTY_KEY_F8: emit_tilde_key(19); break;
        case TV_GHOSTTY_KEY_F9: emit_tilde_key(20); break;
        case TV_GHOSTTY_KEY_F10: emit_tilde_key(21); break;
        case TV_GHOSTTY_KEY_F11: emit_tilde_key(23); break;
        case TV_GHOSTTY_KEY_F12: emit_tilde_key(24); break;
        case TV_GHOSTTY_KEY_UNICODE: {
            uint32_t cp = event.unicode_codepoint;
            if (kitty_all_keys || (kitty_disambiguate && (event.mods & (TV_GHOSTTY_MOD_CTRL | TV_GHOSTTY_MOD_ALT | TV_GHOSTTY_MOD_SUPER)))) {
                uint32_t base_cp = (cp >= 'A' && cp <= 'Z') ? (cp + 32u) : cp;
                emit_kitty_csi_u(base_cp);
                break;
            }
            if (encoder->modify_other_keys >= 2 && (event.mods & (TV_GHOSTTY_MOD_CTRL | TV_GHOSTTY_MOD_ALT)) && cp >= 0x20u && cp <= 0x7Eu) {
                char tmp[32];
                std::snprintf(tmp, sizeof(tmp), "\x1b[27;%u;%uu", xterm_mod, cp);
                // Standard modifyOtherKeys terminates with '~'.
                std::snprintf(tmp, sizeof(tmp), "\x1b[27;%u;%u~", xterm_mod, cp);
                seq.assign(tmp);
                break;
            }
            if (event.mods & TV_GHOSTTY_MOD_ALT) {
                seq.push_back('\x1b');
            }
            if (event.mods & TV_GHOSTTY_MOD_CTRL) {
                uint32_t upper = (cp >= 'a' && cp <= 'z') ? (cp - 32u) : cp;
                if (upper == ' ' || upper == '@') {
                    seq.push_back('\x00');
                } else if (upper >= 'A' && upper <= '_') {
                    seq.push_back(static_cast<char>(upper - 0x40u));
                } else if (upper == '?') {
                    seq.push_back('\x7f');
                } else if (upper == '/') {
                    seq.push_back('\x1f');
                } else {
                    AppendUtf8(cp, &seq);
                }
            } else if (event.utf8_text != nullptr && event.utf8_len > 0) {
                seq.append(event.utf8_text, event.utf8_len);
            } else if (cp != 0) {
                AppendUtf8(cp, &seq);
            }
            break;
        }
        default:
            break;
    }

    *out_written = seq.size();
    if (seq.empty()) {
        return TV_GHOSTTY_SUCCESS;
    }
    if (out_buf == nullptr || buf_cap < seq.size()) {
        return TV_GHOSTTY_OUT_OF_SPACE;
    }
    std::memcpy(out_buf, seq.data(), seq.size());
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyResult tv_ghostty_mouse_encoder_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyMouseEncoder* out_encoder) {
    (void)allocator;
    if (out_encoder == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* enc = new (std::nothrow) TvGhosttyMouseEncoderImpl();
    if (enc == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_encoder = enc;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_mouse_encoder_free(TvGhosttyMouseEncoder encoder) {
    delete encoder;
}

void tv_ghostty_mouse_encoder_set_opts_from_terminal(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyTerminal terminal) {
    if (encoder == nullptr || terminal == nullptr) {
        return;
    }
    encoder->tracking_mode = terminal->mouse_tracking_mode;
    encoder->format_mode = terminal->mouse_format_mode;
    encoder->last_motion_col = -1;
    encoder->last_motion_row = -1;
}

TvGhosttyResult tv_ghostty_mouse_encoder_encode(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyMouseEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (encoder == nullptr || out_written == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    *out_written = 0;
    if (encoder->tracking_mode == 0) {
        return TV_GHOSTTY_SUCCESS;
    }
    if (event.action == TV_GHOSTTY_MOUSE_MOTION) {
        if (encoder->tracking_mode < 1002u) {
            return TV_GHOSTTY_SUCCESS;
        }
        if (encoder->tracking_mode == 1002u && event.button == TV_GHOSTTY_MOUSE_BUTTON_NONE) {
            return TV_GHOSTTY_SUCCESS;
        }
        if (encoder->last_motion_col == static_cast<int32_t>(event.col) &&
            encoder->last_motion_row == static_cast<int32_t>(event.row)) {
            return TV_GHOSTTY_SUCCESS;
        }
        encoder->last_motion_col = static_cast<int32_t>(event.col);
        encoder->last_motion_row = static_cast<int32_t>(event.row);
    }

    int cb = 0;
    if (event.action == TV_GHOSTTY_MOUSE_WHEEL_UP) {
        cb = 64;
    } else if (event.action == TV_GHOSTTY_MOUSE_WHEEL_DOWN) {
        cb = 65;
    } else if (event.action == TV_GHOSTTY_MOUSE_RELEASE && encoder->format_mode != 1006u) {
        cb = 3;
    } else {
        switch (event.button) {
            case TV_GHOSTTY_MOUSE_BUTTON_LEFT: cb = 0; break;
            case TV_GHOSTTY_MOUSE_BUTTON_MIDDLE: cb = 1; break;
            case TV_GHOSTTY_MOUSE_BUTTON_RIGHT: cb = 2; break;
            default: cb = 3; break;
        }
    }
    if (event.action == TV_GHOSTTY_MOUSE_MOTION) {
        cb += 32;
    }
    if (event.mods & TV_GHOSTTY_MOD_SHIFT) cb += 4;
    if (event.mods & TV_GHOSTTY_MOD_ALT) cb += 8;
    if (event.mods & TV_GHOSTTY_MOD_CTRL) cb += 16;

    uint32_t cx1 = static_cast<uint32_t>(event.col) + 1u;
    uint32_t cy1 = static_cast<uint32_t>(event.row) + 1u;

    std::string seq;
    if (encoder->format_mode == 1006u) {
        char final_c = (event.action == TV_GHOSTTY_MOUSE_RELEASE) ? 'm' : 'M';
        char tmp[48];
        std::snprintf(tmp, sizeof(tmp), "\x1b[<%d;%u;%u%c", cb, cx1, cy1, final_c);
        seq.assign(tmp);
    } else if (encoder->format_mode == 1005u) {
        seq = "\x1b[M";
        AppendUtf8(static_cast<uint32_t>(cb + 32), &seq);
        AppendUtf8(std::min<uint32_t>(cx1 + 32u, 2047u), &seq);
        AppendUtf8(std::min<uint32_t>(cy1 + 32u, 2047u), &seq);
    } else {
        if (cx1 > 223u || cy1 > 223u) {
            return TV_GHOSTTY_SUCCESS;
        }
        seq.push_back('\x1b');
        seq.push_back('[');
        seq.push_back('M');
        seq.push_back(static_cast<char>(cb + 32));
        seq.push_back(static_cast<char>(cx1 + 32u));
        seq.push_back(static_cast<char>(cy1 + 32u));
    }

    *out_written = seq.size();
    if (out_buf == nullptr || buf_cap < seq.size()) {
        return TV_GHOSTTY_OUT_OF_SPACE;
    }
    std::memcpy(out_buf, seq.data(), seq.size());
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyResult tv_ghostty_formatter_terminal_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyFormatter* out_formatter,
    TvGhosttyTerminal terminal,
    TvGhosttyFormatterTerminalOptions opts) {
    (void)allocator;
    if (out_formatter == nullptr || terminal == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto* fmt = new (std::nothrow) TvGhosttyFormatterImpl();
    if (fmt == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    fmt->terminal = terminal;
    fmt->opts = opts;
    *out_formatter = fmt;
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyResult tv_ghostty_formatter_format_alloc(
    TvGhosttyFormatter formatter,
    const TvGhosttyAllocator* allocator,
    uint8_t** out_buf,
    size_t* out_len) {
    (void)allocator;
    if (formatter == nullptr || formatter->terminal == nullptr || out_buf == nullptr || out_len == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    TvGhosttyTerminal term = formatter->terminal;
    const auto& opts = formatter->opts;

    int32_t r0 = opts.start_row;
    int32_t c0 = opts.start_col;
    int32_t r1 = opts.end_row;
    int32_t c1 = opts.end_col;

    bool use_abs_rows = false;
    if (opts.use_selection) {
        if (!term->selection_active) {
            *out_buf = static_cast<uint8_t*>(std::malloc(1));
            if (*out_buf == nullptr) {
                return TV_GHOSTTY_OUT_OF_MEMORY;
            }
            (*out_buf)[0] = 0;
            *out_len = 0;
            return TV_GHOSTTY_SUCCESS;
        }
        use_abs_rows = true;
        r0 = term->sel_start_abs_row;
        c0 = term->sel_start_col;
        r1 = term->sel_end_abs_row;
        c1 = term->sel_end_col;
    } else {
        if (r1 < 0) {
            r0 = 0;
            c0 = 0;
            r1 = static_cast<int32_t>(term->rows) - 1;
            c1 = static_cast<int32_t>(term->cols) - 1;
        }
    }

    if (r0 > r1 || (r0 == r1 && c0 > c1)) {
        std::swap(r0, r1);
        std::swap(c0, c1);
    }

    auto get_row = [&](int32_t r) -> const RowData* {
        if (use_abs_rows) {
            int32_t sb_size = static_cast<int32_t>(term->scrollback.size());
            if (r < 0) {
                return nullptr;
            }
            if (r < sb_size) {
                return &term->scrollback[static_cast<size_t>(r)];
            }
            int32_t gidx = r - sb_size;
            const auto& grid = term->ActiveGrid();
            if (gidx >= 0 && static_cast<size_t>(gidx) < grid.size()) {
                return &grid[static_cast<size_t>(gidx)];
            }
            return nullptr;
        }
        if (r < 0 || r >= static_cast<int32_t>(term->rows)) {
            return nullptr;
        }
        return &term->ViewportRow(static_cast<uint16_t>(r));
    };

    std::string out;
    for (int32_t r = r0; r <= r1; ++r) {
        const RowData* row = get_row(r);
        if (row == nullptr) {
            continue;
        }
        int32_t col_start = (r == r0) ? std::max<int32_t>(0, c0) : 0;
        int32_t col_end = (r == r1)
            ? std::min<int32_t>(static_cast<int32_t>(row->cells.size()) - 1, c1)
            : static_cast<int32_t>(row->cells.size()) - 1;

        std::string line;
        uint16_t prev_style = 0;
        TvGhosttyColorValue prev_fg = {TV_GHOSTTY_COLOR_DEFAULT, 0, 0, 0, 0};

        for (int32_t c = col_start; c <= col_end; ++c) {
            const CellData& cell = row->cells[static_cast<size_t>(c)];
            if (cell.continuation) {
                continue;
            }
            if (opts.emit == TV_GHOSTTY_FORMATTER_EMIT_VT) {
                if (cell.style_flags != prev_style || cell.fg.kind != prev_fg.kind ||
                    cell.fg.r != prev_fg.r || cell.fg.g != prev_fg.g || cell.fg.b != prev_fg.b) {
                    line.append("\x1b[0");
                    if (cell.style_flags & termvr::vt::kStyleBold) line.append(";1");
                    if (cell.style_flags & termvr::vt::kStyleItalic) line.append(";3");
                    if (cell.style_flags & termvr::vt::kStyleUnderlineMask) line.append(";4");
                    if (cell.fg.kind != TV_GHOSTTY_COLOR_DEFAULT) {
                        char rgb_seq[32];
                        std::snprintf(rgb_seq, sizeof(rgb_seq), ";38;2;%u;%u;%u",
                                      cell.fg.r, cell.fg.g, cell.fg.b);
                        line.append(rgb_seq);
                    }
                    line.push_back('m');
                    prev_style = cell.style_flags;
                    prev_fg = cell.fg;
                }
            }
            if (!cell.grapheme.empty()) {
                line.append(cell.grapheme);
            } else {
                line.push_back(' ');
            }
        }
        if (opts.emit == TV_GHOSTTY_FORMATTER_EMIT_VT && (prev_style != 0 || prev_fg.kind != TV_GHOSTTY_COLOR_DEFAULT)) {
            line.append("\x1b[0m");
        }
        if (opts.trim_trailing_whitespace && opts.emit == TV_GHOSTTY_FORMATTER_EMIT_PLAIN && !row->soft_wrapped) {
            while (!line.empty() && line.back() == ' ') {
                line.pop_back();
            }
        }
        out.append(line);
        if (r < r1) {
            if (!(opts.unwrap_soft_wraps && row->soft_wrapped)) {
                out.push_back('\n');
            }
        }
    }

    auto* raw = static_cast<uint8_t*>(std::malloc(out.size() + 1u));
    if (raw == nullptr) {
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    if (!out.empty()) {
        std::memcpy(raw, out.data(), out.size());
    }
    raw[out.size()] = 0;
    *out_buf = raw;
    *out_len = out.size();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_formatter_free(TvGhosttyFormatter formatter) {
    delete formatter;
}

void tv_ghostty_formatter_buffer_free(
    const TvGhosttyAllocator* allocator,
    uint8_t* buf,
    size_t len) {
    (void)allocator;
    (void)len;
    std::free(buf);
}

bool tv_ghostty_paste_is_safe(const char* data, size_t len) {
    if (data == nullptr || len == 0) {
        return true;
    }
    for (size_t i = 0; i < len; ++i) {
        unsigned char ch = static_cast<unsigned char>(data[i]);
        if (ch == '\n' || ch == '\r' || ch == 0x1Bu || ch == 0x00u || ch == 0x03u || ch == 0x04u) {
            return false;
        }
        // Detect UTF-8 C1 NEL (U+0085: C2 85) and Line/Paragraph Separator (U+2028/U+2029: E2 80 A8/A9).
        if (ch == 0xC2u && i + 1 < len && static_cast<unsigned char>(data[i + 1]) == 0x85u) {
            return false;
        }
        if (ch == 0xE2u && i + 2 < len && static_cast<unsigned char>(data[i + 1]) == 0x80u) {
            unsigned char b2 = static_cast<unsigned char>(data[i + 2]);
            if (b2 == 0xA8u || b2 == 0xA9u) {
                return false;
            }
        }
    }
    return true;
}

TvGhosttyResult tv_ghostty_terminal_paste(
    TvGhosttyTerminal terminal,
    const char* data,
    size_t len,
    bool allow_unsafe,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (terminal == nullptr || out_written == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    *out_written = 0;
    if (data == nullptr || len == 0) {
        return TV_GHOSTTY_SUCCESS;
    }
    if (!allow_unsafe && !tv_ghostty_paste_is_safe(data, len)) {
        return TV_GHOSTTY_UNSAFE_PASTE;
    }

    std::string sanitized;
    sanitized.reserve(len + 16u);
    if (terminal->mode_bracketed_paste) {
        sanitized.append("\x1b[200~");
    }

    for (size_t i = 0; i < len; ++i) {
        // Strip embedded ESC [ 201 ~ so pasted text cannot escape bracketed paste mode.
        if (i + 5 < len && std::memcmp(data + i, "\x1b[201~", 6) == 0) {
            i += 5;
            continue;
        }
        if (!terminal->mode_bracketed_paste && data[i] == '\n') {
            sanitized.push_back('\r');
        } else {
            sanitized.push_back(data[i]);
        }
    }

    if (terminal->mode_bracketed_paste) {
        sanitized.append("\x1b[201~");
    }

    *out_written = sanitized.size();
    if (out_buf == nullptr || buf_cap < sanitized.size()) {
        return TV_GHOSTTY_OUT_OF_SPACE;
    }
    std::memcpy(out_buf, sanitized.data(), sanitized.size());
    return TV_GHOSTTY_SUCCESS;
}

}  // extern "C"

namespace termvr::vt {

void TerminalSetScrollbackOffset(TvGhosttyTerminal terminal, uint32_t lines_from_bottom) {
    if (terminal == nullptr) {
        return;
    }
    uint32_t max_off = terminal->using_alt_screen ? 0u : static_cast<uint32_t>(terminal->scrollback.size());
    uint32_t clamped = std::min(lines_from_bottom, max_off);
    if (terminal->scrollback_offset != clamped) {
        terminal->scrollback_offset = clamped;
        terminal->MarkAllDirty();
    }
}

uint32_t TerminalGetScrollbackOffset(TvGhosttyTerminal terminal) {
    return terminal == nullptr ? 0u : terminal->scrollback_offset;
}

uint32_t TerminalGetScrollbackCount(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->using_alt_screen) {
        return 0u;
    }
    return static_cast<uint32_t>(terminal->scrollback.size());
}

void TerminalSetSelection(
    TvGhosttyTerminal terminal,
    bool active,
    int32_t start_row,
    int32_t start_col,
    int32_t end_row,
    int32_t end_col) {
    if (terminal == nullptr) {
        return;
    }
    int32_t base_abs = 0;
    if (!terminal->using_alt_screen && !terminal->scrollback.empty()) {
        uint32_t clamped_off = std::min<uint32_t>(
            terminal->scrollback_offset, static_cast<uint32_t>(terminal->scrollback.size()));
        base_abs = static_cast<int32_t>(terminal->scrollback.size() - clamped_off);
    }
    terminal->selection_active = active;
    terminal->sel_start_abs_row = base_abs + start_row;
    terminal->sel_start_col = start_col;
    terminal->sel_end_abs_row = base_abs + end_row;
    terminal->sel_end_col = end_col;
    terminal->MarkAllDirty();
}

void TerminalClearSelection(TvGhosttyTerminal terminal) {
    if (terminal != nullptr && terminal->selection_active) {
        terminal->selection_active = false;
        terminal->MarkAllDirty();
    }
}

bool TerminalHasSelection(TvGhosttyTerminal terminal) {
    return terminal != nullptr && terminal->selection_active;
}

bool TerminalIsMouseTrackingActive(TvGhosttyTerminal terminal) {
    return terminal != nullptr && terminal->mouse_tracking_mode != 0;
}

bool TerminalIsBracketedPasteActive(TvGhosttyTerminal terminal) {
    return terminal != nullptr && terminal->mode_bracketed_paste;
}

bool TerminalIsApplicationCursorActive(TvGhosttyTerminal terminal) {
    return terminal != nullptr && terminal->mode_decckm;
}

std::string TerminalGetWindowTitle(TvGhosttyTerminal terminal) {
    return terminal == nullptr ? std::string() : terminal->window_title;
}

TerminalCursorSnapshot TerminalGetCursorSnapshot(TvGhosttyTerminal terminal) {
    TerminalCursorSnapshot snap;
    if (terminal == nullptr) {
        return snap;
    }
    snap.col = terminal->cursor_col;
    snap.row = terminal->cursor_row;
    snap.visible = terminal->cursor_visible && (terminal->scrollback_offset == 0);
    snap.blink = terminal->cursor_blink;
    snap.shape = terminal->cursor_shape;
    return snap;
}

namespace {

struct LineTextMapping {
    std::string utf8;
    std::vector<uint16_t> byte_to_col;
};

LineTextMapping BuildRowTextMapping(const RowData& row) {
    LineTextMapping map;
    map.utf8.reserve(row.cells.size());
    map.byte_to_col.reserve(row.cells.size());
    for (uint16_t c = 0; c < row.cells.size(); ++c) {
        const CellData& cell = row.cells[c];
        if (cell.continuation) {
            continue;
        }
        const std::string& g = cell.grapheme.empty() ? std::string(1, ' ') : cell.grapheme;
        for (size_t b = 0; b < g.size(); ++b) {
            map.utf8.push_back(g[b]);
            map.byte_to_col.push_back(c);
        }
    }
    while (!map.utf8.empty() && map.utf8.back() == ' ') {
        map.utf8.pop_back();
        map.byte_to_col.pop_back();
    }
    return map;
}

std::string ToLowerAscii(const std::string& s) {
    std::string out = s;
    for (char& ch : out) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }
    return out;
}

}  // namespace

std::vector<SearchMatchNative> TerminalSearchScrollback(
    TvGhosttyTerminal terminal,
    const std::string& query,
    bool use_regex,
    bool case_sensitive) {
    std::vector<SearchMatchNative> results;
    if (terminal == nullptr || query.empty()) {
        return results;
    }

    int32_t sb_count = terminal->using_alt_screen ? 0 : static_cast<int32_t>(terminal->scrollback.size());
    const auto& grid = terminal->ActiveGrid();
    int32_t total_lines = sb_count + static_cast<int32_t>(grid.size());
    uint32_t clamped_off = std::min<uint32_t>(terminal->scrollback_offset, static_cast<uint32_t>(sb_count));
    int32_t viewport_top_abs = sb_count - static_cast<int32_t>(clamped_off);

    std::unique_ptr<std::regex> compiled_regex;
    if (use_regex) {
        auto flags = std::regex_constants::ECMAScript;
        if (!case_sensitive) {
            flags |= std::regex_constants::icase;
        }
        try {
            compiled_regex = std::make_unique<std::regex>(query, flags);
        } catch (const std::regex_error&) {
            return results;
        }
    }

    std::string needle = case_sensitive ? query : ToLowerAscii(query);

    for (int32_t abs_line = 0; abs_line < total_lines; ++abs_line) {
        const RowData& row = (abs_line < sb_count)
            ? terminal->scrollback[static_cast<size_t>(abs_line)]
            : grid[static_cast<size_t>(abs_line - sb_count)];

        LineTextMapping mapping = BuildRowTextMapping(row);
        if (mapping.utf8.empty()) {
            continue;
        }
        int32_t vrow = (abs_line >= viewport_top_abs && abs_line < viewport_top_abs + static_cast<int32_t>(terminal->rows))
            ? (abs_line - viewport_top_abs)
            : -1;

        if (use_regex) {
            auto begin = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), *compiled_regex);
            auto end = std::sregex_iterator();
            for (auto it = begin; it != end; ++it) {
                size_t b_start = static_cast<size_t>(it->position());
                size_t b_len = static_cast<size_t>(it->length());
                if (b_len == 0 || b_start >= mapping.byte_to_col.size()) {
                    continue;
                }
                size_t b_last = std::min(b_start + b_len - 1u, mapping.byte_to_col.size() - 1u);
                SearchMatchNative match;
                match.absolute_line = abs_line;
                match.viewport_row = vrow;
                match.start_col = mapping.byte_to_col[b_start];
                uint16_t last_col = mapping.byte_to_col[b_last];
                uint8_t last_w = row.cells[last_col].width;
                match.end_col = static_cast<uint16_t>(last_col + std::max<uint8_t>(1, last_w) - 1u);
                match.matched_text = it->str();
                results.push_back(std::move(match));
            }
        } else {
            std::string hay = case_sensitive ? mapping.utf8 : ToLowerAscii(mapping.utf8);
            size_t pos = 0;
            while ((pos = hay.find(needle, pos)) != std::string::npos) {
                size_t b_last = std::min(pos + needle.size() - 1u, mapping.byte_to_col.size() - 1u);
                SearchMatchNative match;
                match.absolute_line = abs_line;
                match.viewport_row = vrow;
                match.start_col = mapping.byte_to_col[pos];
                uint16_t last_col = mapping.byte_to_col[b_last];
                uint8_t last_w = row.cells[last_col].width;
                match.end_col = static_cast<uint16_t>(last_col + std::max<uint8_t>(1, last_w) - 1u);
                match.matched_text = mapping.utf8.substr(pos, needle.size());
                results.push_back(std::move(match));
                pos += std::max<size_t>(1u, needle.size());
            }
        }
    }
    return results;
}

bool TerminalDetectLinkAt(
    TvGhosttyTerminal terminal,
    uint16_t viewport_row,
    uint16_t viewport_col,
    DetectedLinkNative* out_link) {
    if (terminal == nullptr || out_link == nullptr ||
        viewport_row >= terminal->rows || viewport_col >= terminal->cols) {
        return false;
    }

    const RowData& row = terminal->ViewportRow(viewport_row);
    uint16_t col = viewport_col;
    if (col < row.cells.size() && row.cells[col].continuation && col > 0) {
        --col;
    }

    if (col < row.cells.size() && row.cells[col].link_id != 0) {
        uint32_t lid = row.cells[col].link_id;
        auto it = terminal->osc8_links.find(lid);
        if (it != terminal->osc8_links.end()) {
            uint16_t c_start = col;
            while (c_start > 0 && row.cells[c_start - 1].link_id == lid) {
                --c_start;
            }
            uint16_t c_end = col;
            while (static_cast<size_t>(c_end + 1) < row.cells.size() && row.cells[c_end + 1].link_id == lid) {
                ++c_end;
            }
            out_link->kind = DetectedLinkKind::kUrl;
            out_link->start_row = viewport_row;
            out_link->start_col = c_start;
            out_link->end_row = viewport_row;
            out_link->end_col = c_end;
            out_link->target = it->second;
            return true;
        }
    }

    LineTextMapping mapping = BuildRowTextMapping(row);
    if (mapping.utf8.empty()) {
        return false;
    }

    static const std::regex kUrlRegex(R"(https?://[^\s<>'"`]+)");
    for (auto it = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), kUrlRegex);
         it != std::sregex_iterator(); ++it) {
        std::string matched = it->str();
        size_t len = matched.size();
        while (len > 0 && (matched[len - 1] == '.' || matched[len - 1] == ',' ||
                           matched[len - 1] == ';' || matched[len - 1] == ')')) {
            --len;
        }
        if (len == 0) {
            continue;
        }
        size_t b0 = static_cast<size_t>(it->position());
        size_t b1 = std::min(b0 + len - 1u, mapping.byte_to_col.size() - 1u);
        uint16_t c0 = mapping.byte_to_col[b0];
        uint16_t c1 = mapping.byte_to_col[b1];
        if (viewport_col >= c0 && viewport_col <= c1) {
            out_link->kind = DetectedLinkKind::kUrl;
            out_link->start_row = viewport_row;
            out_link->start_col = c0;
            out_link->end_row = viewport_row;
            out_link->end_col = c1;
            out_link->target = matched.substr(0, len);
            return true;
        }
    }

    static const std::regex kPathRegex(R"((?:~|/|\./|\.\./)[A-Za-z0-9._/\-]+(?::[0-9]+(?::[0-9]+)?)?)");
    for (auto it = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), kPathRegex);
         it != std::sregex_iterator(); ++it) {
        size_t len = static_cast<size_t>(it->length());
        if (len < 2) {
            continue;
        }
        size_t b0 = static_cast<size_t>(it->position());
        size_t b1 = std::min(b0 + len - 1u, mapping.byte_to_col.size() - 1u);
        uint16_t c0 = mapping.byte_to_col[b0];
        uint16_t c1 = mapping.byte_to_col[b1];
        if (viewport_col >= c0 && viewport_col <= c1) {
            out_link->kind = DetectedLinkKind::kFilepath;
            out_link->start_row = viewport_row;
            out_link->start_col = c0;
            out_link->end_row = viewport_row;
            out_link->end_col = c1;
            out_link->target = it->str();
            return true;
        }
    }

    return false;
}

std::vector<KittyGraphicPlacement> TerminalGetKittyPlacements(TvGhosttyTerminal terminal) {
    if (terminal == nullptr) {
        return {};
    }
    return terminal->kitty_placements;
}

uint64_t TerminalGetKittyGraphicsRevision(TvGhosttyTerminal terminal) {
    return terminal == nullptr ? 0u : terminal->kitty_revision;
}

}  // namespace termvr::vt
