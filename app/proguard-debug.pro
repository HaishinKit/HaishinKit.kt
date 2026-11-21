# The debug-only MCP server uses plain HTTP and Android/JDK TLS.
# Netty probes these optional desktop TLS/logging providers before falling back;
# Ktor's IDE debugger probe is unavailable on Android. None are shipped by this app.
# Keep the exceptions scoped to exact optional types, not HaishinKit or JNI.
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.openssl.PEMDecryptorProvider
-dontwarn org.bouncycastle.openssl.PEMEncryptedKeyPair
-dontwarn org.bouncycastle.openssl.PEMKeyPair
-dontwarn org.bouncycastle.openssl.PEMParser
-dontwarn org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
-dontwarn org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
-dontwarn org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
-dontwarn org.conscrypt.BufferAllocator
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.HandshakeListener
# Ktor hands channel classes to Netty's ReflectiveChannelFactory, which invokes
# public no-argument constructors. Strict full mode does not retain them implicitly.
-keepclassmembers,allowoptimization class io.netty.channel.socket.nio.NioServerSocketChannel {
    public <init>();
}
-keepclassmembers,allowoptimization class io.netty.channel.socket.nio.NioSocketChannel {
    public <init>();
}
# Netty's leak detector validates these exclusion names using getDeclaredMethods.
-keepclassmembers class io.netty.buffer.AbstractByteBufAllocator {
    static *** toLeakAwareBuffer(...);
}
-keepclassmembers class io.netty.buffer.AdvancedLeakAwareByteBuf {
    *** touch(...);
    static *** recordLeakNonRefCountingOperation(...);
}
-keepclassmembers class io.netty.util.ReferenceCountUtil {
    public static *** touch(...);
}
