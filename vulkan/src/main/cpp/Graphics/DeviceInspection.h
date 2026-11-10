#ifndef HAISHINKIT_DEVICE_INSPECTION_H
#define HAISHINKIT_DEVICE_INSPECTION_H

#include <cstdint>
#include <string>
#include <vector>

namespace Graphics {
struct DeviceInspection {
    std::string name;
    std::string type;
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorId;
    uint32_t deviceId;
    std::vector<std::string> extensions;
};

inline std::string QuoteInspectionString(const std::string &value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string result = "\"";
    for (const unsigned char c : value) {
        if (c == '"' || c == '\\') {
            result += '\\';
            result += static_cast<char>(c);
        } else if (c < 0x20) {
            result += "\\u00";
            result += hex[c >> 4];
            result += hex[c & 15];
        } else {
            result += static_cast<char>(c);
        }
    }
    return result + '"';
}

inline std::string SerializeDeviceInspection(const std::vector<DeviceInspection> &devices) {
    std::string result = "{\"devices\":[";
    const char *separator = "";
    for (const auto &device : devices) {
        result += separator;
        separator = ",";
        result += "{\"device_name\":" + QuoteInspectionString(device.name) +
                  ",\"device_type\":" + QuoteInspectionString(device.type) +
                  ",\"api_version\":" + std::to_string(device.apiVersion) +
                  ",\"driver_version\":" + std::to_string(device.driverVersion) +
                  ",\"vendor_id\":" + std::to_string(device.vendorId) +
                  ",\"device_id\":" + std::to_string(device.deviceId) + ",\"extensions\":[";
        const char *extensionSeparator = "";
        for (const auto &extension : device.extensions) {
            result += extensionSeparator;
            extensionSeparator = ",";
            result += QuoteInspectionString(extension);
        }
        result += "]}";
    }
    return result + "]}";
}
} // namespace Graphics
#endif
