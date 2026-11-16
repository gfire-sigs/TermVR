#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "font_manager.h"
#include "ghostty_vt_api.h"
#include "gles3_renderer.h"
#include "glyph_atlas.h"
#include "termvr_vt_types.h"
#include "vt_session_native.h"

using namespace termvr::vt;

namespace {

void TestCellGpuInstanceLayout() {
    static_assert(sizeof(CellGpuInstance) == 32, "CellGpuInstance size must be 32");
    static_assert(alignof(CellGpuInstance) == 16, "CellGpuInstance align must be 16");
    assert(offsetof(CellGpuInstance, grid_col) == 0);
    assert(offsetof(CellGpuInstance, grid_row) == 2);
    assert(offsetof(CellGpuInstance, span_cols) == 4);
    assert(offsetof(CellGpuInstance, atlas_page) == 5);
    assert(offsetof(CellGpuInstance, style_flags) == 6);
    assert(offsetof(CellGpuInstance, uv_x) == 8);
    assert(offsetof(CellGpuInstance, uv_y) == 10);
    assert(offsetof(CellGpuInstance, uv_w) == 12);
    assert(offsetof(CellGpuInstance, uv_h) == 14);
    assert(offsetof(CellGpuInstance, bearing_x) == 16);
    assert(offsetof(CellGpuInstance, bearing_y) == 18);
    assert(offsetof(CellGpuInstance, fg_rgba) == 20);
    assert(offsetof(CellGpuInstance, bg_rgba) == 24);
    assert(offsetof(CellGpuInstance, deco_rgba) == 28);
}

void TestVtSessionChunkedWriteAndDirtyRows() {
    VtSessionNative session(80, 24, 10000);
    assert(session.IsValid());

    // Write > 64 KiB payload to verify chunked vt_mu locking succeeds.
    std::string big(70000, 'A');
    big.append("\r\n");
    big.append("\x1b[38;2;255;100;50;4:3mStyled\x1b[0m \xED\x95\x9C\xEA\xB8\x80");
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(big.data()), big.size());

    ExtractedFrameSnapshot snap1;
    assert(session.ExtractDirtyFrame(false, &snap1));
    assert(snap1.any_dirty);
    assert(!snap1.dirty_rows.empty());

    // Immediately extracting again with no writes yields zero dirty rows.
    ExtractedFrameSnapshot snap2;
    assert(session.ExtractDirtyFrame(false, &snap2));
    assert(!snap2.any_dirty);
    assert(snap2.dirty_rows.empty());

    // Writing on a non-scrolling row dirties only old+new cursor rows (PARTIAL).
    const char* single = "\x1b[2;1Hnext";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(single), std::strlen(single));
    ExtractedFrameSnapshot snap3;
    assert(session.ExtractDirtyFrame(false, &snap3));
    assert(snap3.any_dirty);
    assert(!snap3.full_redraw);
    assert(snap3.dirty_rows.size() == 2u);

    // Writing within the same row dirties exactly 1 row (PARTIAL).
    const char* same_row = "\rsame";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(same_row), std::strlen(same_row));
    ExtractedFrameSnapshot snap3b;
    assert(session.ExtractDirtyFrame(false, &snap3b));
    assert(snap3b.any_dirty);
    assert(!snap3b.full_redraw);
    assert(snap3b.dirty_rows.size() == 1u);

    // Scrolling from the bottom row marks a FULL redraw.
    const char* scroll_cmd = "\x1b[24;1H\r\nscrolled";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(scroll_cmd), std::strlen(scroll_cmd));
    ExtractedFrameSnapshot snap4;
    assert(session.ExtractDirtyFrame(false, &snap4));
    assert(snap4.any_dirty);
    assert(snap4.full_redraw);
}

void TestEncodersPasteAndKittyGraphics() {
    VtSessionNative session(40, 12);
    // Enable DECCKM, modifyOtherKeys=2, Kitty keyboard protocol, SGR mouse, bracketed paste.
    const char* setup = "\x1b[?1h\x1b[>4;2m\x1b[>1u\x1b[?1002h\x1b[?1006h\x1b[?2004h";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(setup), std::strlen(setup));

    TvGhosttyKeyEvent up{};
    up.key = TV_GHOSTTY_KEY_UP;
    up.action = TV_GHOSTTY_KEY_ACTION_PRESS;
    auto up_bytes = session.EncodeKey(up);
    assert(std::string(up_bytes.begin(), up_bytes.end()) == "\x1bOA");

    TvGhosttyKeyEvent ctrl_c{};
    ctrl_c.key = TV_GHOSTTY_KEY_UNICODE;
    ctrl_c.action = TV_GHOSTTY_KEY_ACTION_PRESS;
    ctrl_c.mods = TV_GHOSTTY_MOD_CTRL;
    ctrl_c.unicode_codepoint = 'c';
    auto kitty_ctrl_c = session.EncodeKey(ctrl_c);
    assert(std::string(kitty_ctrl_c.begin(), kitty_ctrl_c.end()) == "\x1b[99;5u");

    TvGhosttyMouseEvent mpress{};
    mpress.action = TV_GHOSTTY_MOUSE_PRESS;
    mpress.button = TV_GHOSTTY_MOUSE_BUTTON_LEFT;
    mpress.col = 3;
    mpress.row = 4;
    auto mbytes = session.EncodeMouse(mpress);
    assert(std::string(mbytes.begin(), mbytes.end()) == "\x1b[<0;4;5M");

    assert(!VtSessionNative::IsPasteSafe("rm -rf /\n", 9));
    assert(VtSessionNative::IsPasteSafe("safe_text", 9));
    std::vector<uint8_t> pasted;
    assert(!session.EncodePaste("a\nb", 3, false, &pasted));
    assert(session.EncodePaste("a\x1b[201~b", 8, true, &pasted));
    assert(std::string(pasted.begin(), pasted.end()) == "\x1b[200~ab\x1b[201~");

    // Kitty APC G: 2x2 RGBA pixels (16 bytes encoded as "AAAAAP8AAP//AAAAAP//AA=="), z=-1
    const char* kitty_apc = "\x1b_Ga=T,f=32,s=2,v=2,c=2,r=1,z=-1;AAAAAP8AAP//AAAAAP//AA==\x1b\\";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(kitty_apc), std::strlen(kitty_apc));
    ExtractedFrameSnapshot snap;
    assert(session.ExtractDirtyFrame(true, &snap));
    assert(snap.kitty_placements.size() == 1u);
    assert(snap.kitty_placements[0].z_index == -1);
    assert(snap.kitty_placements[0].rgba_pixels.size() == 16u);
}

void TestFontLigaturesBoxDrawingAndAtlasEviction() {
    FontManager fm;
    FontConfigNative cfg;
    cfg.ligatures_enabled = true;
    fm.Configure(cfg);
    GlyphAtlasManager atlas;

    // Row with "a == b" where cursor is at col 0 (does not touch "=="), then cursor at col 2 (splits "==").
    std::vector<ExtractedCell> cells(6);
    const char chars[6] = {'a', ' ', '=', '=', ' ', 'b'};
    for (uint16_t i = 0; i < 6; ++i) {
        cells[i].col = i;
        cells[i].width = 1;
        cells[i].fg_rgba = 0xFFFFFFFFu;
        cells[i].bg_rgba = 0x000000FFu;
        cells[i].primary_codepoint = static_cast<uint32_t>(chars[i]);
        cells[i].grapheme_utf8.assign(1, chars[i]);
    }

    std::vector<CellGpuInstance> inst_lig;
    fm.ShapeRow(0, cells, 0, CursorShape::kBlock, 0xFF0000FFu, 0x333333FFu, &atlas, &inst_lig);
    assert(inst_lig.size() == 5u);  // "==" merged into span_cols = 2
    assert(inst_lig[2].grid_col == 2 && inst_lig[2].span_cols == 2);

    std::vector<CellGpuInstance> inst_split;
    fm.ShapeRow(0, cells, 2, CursorShape::kBlock, 0xFF0000FFu, 0x333333FFu, &atlas, &inst_split);
    assert(inst_split.size() == 6u);  // Cursor on col 2 splits "==" into 1-cell glyphs
    assert(inst_split[2].span_cols == 1 && inst_split[3].span_cols == 1);

    // Box drawing, block element, braille, and Hangul syllable rasterization.
    GlyphAtlasEntry entry{};
    assert(fm.EnsureGraphemeGlyph("\xE2\x94\x80", 0x2500u, 0, 1, &atlas, &entry));
    assert(entry.atlas_page == kAtlasPageMonoR8);
    assert(fm.EnsureGraphemeGlyph("\xE2\x96\x88", 0x2588u, 0, 1, &atlas, &entry));
    assert(fm.EnsureGraphemeGlyph("\xE2\xA3\xBF", 0x28FFu, 0, 1, &atlas, &entry));
    assert(fm.EnsureGraphemeGlyph("\xED\x95\x9C", 0xD55Cu, 0, 2, &atlas, &entry));
    assert(entry.span_cols == 2);

    // Verify LRU shelf eviction triggers above 90% occupancy.
    ShelfAtlasPage page(1);
    std::vector<uint8_t> big_glyph(1024u * 128u, 0xFFu);
    bool any_evicted = false;
    for (uint32_t i = 0; i < 40; ++i) {
        GlyphKey k{};
        k.cluster_hash = i + 1;
        k.codepoint = 0xE000u + i;
        k.font_size_px = 128;
        bool evicted = false;
        assert(page.Insert(k, 1024, 128, 0, 0, 1, big_glyph.data(), i + 1, &entry, &evicted));
        any_evicted = any_evicted || evicted;
    }
    assert(any_evicted);
    assert(page.EvictionCount() > 0);
}

void TestRendererCpuPath() {
    VtSessionNative sess(40, 10);
    const char* msg = "Hello \xEB\xB0\x94\xEB\xB3\xB4 == !=\r\nhttps://termvr.dev";
    sess.WritePtyBytes(reinterpret_cast<const uint8_t*>(msg), std::strlen(msg));

    Gles3Renderer renderer;
    assert(renderer.InitializeGl());

    PaneViewportSpec pane;
    pane.session_handle = static_cast<int64_t>(reinterpret_cast<intptr_t>(&sess));
    pane.x_px = 0;
    pane.y_px = 0;
    pane.width_px = 800;
    pane.height_px = 400;
    pane.focused = true;
    pane.cursor_blink_visible = true;
    pane.preedit.active = true;
    pane.preedit.text_utf8 = "\xED\x95\x9C";

    SplitDividerSpec div{800, 0, 2, 400, 0x30363DFFu};
    assert(renderer.RenderFrame(1080, 400, {pane}, {div}, true, 0x101418FFu));
    assert(renderer.LastDrawCallCount() >= 4u);
    assert(renderer.LastUploadedBytes() > 0u);

    renderer.NotifyContextLost(2);
    assert(renderer.ContextEpoch() == 2u);
    renderer.DetachSession(pane.session_handle);
}

void TestQueryRepliesAndDrain() {
    VtSessionNative session(80, 24);
    assert(session.IsValid());
    assert(session.ResizeGrid(60, 30, 12, 24));

    // Position cursor at row 5, col 10 (1-based) and query CPR, DSR, DA1, DA2, XTWINOPS, DECRQM, OSC 10/11, Kitty q.
    const std::string queries =
        "\x1b[5;10H"
        "\x1b[6n"
        "\x1b[5n"
        "\x1b[c"
        "\x1b[>c"
        "\x1b[14t"
        "\x1b[16t"
        "\x1b[18t"
        "\x1b[?7$p"
        "\x1b[?996n"
        "\x1b]10;?\x07"
        "\x1b]11;?\x1b\\"
        "\x1b_Gi=7,a=q;\x1b\\";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(queries.data()), queries.size());

    std::vector<uint8_t> raw = session.DrainPtyReplies();
    std::string replies(raw.begin(), raw.end());
    assert(replies.find("\x1b[5;10R") != std::string::npos);
    assert(replies.find("\x1b[0n") != std::string::npos);
    assert(replies.find("\x1b[?62;") != std::string::npos);
    assert(replies.find("\x1b[>1;") != std::string::npos);
    assert(replies.find("\x1b[4;720;720t") != std::string::npos);
    assert(replies.find("\x1b[6;24;12t") != std::string::npos);
    assert(replies.find("\x1b[8;30;60t") != std::string::npos);
    assert(replies.find("\x1b[?7;1$y") != std::string::npos);
    assert(replies.find("\x1b[?997;1n") != std::string::npos);
    assert(replies.find("\x1b]10;rgb:") != std::string::npos);
    assert(replies.find("\x1b]11;rgb:") != std::string::npos);
    assert(replies.find("\x1b_Gi=7;OK\x1b\\") != std::string::npos);

    // Subsequent drain must be empty.
    assert(session.DrainPtyReplies().empty());
}

void TestClearDecawmAndCursorBlinkRedraw() {
    VtSessionNative session(30, 8);
    assert(session.IsValid());

    // Push lines into scrollback then run clear (H + 2J + 3J).
    for (int i = 1; i <= 12; ++i) {
        std::string line = "line-" + std::to_string(i) + "\r\n";
        session.WritePtyBytes(reinterpret_cast<const uint8_t*>(line.data()), line.size());
    }
    assert(session.GetScrollbackCount() > 0u);
    session.SetScrollbackOffset(2u);
    assert(session.GetScrollbackOffset() == 2u);

    const std::string clear_seq = "\x1b[H\x1b[2J\x1b[3J";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(clear_seq.data()), clear_seq.size());
    assert(session.GetScrollbackCount() == 0u);
    assert(session.GetScrollbackOffset() == 0u);
    std::string cleared = session.FormatText(false, false, 0, 0, -1, -1, true, false);
    assert(cleared.find_first_not_of("\n ") == std::string::npos);

    // Fastfetch-style layout: disable DECAWM, draw logo lines, move up and right, write info.
    const std::string ff =
        "\x1b[?7l"
        "LOGO1\r\n"
        "LOGO2\r\n"
        "LOGO3"
        "\x1b[2A"
        "\x1b[3C"
        "OS: Debian\r\n"
        "\x1b[8C"
        "Kernel: 6.1"
        "\x1b[?7h";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(ff.data()), ff.size());
    std::string rendered = session.FormatText(false, false, 0, 0, -1, -1, true, false);
    assert(rendered.find("LOGO1   OS: Debian") != std::string::npos);
    assert(rendered.find("LOGO2   Kernel: 6.1") != std::string::npos);

    // Verify DECAWM=off does not wrap at right edge.
    const std::string long_nowrap = "\x1b[H\x1b[2J\x1b[?7l" + std::string(40, 'X') + "\x1b[?7h";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(long_nowrap.data()), long_nowrap.size());
    assert(session.GetCursorSnapshot().row == 0u);

    // Verify cursor blink toggle re-uploads cursor row without VT changes.
    Gles3Renderer renderer;
    assert(renderer.InitializeGl());
    FontConfigNative font_cfg;
    renderer.ConfigureFont(font_cfg);
    PaneViewportSpec pane{};
    pane.session_handle = reinterpret_cast<int64_t>(&session);
    pane.x_px = 0;
    pane.y_px = 0;
    pane.width_px = 600;
    pane.height_px = 320;
    pane.focused = true;
    pane.cursor_blink_visible = true;
    assert(renderer.RenderFrame(600, 320, {pane}, {}, true, 0x0D1117FFu));

    // Second render with identical blink state uploads 0 bytes; toggling blink uploads cursor row.
    assert(renderer.RenderFrame(600, 320, {pane}, {}, false, 0x0D1117FFu));
    assert(renderer.LastUploadedBytes() == 0u);
    pane.cursor_blink_visible = false;
    assert(renderer.RenderFrame(600, 320, {pane}, {}, false, 0x0D1117FFu));
    assert(renderer.LastUploadedBytes() > 0u);
    renderer.DetachSession(pane.session_handle);
}

void TestLegacyKeyEncodingParity() {
    VtSessionNative session(80, 24);
    assert(session.IsValid());

    auto encode = [&](TvGhosttyKey key, uint32_t mods, uint32_t cp, const char* utf8) -> std::string {
        TvGhosttyKeyEvent ev{};
        ev.key = key;
        ev.action = TV_GHOSTTY_KEY_ACTION_PRESS;
        ev.mods = mods;
        ev.unicode_codepoint = cp;
        ev.utf8_text = utf8;
        ev.utf8_len = utf8 != nullptr ? std::strlen(utf8) : 0;
        auto bytes = session.EncodeKey(ev);
        return std::string(bytes.begin(), bytes.end());
    };

    // Ctrl+a..z -> 0x01..0x1A
    for (char ch = 'a'; ch <= 'z'; ++ch) {
        char text[2] = {ch, '\0'};
        std::string out = encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, static_cast<uint32_t>(ch), text);
        assert(out.size() == 1u && static_cast<uint8_t>(out[0]) == static_cast<uint8_t>(ch - 'a' + 1));
    }

    // Soft-IME uppercase 'C' with Ctrl (without Shift) -> 0x03
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, 'C', "C") == std::string(1, '\x03'));

    // Punctuation Ctrl chords and Alt prefixes
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '[', "[") == std::string(1, '\x1b'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '\\', "\\") == std::string(1, '\x1c'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, ']', "]") == std::string(1, '\x1d'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '^', "^") == std::string(1, '\x1e'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '6', "6") == std::string(1, '\x1e'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '_', "_") == std::string(1, '\x1f'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '/', "/") == std::string(1, '\x1f'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '-', "-") == std::string(1, '\x1f'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, ' ', " ") == std::string(1, '\x00'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '@', "@") == std::string(1, '\x00'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_CTRL, '?', "?") == std::string(1, '\x7f'));
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_ALT | TV_GHOSTTY_MOD_CTRL, 'c', "c") == "\x1b\x03");
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_ALT, 'b', "b") == "\x1b" "b");
    assert(encode(TV_GHOSTTY_KEY_UNICODE, TV_GHOSTTY_MOD_ALT, 'f', "f") == "\x1b" "f");

    // Special keys & DECCKM arrow parity
    assert(encode(TV_GHOSTTY_KEY_TAB, TV_GHOSTTY_MOD_SHIFT, 0, nullptr) == "\x1b[Z");
    assert(encode(TV_GHOSTTY_KEY_ESCAPE, 0, 0, nullptr) == "\x1b");
    assert(encode(TV_GHOSTTY_KEY_TAB, 0, 0, nullptr) == "\t");
    assert(encode(TV_GHOSTTY_KEY_ENTER, 0, 0, nullptr) == "\r");
    assert(encode(TV_GHOSTTY_KEY_BACKSPACE, 0, 0, nullptr) == "\x7f");
    assert(encode(TV_GHOSTTY_KEY_BACKSPACE, TV_GHOSTTY_MOD_CTRL, 0, nullptr) == "\x08");
    assert(encode(TV_GHOSTTY_KEY_DELETE, 0, 0, nullptr) == "\x1b[3~");
    assert(encode(TV_GHOSTTY_KEY_HOME, 0, 0, nullptr) == "\x1b[H");
    assert(encode(TV_GHOSTTY_KEY_END, 0, 0, nullptr) == "\x1b[F");
    assert(encode(TV_GHOSTTY_KEY_PAGE_UP, 0, 0, nullptr) == "\x1b[5~");
    assert(encode(TV_GHOSTTY_KEY_PAGE_DOWN, 0, 0, nullptr) == "\x1b[6~");
    assert(encode(TV_GHOSTTY_KEY_UP, 0, 0, nullptr) == "\x1b[A");
    assert(encode(TV_GHOSTTY_KEY_DOWN, 0, 0, nullptr) == "\x1b[B");
    assert(encode(TV_GHOSTTY_KEY_RIGHT, 0, 0, nullptr) == "\x1b[C");
    assert(encode(TV_GHOSTTY_KEY_LEFT, 0, 0, nullptr) == "\x1b[D");

    const char* enable_decckm = "\x1b[?1h";
    session.WritePtyBytes(reinterpret_cast<const uint8_t*>(enable_decckm), std::strlen(enable_decckm));
    assert(encode(TV_GHOSTTY_KEY_UP, 0, 0, nullptr) == "\x1bOA");
    assert(encode(TV_GHOSTTY_KEY_DOWN, 0, 0, nullptr) == "\x1bOB");
    assert(encode(TV_GHOSTTY_KEY_RIGHT, 0, 0, nullptr) == "\x1bOC");
    assert(encode(TV_GHOSTTY_KEY_LEFT, 0, 0, nullptr) == "\x1bOD");
}

}  // namespace

int main() {
    TestCellGpuInstanceLayout();
    TestVtSessionChunkedWriteAndDirtyRows();
    TestEncodersPasteAndKittyGraphics();
    TestFontLigaturesBoxDrawingAndAtlasEviction();
    TestRendererCpuPath();
    TestQueryRepliesAndDrain();
    TestClearDecawmAndCursorBlinkRedraw();
    TestLegacyKeyEncodingParity();
    std::cout << "All native TermVR VT C++ tests passed!" << std::endl;
    return 0;
}
