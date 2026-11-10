#include "../../main/cpp/Graphics/DeviceInspection.h"
#include <iostream>

int main() {
    using namespace Graphics;
    if (SerializeDeviceInspection({}) != "{\"devices\":[]}")
        return 1;
    if (SerializeDeviceInspection(
            {{"GPU", "cpu", UINT32_MAX, 0, 1, 2, {"VK_test"}}, {"second", "other", 3, 4, 5, 6, {}}}) !=
        R"({"devices":[{"device_name":"GPU","device_type":"cpu","api_version":4294967295,"driver_version":0,"vendor_id":1,"device_id":2,"extensions":["VK_test"]},{"device_name":"second","device_type":"other","api_version":3,"driver_version":4,"vendor_id":5,"device_id":6,"extensions":[]}]})")
        return 1;
    std::string controls;

    for (int c = 0; c < 32; ++c)
        controls += static_cast<char>(c);
    if (QuoteInspectionString("\"\\\n") != "\"\\\"\\\\\\u000a\"")
        return 1;
    const auto json = SerializeDeviceInspection({{"GPU \"test\" \\ 日本語" + controls,
                                                  "integrated_gpu",
                                                  UINT32_MAX,
                                                  0,
                                                  1,
                                                  2,
                                                  {"VK_test", "quote\"\\\t"}},
                                                 {"second", "other", 3, 4, 5, 6, {}}});
    // Emit the production serializer's result for validation with a standard JSON parser.
    std::cout << json << '\n';
}
