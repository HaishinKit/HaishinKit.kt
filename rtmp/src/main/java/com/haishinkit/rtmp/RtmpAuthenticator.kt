package com.haishinkit.rtmp

import android.net.Uri
import android.util.Base64
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.rtmp.util.Md5Util
import java.net.URI
import androidx.core.net.toUri

internal class RtmpAuthenticator(
    val connection: RtmpConnection,
) : IEventListener {
    data class Info(
        val user: String,
        val password: String,
        val salt: String,
        val challenge: String?,
        val opaque: String?,
    ) {
        override fun toString(): String {
            var result = ""
            var response = Md5Util.base64("$user$salt$password", Base64.NO_WRAP)
            if (opaque == null) {
                challenge?.let {
                    response += it
                }
            } else {
                result += "&opaque=${Uri.encode(opaque)}"
                response += opaque
            }
            val clientChallenge = String.format("%08x", (0 until Int.MAX_VALUE).random())
            response = Md5Util.base64("$response$clientChallenge", Base64.NO_WRAP)
            result += "&challenge=$clientChallenge&response=$response"
            return result
        }
    }

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        when (data["code"].toString()) {
            RtmpConnection.Code.CONNECT_REJECTED.rawValue -> {
                val description = data["description"] as? String ?: return
                when (true) {
                    description.contains("reason=needauth") -> {
                        val uri = connection.uri ?: return
                        if (uri.userInfo == null) return
                        connection.close()
                        connection.connect(createAuthCommand(uri, description))
                    }

                    description.contains("authmod=adobe") -> {
                        val uri = connection.uri ?: return
                        if (uri.userInfo == null) return
                        connection.close()
                        connection.connect(createAuthQuery(uri))
                    }

                    else -> {
                        connection.close()
                    }
                }
            }
        }
    }

    private fun createAuthQuery(uri: URI): String {
        val query = uri.query ?: ""
        val user = uri.userInfo.split(":")[0]
        return "$uri" +
            if (query.isEmpty()) {
                "?"
            } else {
                "&"
            } + "authmod=adobe&user=$user"
    }

    private fun createAuthCommand(
        uri: URI,
        description: String,
    ): String {
        if (!description.contains("?")) return uri.toString()
        if (uri.rawUserInfo == null) return uri.toString()
        val command = createAuthQuery(uri)
        val query = description.split("?")[1]
        val descriptionUri = "https://localhost?$query".toUri()
        val info =
            Info(
                user = uri.userInfo.split(":")[0],
                password = uri.userInfo.split(":")[1],
                salt = descriptionUri.getQueryParameter("salt") ?: "",
                challenge = descriptionUri.getQueryParameter("challenge"),
                opaque = descriptionUri.getQueryParameter("opaque"),
            )
        return "$command$info"
    }

    companion object {
        private val TAG = RtmpAuthenticator::class.java.simpleName
    }
}
