#include "Unicode.h"
#include <iostream>
#include <vector>

int main() {
    using namespace haishinkit;
    const std::vector<std::pair<std::string, std::u16string>> cases = {
        {"", u""}, {std::string("a\0b", 3), std::u16string(u"a\0b", 3)},
        {u8"日本語 😀", u"日本語 😀"},
        {u8"\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff\U00010000\U0010ffff",
         u"\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff\U00010000\U0010ffff"}
    };
    for (const auto &entry : cases)
        if (fromUtf8(entry.first) != entry.second || toUtf8(entry.second) != entry.first) return 1;
    for (const std::string input : {"\x80", "\xc0\x80", "\xc2", "\xe0\x80\x80", "\xed\xa0\x80", "\xf0\x80\x80\x80", "\xf4\x90\x80\x80", "\xc2x"}) {
        try { fromUtf8(input); return 2; } catch (const std::range_error &) {}
    }
    for (const auto &input : {std::u16string(1, 0xd800), std::u16string(1, 0xdc00), std::u16string{0xd800, u'x'}}) {
        try { toUtf8(input); return 3; } catch (const std::range_error &) {}
    }
    std::cout << "Unicode conversion passed\n";
}
