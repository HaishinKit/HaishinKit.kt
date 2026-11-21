#ifndef HAISHINKIT_UNICODE_H
#define HAISHINKIT_UNICODE_H
#include <cstdint>
#include <stdexcept>
#include <string>

namespace haishinkit {
inline std::u16string fromUtf8(const std::string &input) {
    std::u16string output;
    for (size_t i = 0; i < input.size();) {
        uint32_t cp = static_cast<unsigned char>(input[i++]);
        unsigned extra = 0;
        uint32_t minimum = 0;
        if (cp >= 0xc2 && cp <= 0xdf) { cp &= 0x1f; extra = 1; minimum = 0x80; }
        else if (cp >= 0xe0 && cp <= 0xef) { cp &= 0x0f; extra = 2; minimum = 0x800; }
        else if (cp >= 0xf0 && cp <= 0xf4) { cp &= 7; extra = 3; minimum = 0x10000; }
        else if (cp >= 0x80) throw std::range_error("Invalid UTF-8");
        if (extra > input.size() - i) throw std::range_error("Truncated UTF-8");
        while (extra--) {
            auto byte = static_cast<unsigned char>(input[i++]);
            if ((byte & 0xc0) != 0x80) throw std::range_error("Invalid UTF-8 continuation");
            cp = (cp << 6) | (byte & 0x3f);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff))
            throw std::range_error("Invalid Unicode scalar");
        if (cp <= 0xffff) output.push_back(static_cast<char16_t>(cp));
        else {
            cp -= 0x10000;
            output.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
            output.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
        }
    }
    return output;
}
inline std::string toUtf8(const std::u16string &input) {
    std::string output;
    for (size_t i = 0; i < input.size(); ++i) {
        uint32_t cp = input[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (++i == input.size() || input[i] < 0xdc00 || input[i] > 0xdfff)
                throw std::range_error("Invalid UTF-16 surrogate pair");
            cp = 0x10000 + ((cp - 0xd800) << 10) + (input[i] - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) throw std::range_error("Unpaired UTF-16 surrogate");
        if (cp < 0x80) output.push_back(static_cast<char>(cp));
        else {
            if (cp < 0x800) output.push_back(static_cast<char>(0xc0 | (cp >> 6)));
            else {
                if (cp < 0x10000) output.push_back(static_cast<char>(0xe0 | (cp >> 12)));
                else {
                    output.push_back(static_cast<char>(0xf0 | (cp >> 18)));
                    output.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
                }
                output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            }
            output.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        }
    }
    return output;
}
}
#endif
