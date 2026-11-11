# Both consumers must link the same bundled TLS build, never host libraries.
set(MbedTLS_FOUND TRUE)
set(MbedTLS_VERSION "3.6.5")
set(MBEDTLS_INCLUDE_DIRS "${mbedtls_SOURCE_DIR}/include")
set(MBEDTLS_LIBRARIES mbedtls mbedx509 mbedcrypto)
