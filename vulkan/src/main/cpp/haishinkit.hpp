#ifndef HAISHINKIT_KT_HAISHINKIT_HPP
#define HAISHINKIT_KT_HAISHINKIT_HPP

#include "jni.h"
#include <exception>
#include <string>
#include <android/log.h>

#define LOGE(x...) do { \
  char buf[1024]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_ERROR, "HaishinKit", "%s | %s:%i", buf, __FILE__, __LINE__); \
} while (0)

#define LOGW(x...) do { \
  char buf[1024]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_WARN, "HaishinKit", "%s | %s:%i", buf, __FILE__, __LINE__); \
} while (0)

#define LOGI(x...) do { \
  char buf[1024]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_INFO, "HaishinKit", "%s | %s:%i", buf, __FILE__, __LINE__); \
} while (0)

#define LOGV(x...) do { \
  char buf[1024]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_VERBOSE, "HaishinKit", "%s | %s:%i", buf, __FILE__, __LINE__); \
} while (0)

#endif //HAISHINKIT_KT_HAISHINKIT_HPP
