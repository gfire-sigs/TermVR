#include "font_manager.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>

#if defined(__ANDROID__)
#include <android/font.h>
#include <android/system_fonts.h>
#endif

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include FT_SYNTHESIS_H
#include <hb.h>
#include <hb-ft.h>
#endif

namespace termvr::vt {

namespace {

uint64_t Fnv1a64(const char* data, size_t len) noexcept {
    uint64_t h = 14695981039346656037ull;
    for (size_t i = 0; i < len; ++i) {
        h ^= static_cast<uint8_t>(data[i]);
        h *= 1099511628211ull;
    }
    return h;
}

class Sha256 {
public:
    Sha256() {
        state_[0] = 0x6a09e667u;
        state_[1] = 0xbb67ae85u;
        state_[2] = 0x3c6ef372u;
        state_[3] = 0xa54ff53au;
        state_[4] = 0x510e527fu;
        state_[5] = 0x9b05688cu;
        state_[6] = 0x1f83d9abu;
        state_[7] = 0x5be0cd19u;
    }

    void Update(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            buf_[buf_len_++] = data[i];
            total_bits_ += 8ull;
            if (buf_len_ == 64u) {
                Transform();
                buf_len_ = 0;
            }
        }
    }

    std::string HexDigest() {
        buf_[buf_len_++] = 0x80u;
        if (buf_len_ > 56u) {
            while (buf_len_ < 64u) {
                buf_[buf_len_++] = 0;
            }
            Transform();
            buf_len_ = 0;
        }
        while (buf_len_ < 56u) {
            buf_[buf_len_++] = 0;
        }
        for (int i = 7; i >= 0; --i) {
            buf_[buf_len_++] = static_cast<uint8_t>((total_bits_ >> (i * 8)) & 0xFFu);
        }
        Transform();

        static constexpr char kHex[] = "0123456789abcdef";
        std::string out(64, '0');
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) {
                out[static_cast<size_t>(i * 8 + j)] = kHex[(state_[i] >> (28 - j * 4)) & 0xFu];
            }
        }
        return out;
    }

private:
    uint32_t state_[8]{};
    uint8_t buf_[64]{};
    size_t buf_len_ = 0;
    uint64_t total_bits_ = 0;

    static constexpr uint32_t Rotr(uint32_t x, uint32_t n) noexcept {
        return (x >> n) | (x << (32u - n));
    }

    void Transform() {
        static constexpr uint32_t k[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
        };
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(buf_[i * 4]) << 24u) |
                   (static_cast<uint32_t>(buf_[i * 4 + 1]) << 16u) |
                   (static_cast<uint32_t>(buf_[i * 4 + 2]) << 8u) |
                   static_cast<uint32_t>(buf_[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = Rotr(w[i - 15], 7u) ^ Rotr(w[i - 15], 18u) ^ (w[i - 15] >> 3u);
            uint32_t s1 = Rotr(w[i - 2], 17u) ^ Rotr(w[i - 2], 19u) ^ (w[i - 2] >> 10u);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = Rotr(e, 6u) ^ Rotr(e, 11u) ^ Rotr(e, 25u);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t temp1 = h + S1 + ch + k[i] + w[i];
            uint32_t S0 = Rotr(a, 2u) ^ Rotr(a, 13u) ^ Rotr(a, 22u);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t temp2 = S0 + maj;
            h = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }
};

bool VerifyFileSha256(const std::string& path, const std::string& expected_hex, std::vector<uint8_t>* out_bytes) {
    std::ifstream ifs(path, std::ios::binary | std::ios::ate);
    if (!ifs.is_open()) {
        return false;
    }
    std::streamsize sz = ifs.tellg();
    if (sz <= 0 || sz > 64 * 1024 * 1024) {
        return false;
    }
    ifs.seekg(0, std::ios::beg);
    out_bytes->resize(static_cast<size_t>(sz));
    if (!ifs.read(reinterpret_cast<char*>(out_bytes->data()), sz)) {
        out_bytes->clear();
        return false;
    }
    if (!expected_hex.empty()) {
        Sha256 hasher;
        hasher.Update(out_bytes->data(), out_bytes->size());
        std::string actual = hasher.HexDigest();
        std::string lower_exp = expected_hex;
        for (char& ch : lower_exp) {
            if (ch >= 'A' && ch <= 'Z') {
                ch = static_cast<char>(ch - 'A' + 'a');
            }
        }
        if (actual != lower_exp) {
            out_bytes->clear();
            return false;
        }
    }
    return true;
}

// Compact 5x7 bitmap font covering printable ASCII (0x20..0x7E) for self-contained fallback.
constexpr uint8_t kAscii5x7[95][7] = {
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, // ' '
    {0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04}, // '!'
    {0x0A, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00}, // '"'
    {0x0A, 0x0A, 0x1F, 0x0A, 0x1F, 0x0A, 0x0A}, // '#'
    {0x04, 0x0F, 0x14, 0x0E, 0x05, 0x1E, 0x04}, // '$'
    {0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03}, // '%'
    {0x08, 0x14, 0x14, 0x08, 0x15, 0x12, 0x0D}, // '&'
    {0x04, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00}, // '''
    {0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02}, // '('
    {0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08}, // ')'
    {0x00, 0x04, 0x15, 0x0E, 0x15, 0x04, 0x00}, // '*'
    {0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00}, // '+'
    {0x00, 0x00, 0x00, 0x00, 0x04, 0x04, 0x08}, // ','
    {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}, // '-'
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x06}, // '.'
    {0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10}, // '/'
    {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}, // '0'
    {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}, // '1'
    {0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F}, // '2'
    {0x1E, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x1E}, // '3'
    {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}, // '4'
    {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}, // '5'
    {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}, // '6'
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}, // '7'
    {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}, // '8'
    {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}, // '9'
    {0x00, 0x06, 0x06, 0x00, 0x06, 0x06, 0x00}, // ':'
    {0x00, 0x06, 0x06, 0x00, 0x06, 0x04, 0x08}, // ';'
    {0x02, 0x04, 0x08, 0x10, 0x08, 0x04, 0x02}, // '<'
    {0x00, 0x00, 0x1F, 0x00, 0x1F, 0x00, 0x00}, // '='
    {0x08, 0x04, 0x02, 0x01, 0x02, 0x04, 0x08}, // '>'
    {0x0E, 0x11, 0x01, 0x06, 0x04, 0x00, 0x04}, // '?'
    {0x0E, 0x11, 0x17, 0x15, 0x17, 0x10, 0x0E}, // '@'
    {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}, // 'A'
    {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}, // 'B'
    {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}, // 'C'
    {0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E}, // 'D'
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}, // 'E'
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}, // 'F'
    {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}, // 'G'
    {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}, // 'H'
    {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}, // 'I'
    {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C}, // 'J'
    {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}, // 'K'
    {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}, // 'L'
    {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}, // 'M'
    {0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11}, // 'N'
    {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}, // 'O'
    {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}, // 'P'
    {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}, // 'Q'
    {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}, // 'R'
    {0x0E, 0x11, 0x10, 0x0E, 0x01, 0x11, 0x0E}, // 'S'
    {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}, // 'T'
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}, // 'U'
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}, // 'V'
    {0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11}, // 'W'
    {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}, // 'X'
    {0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04}, // 'Y'
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}, // 'Z'
    {0x0E, 0x08, 0x08, 0x08, 0x08, 0x08, 0x0E}, // '['
    {0x10, 0x10, 0x08, 0x04, 0x02, 0x01, 0x01}, // '\'
    {0x0E, 0x02, 0x02, 0x02, 0x02, 0x02, 0x0E}, // ']'
    {0x04, 0x0A, 0x11, 0x00, 0x00, 0x00, 0x00}, // '^'
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F}, // '_'
    {0x08, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00}, // '`'
    {0x00, 0x00, 0x0E, 0x01, 0x0F, 0x11, 0x0F}, // 'a'
    {0x10, 0x10, 0x1E, 0x11, 0x11, 0x11, 0x1E}, // 'b'
    {0x00, 0x00, 0x0E, 0x11, 0x10, 0x11, 0x0E}, // 'c'
    {0x01, 0x01, 0x0F, 0x11, 0x11, 0x11, 0x0F}, // 'd'
    {0x00, 0x00, 0x0E, 0x11, 0x1F, 0x10, 0x0E}, // 'e'
    {0x06, 0x08, 0x1E, 0x08, 0x08, 0x08, 0x08}, // 'f'
    {0x00, 0x0F, 0x11, 0x11, 0x0F, 0x01, 0x0E}, // 'g'
    {0x10, 0x10, 0x1E, 0x11, 0x11, 0x11, 0x11}, // 'h'
    {0x04, 0x00, 0x0C, 0x04, 0x04, 0x04, 0x0E}, // 'i'
    {0x02, 0x00, 0x06, 0x02, 0x02, 0x12, 0x0C}, // 'j'
    {0x10, 0x10, 0x12, 0x14, 0x18, 0x14, 0x12}, // 'k'
    {0x0C, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}, // 'l'
    {0x00, 0x00, 0x1A, 0x15, 0x15, 0x11, 0x11}, // 'm'
    {0x00, 0x00, 0x1E, 0x11, 0x11, 0x11, 0x11}, // 'n'
    {0x00, 0x00, 0x0E, 0x11, 0x11, 0x11, 0x0E}, // 'o'
    {0x00, 0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10}, // 'p'
    {0x00, 0x0F, 0x11, 0x11, 0x0F, 0x01, 0x01}, // 'q'
    {0x00, 0x00, 0x16, 0x19, 0x10, 0x10, 0x10}, // 'r'
    {0x00, 0x00, 0x0F, 0x10, 0x0E, 0x01, 0x1E}, // 's'
    {0x08, 0x08, 0x1E, 0x08, 0x08, 0x09, 0x06}, // 't'
    {0x00, 0x00, 0x11, 0x11, 0x11, 0x11, 0x0F}, // 'u'
    {0x00, 0x00, 0x11, 0x11, 0x11, 0x0A, 0x04}, // 'v'
    {0x00, 0x00, 0x11, 0x11, 0x15, 0x15, 0x0A}, // 'w'
    {0x00, 0x00, 0x11, 0x0A, 0x04, 0x0A, 0x11}, // 'x'
    {0x00, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}, // 'y'
    {0x00, 0x00, 0x1F, 0x02, 0x04, 0x08, 0x1F}, // 'z'
    {0x02, 0x04, 0x04, 0x08, 0x04, 0x04, 0x02}, // '{'
    {0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}, // '|'
    {0x08, 0x04, 0x04, 0x02, 0x04, 0x04, 0x08}, // '}'
    {0x00, 0x00, 0x08, 0x15, 0x02, 0x00, 0x00}, // '~'
};

bool MatchLigatureOperator(const std::vector<ExtractedCell>& cells, size_t start, size_t* out_len) {
    if (start + 1 >= cells.size()) {
        return false;
    }
    auto eq_cp = [&](size_t idx, uint32_t cp) -> bool {
        return idx < cells.size() && !cells[idx].is_continuation &&
               cells[idx].width == 1 && cells[idx].primary_codepoint == cp;
    };
    uint32_t c0 = cells[start].primary_codepoint;
    uint32_t c1 = cells[start + 1].primary_codepoint;
    if (c0 == '/' && c1 == '/' && eq_cp(start + 2, '/')) {
        *out_len = 3;
        return true;
    }
    if (c0 == '=' && c1 == '=' && eq_cp(start + 2, '=')) {
        *out_len = 3;
        return true;
    }
    if (c0 == '<' && c1 == '=' && eq_cp(start + 2, '>')) {
        *out_len = 3;
        return true;
    }
    if ((c0 == '=' && c1 == '=') || (c0 == '!' && c1 == '=') ||
        (c0 == '>' && c1 == '=') || (c0 == '<' && c1 == '=') ||
        (c0 == '=' && c1 == '>') || (c0 == '|' && c1 == '>') ||
        (c0 == ':' && c1 == ':') || (c0 == '-' && c1 == '>') ||
        (c0 == '<' && c1 == '-')) {
        *out_len = 2;
        return true;
    }
    return false;
}

void DrawThickLine(
    std::vector<uint8_t>* buf,
    uint32_t w,
    uint32_t h,
    int x0,
    int y0,
    int x1,
    int y1,
    int thickness) {
    int dx = std::abs(x1 - x0);
    int dy = std::abs(y1 - y0);
    int steps = std::max({1, dx, dy});
    int half = std::max(0, thickness / 2);
    for (int s = 0; s <= steps; ++s) {
        int px = x0 + ((x1 - x0) * s) / steps;
        int py = y0 + ((y1 - y0) * s) / steps;
        for (int oy = -half; oy <= half; ++oy) {
            for (int ox = -half; ox <= half; ++ox) {
                int rx = px + ox;
                int ry = py + oy;
                if (rx >= 0 && rx < static_cast<int>(w) && ry >= 0 && ry < static_cast<int>(h)) {
                    (*buf)[static_cast<size_t>(ry) * w + static_cast<size_t>(rx)] = 255u;
                }
            }
        }
    }
}

void FillRect(std::vector<uint8_t>* buf, uint32_t w, uint32_t h, int x0, int y0, int x1, int y1, uint8_t val = 255u) {
    int cx0 = std::max(0, x0);
    int cy0 = std::max(0, y0);
    int cx1 = std::min(static_cast<int>(w), x1);
    int cy1 = std::min(static_cast<int>(h), y1);
    for (int y = cy0; y < cy1; ++y) {
        for (int x = cx0; x < cx1; ++x) {
            (*buf)[static_cast<size_t>(y) * w + static_cast<size_t>(x)] = val;
        }
    }
}

void DownsamplePixels(
    const uint8_t* src,
    uint32_t src_w,
    uint32_t src_h,
    uint32_t dst_w,
    uint32_t dst_h,
    uint32_t channels,
    std::vector<uint8_t>* out) {
    if (src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 || channels == 0) {
        out->clear();
        return;
    }
    out->assign(static_cast<size_t>(dst_w) * dst_h * channels, 0u);
    for (uint32_t dy = 0; dy < dst_h; ++dy) {
        float sy0 = (static_cast<float>(dy) * static_cast<float>(src_h)) / static_cast<float>(dst_h);
        float sy1 = (static_cast<float>(dy + 1u) * static_cast<float>(src_h)) / static_cast<float>(dst_h);
        uint32_t iy0 = std::min(src_h - 1u, static_cast<uint32_t>(sy0));
        uint32_t iy1 = std::min(src_h, std::max(iy0 + 1u, static_cast<uint32_t>(std::ceil(sy1))));
        for (uint32_t dx = 0; dx < dst_w; ++dx) {
            float sx0 = (static_cast<float>(dx) * static_cast<float>(src_w)) / static_cast<float>(dst_w);
            float sx1 = (static_cast<float>(dx + 1u) * static_cast<float>(src_w)) / static_cast<float>(dst_w);
            uint32_t ix0 = std::min(src_w - 1u, static_cast<uint32_t>(sx0));
            uint32_t ix1 = std::min(src_w, std::max(ix0 + 1u, static_cast<uint32_t>(std::ceil(sx1))));
            uint32_t count = (iy1 - iy0) * (ix1 - ix0);
            for (uint32_t c = 0; c < channels; ++c) {
                uint32_t sum = 0;
                for (uint32_t y = iy0; y < iy1; ++y) {
                    for (uint32_t x = ix0; x < ix1; ++x) {
                        sum += src[(static_cast<size_t>(y) * src_w + x) * channels + c];
                    }
                }
                (*out)[(static_cast<size_t>(dy) * dst_w + dx) * channels + c] =
                    static_cast<uint8_t>(count > 0 ? (sum / count) : 0u);
            }
        }
    }
}

}  // namespace

struct FontManager::Impl {
    std::vector<uint8_t> custom_font_bytes;
    std::vector<std::string> system_cjk_paths;
    std::string system_emoji_path;
    bool tier_loaded[6] = {false, false, false, false, false, false};

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    FT_Library ft_lib = nullptr;
    FT_Face tier1_custom = nullptr;
    FT_Face tier2_regular = nullptr;
    FT_Face tier2_bold = nullptr;
    FT_Face tier2_italic = nullptr;
    FT_Face tier2_bold_italic = nullptr;
    FT_Face tier3_nerd = nullptr;
    std::vector<FT_Face> tier4_cjk;
    FT_Face tier5_emoji = nullptr;
    hb_font_t* hb_regular = nullptr;
    hb_font_t* hb_bold = nullptr;
    hb_font_t* hb_italic = nullptr;
    hb_font_t* hb_bold_italic = nullptr;
    hb_font_t* hb_custom = nullptr;
#endif

    ~Impl() {
        ReleaseFaces();
#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
        if (ft_lib != nullptr) {
            FT_Done_FreeType(ft_lib);
            ft_lib = nullptr;
        }
#endif
    }

    void ReleaseFaces() {
#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
        auto free_hb = [](hb_font_t*& f) {
            if (f != nullptr) {
                hb_font_destroy(f);
                f = nullptr;
            }
        };
        free_hb(hb_regular);
        free_hb(hb_bold);
        free_hb(hb_italic);
        free_hb(hb_bold_italic);
        free_hb(hb_custom);

        auto free_ft = [](FT_Face& f) {
            if (f != nullptr) {
                FT_Done_Face(f);
                f = nullptr;
            }
        };
        free_ft(tier1_custom);
        free_ft(tier2_regular);
        free_ft(tier2_bold);
        free_ft(tier2_italic);
        free_ft(tier2_bold_italic);
        free_ft(tier3_nerd);
        for (FT_Face& cjk : tier4_cjk) {
            free_ft(cjk);
        }
        tier4_cjk.clear();
        free_ft(tier5_emoji);
#endif
        for (bool& b : tier_loaded) {
            b = false;
        }
    }
};

FontManager::FontManager() : impl_(std::make_unique<Impl>()) {
#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    FT_Init_FreeType(&impl_->ft_lib);
#endif
    DiscoverAndroidSystemFonts();
    RecomputeMetrics();
}

FontManager::~FontManager() = default;

void FontManager::DiscoverAndroidSystemFonts() {
    impl_->system_cjk_paths.clear();
    impl_->system_emoji_path.clear();

#if defined(__ANDROID__)
    ASystemFontIterator* iter = ASystemFontIterator_open();
    if (iter != nullptr) {
        AFont* font = nullptr;
        while ((font = ASystemFontIterator_next(iter)) != nullptr) {
            const char* path = AFont_getFontFilePath(font);
            const char* locale = AFont_getLocale(font);
            if (path != nullptr) {
                std::string p(path);
                if (p.find("ColorEmoji") != std::string::npos || p.find("Emoji") != std::string::npos) {
                    if (impl_->system_emoji_path.empty()) {
                        impl_->system_emoji_path = p;
                    }
                } else if (AFont_getWeight(font) == 400 && !AFont_isItalic(font)) {
                    if (p.find("CJK") != std::string::npos ||
                        p.find("Hangul") != std::string::npos ||
                        p.find("NotoSansKR") != std::string::npos ||
                        p.find("NotoSansJP") != std::string::npos ||
                        p.find("NotoSansSC") != std::string::npos ||
                        (locale != nullptr && (std::strstr(locale, "ko") != nullptr ||
                                               std::strstr(locale, "ja") != nullptr ||
                                               std::strstr(locale, "zh") != nullptr))) {
                        if (std::find(impl_->system_cjk_paths.begin(), impl_->system_cjk_paths.end(), p) ==
                            impl_->system_cjk_paths.end()) {
                            impl_->system_cjk_paths.push_back(p);
                        }
                    }
                }
            }
            AFont_close(font);
        }
        ASystemFontIterator_close(iter);
    }
#endif
}

void FontManager::SetBundledAssets(BundledFontAssets assets) {
    assets_ = std::move(assets);
    Configure(config_);
}

bool FontManager::Configure(const FontConfigNative& config) {
    config_ = config;
    config_.size_sp = std::clamp(config_.size_sp, 8.0f, 32.0f);
    config_.cell_height_multiplier = std::clamp(config_.cell_height_multiplier, 1.0f, 1.6f);
    config_.density = std::clamp(config_.density, 0.75f, 6.0f);

    impl_->ReleaseFaces();
    impl_->custom_font_bytes.clear();

    if (!config_.custom_font_path.empty()) {
        if (VerifyFileSha256(config_.custom_font_path, config_.custom_font_sha256, &impl_->custom_font_bytes)) {
            impl_->tier_loaded[1] = true;
        }
    }
    if (!assets_.jetbrains_regular.empty()) {
        impl_->tier_loaded[2] = true;
    }
    if (config_.nerd_font_symbols_enabled && !assets_.nerd_symbols.empty()) {
        impl_->tier_loaded[3] = true;
    }
    if (!impl_->system_cjk_paths.empty()) {
        impl_->tier_loaded[4] = true;
    }
    if (!impl_->system_emoji_path.empty()) {
        impl_->tier_loaded[5] = true;
    }

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    uint32_t px_size = static_cast<uint32_t>(std::lround(config_.size_sp * config_.density));
    if (impl_->ft_lib != nullptr) {
        auto load_mem = [&](const std::vector<uint8_t>& bytes, FT_Face* out) {
            if (bytes.empty()) return;
            if (FT_New_Memory_Face(impl_->ft_lib, bytes.data(), static_cast<FT_Long>(bytes.size()), 0, out) == 0) {
                FT_Set_Pixel_Sizes(*out, 0, px_size);
            }
        };
        if (!impl_->custom_font_bytes.empty()) {
            load_mem(impl_->custom_font_bytes, &impl_->tier1_custom);
            if (impl_->tier1_custom != nullptr) {
                impl_->hb_custom = hb_ft_font_create_referenced(impl_->tier1_custom);
            }
        }
        load_mem(assets_.jetbrains_regular, &impl_->tier2_regular);
        load_mem(assets_.jetbrains_bold, &impl_->tier2_bold);
        load_mem(assets_.jetbrains_italic, &impl_->tier2_italic);
        load_mem(assets_.jetbrains_bold_italic, &impl_->tier2_bold_italic);
        if (impl_->tier2_regular != nullptr) impl_->hb_regular = hb_ft_font_create_referenced(impl_->tier2_regular);
        if (impl_->tier2_bold != nullptr) impl_->hb_bold = hb_ft_font_create_referenced(impl_->tier2_bold);
        if (impl_->tier2_italic != nullptr) impl_->hb_italic = hb_ft_font_create_referenced(impl_->tier2_italic);
        if (impl_->tier2_bold_italic != nullptr) impl_->hb_bold_italic = hb_ft_font_create_referenced(impl_->tier2_bold_italic);
        if (config_.nerd_font_symbols_enabled) {
            load_mem(assets_.nerd_symbols, &impl_->tier3_nerd);
        }
        for (const std::string& p : impl_->system_cjk_paths) {
            FT_Face f = nullptr;
            if (FT_New_Face(impl_->ft_lib, p.c_str(), 0, &f) == 0) {
                FT_Set_Pixel_Sizes(f, 0, px_size);
                impl_->tier4_cjk.push_back(f);
            }
        }
        if (!impl_->system_emoji_path.empty()) {
            FT_Face f = nullptr;
            if (FT_New_Face(impl_->ft_lib, impl_->system_emoji_path.c_str(), 0, &f) == 0) {
                if (f->num_fixed_sizes > 0) {
                    FT_Select_Size(f, 0);
                } else {
                    FT_Set_Pixel_Sizes(f, 0, px_size);
                }
                impl_->tier5_emoji = f;
            }
        }
    }
#endif

    RecomputeMetrics();
    return true;
}

void FontManager::RecomputeMetrics() {
    float px = config_.size_sp * config_.density;
    uint32_t cell_w = static_cast<uint32_t>(std::max(6.0f, std::round(px * 0.6f)));
    uint32_t cell_h = static_cast<uint32_t>(std::max(10.0f, std::round(px * config_.cell_height_multiplier)));
    int32_t baseline = static_cast<int32_t>(std::round(static_cast<float>(cell_h) * 0.78f));

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    FT_Face primary = impl_->tier1_custom != nullptr ? impl_->tier1_custom : impl_->tier2_regular;
    if (primary != nullptr && primary->size != nullptr) {
        if (FT_Load_Char(primary, 'M', FT_LOAD_DEFAULT) == 0 && primary->glyph->advance.x > 0) {
            cell_w = static_cast<uint32_t>(std::max<long>(6, (primary->glyph->advance.x + 32) >> 6));
        }
        int32_t line_px = static_cast<int32_t>((primary->size->metrics.height + 32) >> 6);
        if (line_px > 0) {
            cell_h = static_cast<uint32_t>(std::max<int32_t>(
                10, static_cast<int32_t>(std::round(static_cast<float>(line_px) * config_.cell_height_multiplier))));
        }
        int32_t asc = static_cast<int32_t>((primary->size->metrics.ascender + 32) >> 6);
        if (asc > 0 && asc < static_cast<int32_t>(cell_h)) {
            int32_t extra_leading = std::max<int32_t>(0, static_cast<int32_t>(cell_h) - line_px);
            baseline = std::min<int32_t>(static_cast<int32_t>(cell_h) - 1, asc + extra_leading / 2);
        }
    }
#endif

    metrics_.cell_width_px = cell_w;
    metrics_.cell_height_px = cell_h;
    metrics_.baseline_px = baseline;
    metrics_.size_sp = config_.size_sp;
    metrics_.density = config_.density;
}

bool FontManager::HasTierLoaded(int tier) const noexcept {
    if (tier < 1 || tier > 5) {
        return false;
    }
    return impl_->tier_loaded[tier];
}

bool FontManager::IsBoxOrBlockOrBraille(uint32_t cp) noexcept {
    return (cp >= 0x2500u && cp <= 0x257Fu) ||
           (cp >= 0x2580u && cp <= 0x259Fu) ||
           (cp >= 0x2800u && cp <= 0x28FFu);
}

bool FontManager::IsNerdFontPua(uint32_t cp) noexcept {
    return (cp >= 0xE000u && cp <= 0xF8FFu) ||
           (cp >= 0xF0000u && cp <= 0xFFFFDu) ||
           (cp >= 0x100000u && cp <= 0x10FFFDu);
}

bool FontManager::IsCjkOrHangulWide(uint32_t cp) noexcept {
    return (cp >= 0xAC00u && cp <= 0xD7A3u) ||
           (cp >= 0x1100u && cp <= 0x11FFu) ||
           (cp >= 0x3130u && cp <= 0x318Fu) ||
           (cp >= 0x3040u && cp <= 0x30FFu) ||
           (cp >= 0x4E00u && cp <= 0x9FFFu) ||
           (cp >= 0x3400u && cp <= 0x4DBFu) ||
           (cp >= 0x20000u && cp <= 0x2FA1Fu);
}

bool FontManager::IsEmojiCodepoint(uint32_t cp, const std::string& utf8) noexcept {
    if (utf8.find("\xEF\xB8\x8F") != std::string::npos ||
        utf8.find("\xE2\x80\x8D") != std::string::npos) {
        return true;
    }
    return (cp >= 0x1F300u && cp <= 0x1FAFFu) ||
           (cp >= 0x2600u && cp <= 0x27BFu);
}

bool FontManager::RasterizeProceduralBoxBlockBraille(
    uint32_t cp,
    uint32_t cell_w,
    uint32_t cell_h,
    std::vector<uint8_t>* out_r8) const {
    if (!IsBoxOrBlockOrBraille(cp) || cell_w == 0 || cell_h == 0) {
        return false;
    }
    out_r8->assign(static_cast<size_t>(cell_w) * static_cast<size_t>(cell_h), 0u);
    int w = static_cast<int>(cell_w);
    int h = static_cast<int>(cell_h);
    int cx = w / 2;
    int cy = h / 2;
    int lw = std::max(1, static_cast<int>(std::round(static_cast<float>(w) / 8.0f)));

    if (cp >= 0x2580u && cp <= 0x259Fu) {
        if (cp == 0x2588u) {
            FillRect(out_r8, cell_w, cell_h, 0, 0, w, h);
        } else if (cp == 0x2580u) {
            FillRect(out_r8, cell_w, cell_h, 0, 0, w, cy);
        } else if (cp >= 0x2581u && cp <= 0x2587u) {
            int eighths = static_cast<int>(cp - 0x2580u);
            int top = h - (h * eighths) / 8;
            FillRect(out_r8, cell_w, cell_h, 0, top, w, h);
        } else if (cp >= 0x2589u && cp <= 0x258Fu) {
            int eighths = static_cast<int>(0x2590u - cp);
            int right = (w * eighths) / 8;
            FillRect(out_r8, cell_w, cell_h, 0, 0, right, h);
        } else if (cp == 0x2590u) {
            FillRect(out_r8, cell_w, cell_h, cx, 0, w, h);
        } else if (cp == 0x2591u || cp == 0x2592u || cp == 0x2593u) {
            uint8_t shade = (cp == 0x2591u) ? 64u : (cp == 0x2592u ? 128u : 192u);
            FillRect(out_r8, cell_w, cell_h, 0, 0, w, h, shade);
        } else if (cp == 0x2594u) {
            FillRect(out_r8, cell_w, cell_h, 0, 0, w, std::max(1, h / 8));
        } else if (cp == 0x2595u) {
            FillRect(out_r8, cell_w, cell_h, w - std::max(1, w / 8), 0, w, h);
        } else {
            uint32_t q = cp - 0x2596u;
            bool tl = (q == 2 || q == 3 || q == 5 || q == 7 || q == 8);
            bool tr = (q == 1 || q == 4 || q == 5 || q == 6 || q == 7);
            bool bl = (q == 0 || q == 3 || q == 4 || q == 6 || q == 8);
            bool br = (q == 1 || q == 2 || q == 4 || q == 5 || q == 6 || q == 9);
            if (tl) FillRect(out_r8, cell_w, cell_h, 0, 0, cx, cy);
            if (tr) FillRect(out_r8, cell_w, cell_h, cx, 0, w, cy);
            if (bl) FillRect(out_r8, cell_w, cell_h, 0, cy, cx, h);
            if (br) FillRect(out_r8, cell_w, cell_h, cx, cy, w, h);
        }
        return true;
    }

    if (cp >= 0x2800u && cp <= 0x28FFu) {
        uint8_t mask = static_cast<uint8_t>(cp - 0x2800u);
        int dot_r = std::max(1, std::min(w / 5, h / 10));
        static constexpr int kBitToCol[8] = {0, 0, 0, 1, 1, 1, 0, 1};
        static constexpr int kBitToRow[8] = {0, 1, 2, 0, 1, 2, 3, 3};
        for (int b = 0; b < 8; ++b) {
            if ((mask & (1u << b)) == 0) continue;
            int dx = (kBitToCol[b] == 0) ? (w / 4) : ((w * 3) / 4);
            int dy = ((kBitToRow[b] * 2 + 1) * h) / 8;
            FillRect(out_r8, cell_w, cell_h, dx - dot_r, dy - dot_r, dx + dot_r + 1, dy + dot_r + 1);
        }
        return true;
    }

    // Box drawing U+2500..U+257F: exact edge-to-edge segments with zero subpixel gaps.
    bool left = false, right = false, up = false, down = false;
    bool heavy = false, dbl = false;
    switch (cp) {
        case 0x2500: left = right = true; break;
        case 0x2501: left = right = true; heavy = true; break;
        case 0x2502: up = down = true; break;
        case 0x2503: up = down = true; heavy = true; break;
        case 0x250C: case 0x256D: right = down = true; break;
        case 0x250F: right = down = true; heavy = true; break;
        case 0x2510: case 0x256E: left = down = true; break;
        case 0x2513: left = down = true; heavy = true; break;
        case 0x2514: case 0x2570: right = up = true; break;
        case 0x2517: right = up = true; heavy = true; break;
        case 0x2518: case 0x256F: left = up = true; break;
        case 0x251B: left = up = true; heavy = true; break;
        case 0x251C: up = down = right = true; break;
        case 0x2523: up = down = right = true; heavy = true; break;
        case 0x2524: up = down = left = true; break;
        case 0x252B: up = down = left = true; heavy = true; break;
        case 0x252C: left = right = down = true; break;
        case 0x2533: left = right = down = true; heavy = true; break;
        case 0x2534: left = right = up = true; break;
        case 0x253B: left = right = up = true; heavy = true; break;
        case 0x253C: left = right = up = down = true; break;
        case 0x254B: left = right = up = down = true; heavy = true; break;
        case 0x2550: left = right = true; dbl = true; break;
        case 0x2551: up = down = true; dbl = true; break;
        case 0x2554: right = down = true; dbl = true; break;
        case 0x2557: left = down = true; dbl = true; break;
        case 0x255A: right = up = true; dbl = true; break;
        case 0x255D: left = up = true; dbl = true; break;
        case 0x2560: up = down = right = true; dbl = true; break;
        case 0x2563: up = down = left = true; dbl = true; break;
        case 0x2566: left = right = down = true; dbl = true; break;
        case 0x2569: left = right = up = true; dbl = true; break;
        case 0x256C: left = right = up = down = true; dbl = true; break;
        case 0x2571: DrawThickLine(out_r8, cell_w, cell_h, 0, h - 1, w - 1, 0, lw); return true;
        case 0x2572: DrawThickLine(out_r8, cell_w, cell_h, 0, 0, w - 1, h - 1, lw); return true;
        case 0x2573:
            DrawThickLine(out_r8, cell_w, cell_h, 0, h - 1, w - 1, 0, lw);
            DrawThickLine(out_r8, cell_w, cell_h, 0, 0, w - 1, h - 1, lw);
            return true;
        case 0x2574: left = true; break;
        case 0x2575: up = true; break;
        case 0x2576: right = true; break;
        case 0x2577: down = true; break;
        default:
            left = right = true;
            break;
    }

    int stroke = heavy ? std::max(2, lw * 2) : lw;
    if (dbl) {
        int off = std::max(2, lw + 1);
        if (left) {
            FillRect(out_r8, cell_w, cell_h, 0, cy - off, cx + off, cy - off + lw);
            FillRect(out_r8, cell_w, cell_h, 0, cy + off - lw, cx + off, cy + off);
        }
        if (right) {
            FillRect(out_r8, cell_w, cell_h, cx - off, cy - off, w, cy - off + lw);
            FillRect(out_r8, cell_w, cell_h, cx - off, cy + off - lw, w, cy + off);
        }
        if (up) {
            FillRect(out_r8, cell_w, cell_h, cx - off, 0, cx - off + lw, cy + off);
            FillRect(out_r8, cell_w, cell_h, cx + off - lw, 0, cx + off, cy + off);
        }
        if (down) {
            FillRect(out_r8, cell_w, cell_h, cx - off, cy - off, cx - off + lw, h);
            FillRect(out_r8, cell_w, cell_h, cx + off - lw, cy - off, cx + off, h);
        }
    } else {
        int half = stroke / 2;
        if (left) FillRect(out_r8, cell_w, cell_h, 0, cy - half, cx + half + 1, cy - half + stroke);
        if (right) FillRect(out_r8, cell_w, cell_h, cx - half, cy - half, w, cy - half + stroke);
        if (up) FillRect(out_r8, cell_w, cell_h, cx - half, 0, cx - half + stroke, cy + half + 1);
        if (down) FillRect(out_r8, cell_w, cell_h, cx - half, cy - half, cx - half + stroke, h);
    }
    return true;
}

bool FontManager::RasterizeFallbackGlyph(
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
    int16_t* out_bearing_y) const {
    uint32_t box_w = cell_w * std::max<uint8_t>(1, span_cols);
    uint32_t box_h = cell_h;

    if (IsEmojiCodepoint(cp, utf8)) {
        *out_is_color = true;
        *out_w = static_cast<uint16_t>(box_w);
        *out_h = static_cast<uint16_t>(box_h);
        *out_bearing_x = 0;
        *out_bearing_y = static_cast<int16_t>(baseline_px);
        out_pixels->assign(static_cast<size_t>(box_w) * static_cast<size_t>(box_h) * 4u, 0u);
        int cx = static_cast<int>(box_w) / 2;
        int cy = static_cast<int>(box_h) / 2;
        int r = std::max(3, static_cast<int>(std::min(box_w, box_h)) / 3);
        for (int y = 0; y < static_cast<int>(box_h); ++y) {
            for (int x = 0; x < static_cast<int>(box_w); ++x) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= r * r) {
                    size_t idx = (static_cast<size_t>(y) * box_w + static_cast<size_t>(x)) * 4u;
                    (*out_pixels)[idx + 0] = 0xFAu;
                    (*out_pixels)[idx + 1] = 0xC8u;
                    (*out_pixels)[idx + 2] = 0x32u;
                    (*out_pixels)[idx + 3] = 0xFFu;
                }
            }
        }
        return true;
    }

    *out_is_color = false;
    *out_w = static_cast<uint16_t>(box_w);
    *out_h = static_cast<uint16_t>(box_h);
    *out_bearing_x = 0;
    *out_bearing_y = static_cast<int16_t>(baseline_px);
    out_pixels->assign(static_cast<size_t>(box_w) * static_cast<size_t>(box_h), 0u);

    // Operator ligatures when built without upstream HarfBuzz.
    if (span_cols >= 2 && utf8.size() >= 2 && utf8.size() <= 3 &&
        (utf8 == "==" || utf8 == "!=" || utf8 == ">=" || utf8 == "<=" ||
         utf8 == "=>" || utf8 == "|>" || utf8 == "::" || utf8 == "///" ||
         utf8 == "->" || utf8 == "<-" || utf8 == "===" || utf8 == "<=>")) {
        int lw = std::max(1, static_cast<int>(box_h / 12));
        int mid_y = static_cast<int>(box_h) / 2;
        int pad = std::max(2, static_cast<int>(cell_w) / 5);
        int rw = static_cast<int>(box_w) - pad;
        if (utf8 == "==" || utf8 == "===") {
            int gap = std::max(2, static_cast<int>(box_h) / 7);
            FillRect(out_pixels, box_w, box_h, pad, mid_y - gap, rw, mid_y - gap + lw);
            FillRect(out_pixels, box_w, box_h, pad, mid_y + gap - lw, rw, mid_y + gap);
            if (utf8 == "===") {
                FillRect(out_pixels, box_w, box_h, pad, mid_y - lw / 2, rw, mid_y + (lw + 1) / 2);
            }
        } else if (utf8 == "!=") {
            int gap = std::max(2, static_cast<int>(box_h) / 7);
            FillRect(out_pixels, box_w, box_h, pad, mid_y - gap, rw, mid_y - gap + lw);
            FillRect(out_pixels, box_w, box_h, pad, mid_y + gap - lw, rw, mid_y + gap);
            DrawThickLine(out_pixels, box_w, box_h, rw - pad, mid_y - gap * 2, pad + 2, mid_y + gap * 2, lw);
        } else if (utf8 == "->" || utf8 == "=>") {
            if (utf8 == "=>") {
                int gap = std::max(2, static_cast<int>(box_h) / 7);
                FillRect(out_pixels, box_w, box_h, pad, mid_y - gap, rw - 3, mid_y - gap + lw);
                FillRect(out_pixels, box_w, box_h, pad, mid_y + gap - lw, rw - 3, mid_y + gap);
            } else {
                FillRect(out_pixels, box_w, box_h, pad, mid_y - lw / 2, rw, mid_y + (lw + 1) / 2);
            }
            DrawThickLine(out_pixels, box_w, box_h, rw - static_cast<int>(cell_w) / 2, mid_y - static_cast<int>(box_h) / 5, rw, mid_y, lw);
            DrawThickLine(out_pixels, box_w, box_h, rw - static_cast<int>(cell_w) / 2, mid_y + static_cast<int>(box_h) / 5, rw, mid_y, lw);
        } else if (utf8 == "<-") {
            FillRect(out_pixels, box_w, box_h, pad, mid_y - lw / 2, rw, mid_y + (lw + 1) / 2);
            DrawThickLine(out_pixels, box_w, box_h, pad, mid_y, pad + static_cast<int>(cell_w) / 2, mid_y - static_cast<int>(box_h) / 5, lw);
            DrawThickLine(out_pixels, box_w, box_h, pad, mid_y, pad + static_cast<int>(cell_w) / 2, mid_y + static_cast<int>(box_h) / 5, lw);
        } else if (utf8 == ">=" || utf8 == "<=") {
            int top_y = mid_y - static_cast<int>(box_h) / 5;
            int bot_y = mid_y + static_cast<int>(box_h) / 10;
            if (utf8 == ">=") {
                DrawThickLine(out_pixels, box_w, box_h, pad, top_y, rw, (top_y + bot_y) / 2, lw);
                DrawThickLine(out_pixels, box_w, box_h, rw, (top_y + bot_y) / 2, pad, bot_y, lw);
            } else {
                DrawThickLine(out_pixels, box_w, box_h, rw, top_y, pad, (top_y + bot_y) / 2, lw);
                DrawThickLine(out_pixels, box_w, box_h, pad, (top_y + bot_y) / 2, rw, bot_y, lw);
            }
            FillRect(out_pixels, box_w, box_h, pad, bot_y + 2, rw, bot_y + 2 + lw);
        } else if (utf8 == "|>") {
            FillRect(out_pixels, box_w, box_h, pad, mid_y - static_cast<int>(box_h) / 4, pad + lw, mid_y + static_cast<int>(box_h) / 4);
            DrawThickLine(out_pixels, box_w, box_h, pad + lw * 2, mid_y - static_cast<int>(box_h) / 4, rw, mid_y, lw);
            DrawThickLine(out_pixels, box_w, box_h, pad + lw * 2, mid_y + static_cast<int>(box_h) / 4, rw, mid_y, lw);
        } else if (utf8 == "::") {
            int d = std::max(2, lw + 1);
            int c1 = static_cast<int>(box_w) / 3;
            int c2 = (static_cast<int>(box_w) * 2) / 3;
            FillRect(out_pixels, box_w, box_h, c1 - d / 2, mid_y - d * 2, c1 + d, mid_y - d);
            FillRect(out_pixels, box_w, box_h, c1 - d / 2, mid_y + d, c1 + d, mid_y + d * 2);
            FillRect(out_pixels, box_w, box_h, c2 - d / 2, mid_y - d * 2, c2 + d, mid_y - d);
            FillRect(out_pixels, box_w, box_h, c2 - d / 2, mid_y + d, c2 + d, mid_y + d * 2);
        } else {
            for (int k = 0; k < 3; ++k) {
                int sx = pad + k * static_cast<int>(cell_w);
                DrawThickLine(out_pixels, box_w, box_h, sx, static_cast<int>(box_h) - 3, sx + static_cast<int>(cell_w) - 3, 3, lw);
            }
        }
        return true;
    }

    if (cp >= 0x20u && cp <= 0x7Eu) {
        const uint8_t* rows = kAscii5x7[cp - 0x20u];
        int pad_x = std::max(1, static_cast<int>(box_w) / 6);
        int pad_y = std::max(1, static_cast<int>(box_h) / 6);
        int avail_w = std::max(5, static_cast<int>(box_w) - pad_x * 2);
        int avail_h = std::max(7, static_cast<int>(box_h) - pad_y * 2);
        bool bold = (style_flags & kStyleBold) != 0;
        bool italic = (style_flags & kStyleItalic) != 0;

        for (int gy = 0; gy < avail_h; ++gy) {
            int src_y = (gy * 7) / avail_h;
            uint8_t row_bits = rows[src_y];
            int slant = italic ? ((avail_h - 1 - gy) / 6) : 0;
            for (int gx = 0; gx < avail_w; ++gx) {
                int src_x = (gx * 5) / avail_w;
                if ((row_bits & (1u << (4 - src_x))) != 0) {
                    int px = pad_x + gx + slant;
                    int py = pad_y + gy;
                    if (px >= 0 && px < static_cast<int>(box_w) && py >= 0 && py < static_cast<int>(box_h)) {
                        (*out_pixels)[static_cast<size_t>(py) * box_w + static_cast<size_t>(px)] = 255u;
                        if (bold && px + 1 < static_cast<int>(box_w)) {
                            (*out_pixels)[static_cast<size_t>(py) * box_w + static_cast<size_t>(px + 1)] = 255u;
                        }
                    }
                }
            }
        }
        return true;
    }

    // Hangul syllables U+AC00..U+D7A3 procedural compositional fallback when system CJK fonts are absent.
    if (cp >= 0xAC00u && cp <= 0xD7A3u) {
        uint32_t s_idx = cp - 0xAC00u;
        uint32_t cho = s_idx / (21u * 28u);
        uint32_t jung = (s_idx / 28u) % 21u;
        uint32_t jong = s_idx % 28u;
        int lw = std::max(1, static_cast<int>(box_w / 14));
        int l = std::max(2, static_cast<int>(box_w) / 8);
        int r = static_cast<int>(box_w) - l;
        int t = std::max(2, static_cast<int>(box_h) / 8);
        int b = static_cast<int>(box_h) - t;
        int mx = (l + r) / 2;
        int my = (t + b) / 2;

        // Initial consonant box outline variant by cho.
        FillRect(out_pixels, box_w, box_h, l, t, mx - 1, t + lw);
        if ((cho % 3u) != 0) {
            FillRect(out_pixels, box_w, box_h, l, t, l + lw, my);
        }
        if ((cho % 2u) == 0) {
            FillRect(out_pixels, box_w, box_h, mx - 1 - lw, t, mx - 1, my);
        }
        FillRect(out_pixels, box_w, box_h, l, my - lw, mx - 1, my);

        // Medial vowel vertical/horizontal stroke.
        if (jung < 8u || jung == 20u) {
            FillRect(out_pixels, box_w, box_h, mx + 2, t, mx + 2 + lw, my + 2);
            FillRect(out_pixels, box_w, box_h, mx + 2, (t + my) / 2, r, (t + my) / 2 + lw);
        } else {
            FillRect(out_pixels, box_w, box_h, l, my + 1, r, my + 1 + lw);
            FillRect(out_pixels, box_w, box_h, mx, t, mx + lw, my + 1);
        }
        // Final consonant (jongseong) indicator at bottom.
        if (jong > 0) {
            int jb = b - lw;
            FillRect(out_pixels, box_w, box_h, l + 1, my + 3, r - 1, my + 3 + lw);
            if ((jong % 2u) == 1u) {
                FillRect(out_pixels, box_w, box_h, l + 1, jb, r - 1, jb + lw);
            } else {
                FillRect(out_pixels, box_w, box_h, mx - lw, my + 3, mx + lw, b);
            }
        }
        return true;
    }

    // Generic non-empty glyph box for remaining PUA / Unicode when no font face is available.
    int m = std::max(2, static_cast<int>(box_w) / 5);
    int lw = std::max(1, static_cast<int>(box_w) / 10);
    FillRect(out_pixels, box_w, box_h, m, m, static_cast<int>(box_w) - m, m + lw);
    FillRect(out_pixels, box_w, box_h, m, static_cast<int>(box_h) - m - lw, static_cast<int>(box_w) - m, static_cast<int>(box_h) - m);
    FillRect(out_pixels, box_w, box_h, m, m, m + lw, static_cast<int>(box_h) - m);
    FillRect(out_pixels, box_w, box_h, static_cast<int>(box_w) - m - lw, m, static_cast<int>(box_w) - m, static_cast<int>(box_h) - m);
    return true;
}

bool FontManager::EnsureGraphemeGlyph(
    const std::string& utf8,
    uint32_t primary_cp,
    uint16_t style_flags,
    uint8_t span_cols,
    GlyphAtlasManager* atlas,
    GlyphAtlasEntry* out_entry) {
    if (atlas == nullptr || out_entry == nullptr) {
        return false;
    }
    if (primary_cp == 0x20u && utf8.size() <= 1u) {
        *out_entry = GlyphAtlasEntry{};
        out_entry->span_cols = span_cols;
        return true;
    }

    bool is_color = IsEmojiCodepoint(primary_cp, utf8);
    GlyphKey key;
    key.cluster_hash = Fnv1a64(utf8.data(), utf8.size());
    key.codepoint = primary_cp;
    key.font_size_px = static_cast<uint16_t>(metrics_.cell_height_px);
    key.style_bits = static_cast<uint16_t>(style_flags & (kStyleBold | kStyleItalic));
    key.span_cols = span_cols;
    key.is_color = is_color ? 1u : 0u;

    if (const GlyphAtlasEntry* cached = atlas->Find(key)) {
        *out_entry = *cached;
        return true;
    }

    bool use_native_box = false;
#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    if (IsBoxOrBlockOrBraille(primary_cp) && config_.cell_height_multiplier <= 1.03f) {
        FT_Face box_face = (impl_->tier1_custom != nullptr) ? impl_->tier1_custom : impl_->tier2_regular;
        if (box_face != nullptr && FT_Get_Char_Index(box_face, primary_cp) != 0) {
            use_native_box = true;
        }
    }
#endif

    if (IsBoxOrBlockOrBraille(primary_cp) && !use_native_box) {
        std::vector<uint8_t> r8;
        if (RasterizeProceduralBoxBlockBraille(primary_cp, metrics_.cell_width_px, metrics_.cell_height_px, &r8)) {
            return atlas->InsertMonoR8(
                key,
                static_cast<uint16_t>(metrics_.cell_width_px),
                static_cast<uint16_t>(metrics_.cell_height_px),
                0,
                static_cast<int16_t>(metrics_.baseline_px),
                span_cols,
                r8.data(),
                out_entry);
        }
    }

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
    const bool want_bold = (style_flags & kStyleBold) != 0;
    const bool want_italic = (style_flags & kStyleItalic) != 0;
    bool face_has_bold = false;
    bool face_has_italic = false;

    auto select_primary_face = [&]() -> FT_Face {
        if (impl_->tier1_custom != nullptr) {
            return impl_->tier1_custom;
        }
        if (want_bold && want_italic && impl_->tier2_bold_italic != nullptr) {
            face_has_bold = true;
            face_has_italic = true;
            return impl_->tier2_bold_italic;
        }
        if (want_bold && impl_->tier2_bold != nullptr) {
            face_has_bold = true;
            return impl_->tier2_bold;
        }
        if (want_italic && impl_->tier2_italic != nullptr) {
            face_has_italic = true;
            return impl_->tier2_italic;
        }
        return impl_->tier2_regular;
    };

    FT_Face chosen_face = nullptr;
    FT_UInt glyph_index = 0;

    if (is_color && impl_->tier5_emoji != nullptr) {
        glyph_index = FT_Get_Char_Index(impl_->tier5_emoji, primary_cp);
        if (glyph_index != 0) {
            chosen_face = impl_->tier5_emoji;
        }
    }
    if (chosen_face == nullptr && config_.nerd_font_symbols_enabled && IsNerdFontPua(primary_cp) &&
        impl_->tier3_nerd != nullptr) {
        glyph_index = FT_Get_Char_Index(impl_->tier3_nerd, primary_cp);
        if (glyph_index != 0) {
            chosen_face = impl_->tier3_nerd;
        }
    }
    if (chosen_face == nullptr) {
        FT_Face f = select_primary_face();
        if (f != nullptr) {
            glyph_index = FT_Get_Char_Index(f, primary_cp);
            if (glyph_index != 0) {
                chosen_face = f;
            }
        }
        if (chosen_face == nullptr) {
            face_has_bold = false;
            face_has_italic = false;
        }
    }
    if (chosen_face == nullptr && impl_->tier3_nerd != nullptr && config_.nerd_font_symbols_enabled) {
        glyph_index = FT_Get_Char_Index(impl_->tier3_nerd, primary_cp);
        if (glyph_index != 0) {
            chosen_face = impl_->tier3_nerd;
        }
    }
    if (chosen_face == nullptr) {
        for (FT_Face cjk : impl_->tier4_cjk) {
            glyph_index = FT_Get_Char_Index(cjk, primary_cp);
            if (glyph_index != 0) {
                chosen_face = cjk;
                break;
            }
        }
    }

    if (chosen_face != nullptr && glyph_index != 0) {
        const bool synth_bold = !is_color && !use_native_box && want_bold && !face_has_bold;
        const bool synth_italic = !is_color && !use_native_box && want_italic && !face_has_italic;

        FT_Error load_err = 0;
        if (is_color) {
            load_err = FT_Load_Glyph(chosen_face, glyph_index, FT_LOAD_RENDER | FT_LOAD_COLOR);
        } else if (synth_bold || synth_italic) {
            load_err = FT_Load_Glyph(chosen_face, glyph_index, FT_LOAD_DEFAULT);
            if (load_err == 0) {
                if (synth_bold) {
                    FT_GlyphSlot_Embolden(chosen_face->glyph);
                }
                if (synth_italic) {
                    FT_GlyphSlot_Oblique(chosen_face->glyph);
                }
                load_err = FT_Render_Glyph(chosen_face->glyph, FT_RENDER_MODE_NORMAL);
            }
        } else {
            load_err = FT_Load_Glyph(chosen_face, glyph_index, FT_LOAD_RENDER | FT_LOAD_TARGET_NORMAL);
        }

        if (load_err == 0) {
            const FT_Bitmap& bmp = chosen_face->glyph->bitmap;
            if (bmp.width == 0 || bmp.rows == 0) {
                *out_entry = GlyphAtlasEntry{};
                out_entry->span_cols = span_cols;
                return true;
            }

            const uint32_t max_box_w = metrics_.cell_width_px * std::max<uint8_t>(1, span_cols);
            int16_t bx = static_cast<int16_t>(chosen_face->glyph->bitmap_left);
            int16_t by = static_cast<int16_t>(chosen_face->glyph->bitmap_top);

            if (bmp.pixel_mode == FT_PIXEL_MODE_BGRA) {
                std::vector<uint8_t> rgba(static_cast<size_t>(bmp.width) * bmp.rows * 4u);
                for (unsigned int y = 0; y < bmp.rows; ++y) {
                    const uint8_t* src = bmp.buffer + static_cast<size_t>(y) * std::abs(bmp.pitch);
                    for (unsigned int x = 0; x < bmp.width; ++x) {
                        size_t dst_i = (static_cast<size_t>(y) * bmp.width + x) * 4u;
                        rgba[dst_i + 0] = src[x * 4u + 2];
                        rgba[dst_i + 1] = src[x * 4u + 1];
                        rgba[dst_i + 2] = src[x * 4u + 0];
                        rgba[dst_i + 3] = src[x * 4u + 3];
                    }
                }
                uint32_t out_w = bmp.width;
                uint32_t out_h = bmp.rows;
                if (out_w > max_box_w || out_h > metrics_.cell_height_px) {
                    float scale = std::min(
                        static_cast<float>(max_box_w) / static_cast<float>(out_w),
                        static_cast<float>(metrics_.cell_height_px) / static_cast<float>(out_h));
                    uint32_t dst_w = std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(static_cast<float>(out_w) * scale)));
                    uint32_t dst_h = std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(static_cast<float>(out_h) * scale)));
                    std::vector<uint8_t> scaled;
                    DownsamplePixels(rgba.data(), out_w, out_h, dst_w, dst_h, 4u, &scaled);
                    rgba = std::move(scaled);
                    out_w = dst_w;
                    out_h = dst_h;
                    bx = static_cast<int16_t>((max_box_w > out_w) ? ((max_box_w - out_w) / 2u) : 0u);
                    by = static_cast<int16_t>(metrics_.baseline_px -
                        static_cast<int32_t>((metrics_.cell_height_px > out_h) ? ((metrics_.cell_height_px - out_h) / 2u) : 0u));
                }
                return atlas->InsertColorRgba8(
                    key,
                    static_cast<uint16_t>(out_w),
                    static_cast<uint16_t>(out_h),
                    bx,
                    by,
                    span_cols,
                    rgba.data(),
                    out_entry);
            }
            if (bmp.pixel_mode == FT_PIXEL_MODE_GRAY) {
                std::vector<uint8_t> r8(static_cast<size_t>(bmp.width) * bmp.rows);
                for (unsigned int y = 0; y < bmp.rows; ++y) {
                    std::memcpy(
                        r8.data() + static_cast<size_t>(y) * bmp.width,
                        bmp.buffer + static_cast<size_t>(y) * std::abs(bmp.pitch),
                        bmp.width);
                }
                uint32_t out_w = bmp.width;
                uint32_t out_h = bmp.rows;
                if (!use_native_box && out_w > max_box_w &&
                    (span_cols >= 2 || IsCjkOrHangulWide(primary_cp) || IsNerdFontPua(primary_cp))) {
                    float scale = static_cast<float>(max_box_w) / static_cast<float>(out_w);
                    uint32_t dst_w = max_box_w;
                    uint32_t dst_h = std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(static_cast<float>(out_h) * scale)));
                    std::vector<uint8_t> scaled;
                    DownsamplePixels(r8.data(), out_w, out_h, dst_w, dst_h, 1u, &scaled);
                    r8 = std::move(scaled);
                    out_w = dst_w;
                    out_h = dst_h;
                    bx = 0;
                    by = static_cast<int16_t>(std::lround(static_cast<float>(by) * scale));
                } else if (!use_native_box && (span_cols >= 2 || IsCjkOrHangulWide(primary_cp) || IsNerdFontPua(primary_cp))) {
                    if (bx < 0 && out_w <= max_box_w) {
                        bx = 0;
                    }
                    if (bx + static_cast<int32_t>(out_w) > static_cast<int32_t>(max_box_w) && out_w <= max_box_w) {
                        bx = static_cast<int16_t>(std::max<int32_t>(0, static_cast<int32_t>(max_box_w) - static_cast<int32_t>(out_w)));
                    }
                }
                return atlas->InsertMonoR8(
                    key,
                    static_cast<uint16_t>(out_w),
                    static_cast<uint16_t>(out_h),
                    bx,
                    by,
                    span_cols,
                    r8.data(),
                    out_entry);
            }
        }
    }
#endif

    std::vector<uint8_t> pixels;
    bool out_color = false;
    uint16_t gw = 0, gh = 0;
    int16_t bx = 0, by = 0;
    if (!RasterizeFallbackGlyph(
            utf8, primary_cp, style_flags, span_cols,
            metrics_.cell_width_px, metrics_.cell_height_px, metrics_.baseline_px,
            &pixels, &out_color, &gw, &gh, &bx, &by)) {
        return false;
    }
    if (out_color) {
        return atlas->InsertColorRgba8(key, gw, gh, bx, by, span_cols, pixels.data(), out_entry);
    }
    return atlas->InsertMonoR8(key, gw, gh, bx, by, span_cols, pixels.data(), out_entry);
}

void FontManager::ShapeRow(
    uint16_t row_index,
    const std::vector<ExtractedCell>& cells,
    int32_t cursor_col_if_on_row,
    CursorShape cursor_shape,
    uint32_t cursor_rgba,
    uint32_t selection_rgba,
    GlyphAtlasManager* atlas,
    std::vector<CellGpuInstance>* out_instances) {
    if (out_instances == nullptr || atlas == nullptr) {
        return;
    }
    out_instances->clear();
    out_instances->reserve(cells.size());

    for (size_t col = 0; col < cells.size();) {
        const ExtractedCell& cell = cells[col];
        if (cell.is_continuation) {
            ++col;
            continue;
        }

        bool cell_selected = (cell.style_flags & kStyleSelected) != 0;
        bool cursor_here = (cursor_col_if_on_row == static_cast<int32_t>(col)) &&
                           (cursor_shape != CursorShape::kNone);

        size_t lig_len = 1;
        if (config_.ligatures_enabled && !cell_selected && !cursor_here && cell.width == 1) {
            size_t candidate = 1;
            if (MatchLigatureOperator(cells, col, &candidate)) {
                bool can_merge = true;
                for (size_t k = 1; k < candidate; ++k) {
                    const ExtractedCell& next = cells[col + k];
                    if ((next.style_flags & kStyleSelected) != 0 ||
                        cursor_col_if_on_row == static_cast<int32_t>(col + k) ||
                        next.fg_rgba != cell.fg_rgba ||
                        next.bg_rgba != cell.bg_rgba ||
                        (next.style_flags & (kStyleBold | kStyleItalic)) !=
                            (cell.style_flags & (kStyleBold | kStyleItalic))) {
                        can_merge = false;
                        break;
                    }
                }
                if (can_merge) {
                    lig_len = candidate;
                }
            }
        }

        uint8_t span_cols = (lig_len > 1) ? static_cast<uint8_t>(lig_len) : std::max<uint8_t>(1, cell.width);
        std::string cluster_utf8;
        if (lig_len > 1) {
            for (size_t k = 0; k < lig_len; ++k) {
                cluster_utf8.append(cells[col + k].grapheme_utf8);
            }
        } else {
            cluster_utf8 = cell.grapheme_utf8;
        }

        GlyphAtlasEntry glyph_entry{};
        bool ok = EnsureGraphemeGlyph(
            cluster_utf8,
            cell.primary_codepoint,
            cell.style_flags,
            span_cols,
            atlas,
            &glyph_entry);

        uint16_t flags = cell.style_flags;
        uint32_t fg = cell.fg_rgba;
        uint32_t bg = cell.bg_rgba;
        uint32_t deco = cell.deco_rgba;

        if (flags & kStyleInverse) {
            std::swap(fg, bg);
        }
        if (cell_selected) {
            bg = selection_rgba;
        }
        if (cursor_here) {
            flags = static_cast<uint16_t>((flags & ~kStyleCursorMask) | PackCursorBits(cursor_shape));
            deco = cursor_rgba;
            if (cursor_shape == CursorShape::kBlock) {
                std::swap(fg, bg);
                bg = cursor_rgba;
            }
        }

        CellGpuInstance inst{};
        inst.grid_col = static_cast<uint16_t>(col);
        inst.grid_row = row_index;
        inst.span_cols = span_cols;
        inst.atlas_page = ok ? glyph_entry.atlas_page : kAtlasPageNone;
        inst.style_flags = flags;
        inst.uv_x = glyph_entry.uv_x;
        inst.uv_y = glyph_entry.uv_y;
        inst.uv_w = glyph_entry.uv_w;
        inst.uv_h = glyph_entry.uv_h;
        inst.bearing_x = glyph_entry.bearing_x;
        inst.bearing_y = glyph_entry.bearing_y;
        inst.fg_rgba = fg;
        inst.bg_rgba = bg;
        inst.deco_rgba = deco;

        out_instances->push_back(inst);
        col += std::max<size_t>(1u, span_cols);
    }
}

}  // namespace termvr::vt
