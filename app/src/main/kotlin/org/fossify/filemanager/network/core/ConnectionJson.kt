package org.fossify.filemanager.network.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** (De)serialization of saved connections and their secrets. Unknown fields are ignored, broken entries skipped. */
object ConnectionJson {
    fun toJson(connection: NetworkConnection): JSONObject = JSONObject().apply {
        put("id", connection.id)
        put("name", connection.name)
        put("protocol", connection.protocol.id)
        put("host", connection.host)
        put("port", connection.port)
        put("username", connection.username)
        put("auth", connection.authMethod.id)
        put("domain", connection.domain)
        put("share", connection.share)
        put("initialPath", connection.initialPath)
        put("ftpPassive", connection.ftpPassive)
        put("encoding", connection.encoding)
        put("trusted", connection.trustedIdentity)
        put("createdAt", connection.createdAt)
    }

    fun fromJson(json: JSONObject): NetworkConnection? {
        val id = json.optString("id")
        val protocol = Protocol.fromId(json.optString("protocol"))
        val host = json.optString("host")
        if (id.isEmpty() || protocol == null || host.isEmpty()) return null

        return NetworkConnection(
            id = id,
            name = json.optString("name").ifEmpty { host },
            protocol = protocol,
            host = host,
            port = json.optInt("port", 0),
            username = json.optString("username"),
            authMethod = AuthMethod.fromId(json.optString("auth")) ?: AuthMethod.PASSWORD,
            domain = json.optString("domain"),
            share = json.optString("share"),
            initialPath = json.optString("initialPath"),
            ftpPassive = json.optBoolean("ftpPassive", true),
            encoding = json.optString("encoding").ifEmpty { NetworkConnection.DEFAULT_ENCODING },
            trustedIdentity = json.optString("trusted"),
            createdAt = json.optLong("createdAt", 0L),
        )
    }

    fun listToJson(connections: List<NetworkConnection>): String {
        val array = JSONArray()
        connections.forEach { array.put(toJson(it)) }
        return array.toString()
    }

    fun listFromJson(text: String?): List<NetworkConnection> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { fromJson(it) } }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun secretsToJson(secrets: ConnectionSecrets): String = JSONObject().apply {
        put("password", secrets.password)
        put("privateKey", secrets.privateKey)
        put("passphrase", secrets.passphrase)
    }.toString()

    fun secretsFromJson(text: String): ConnectionSecrets? = try {
        val json = JSONObject(text)
        ConnectionSecrets(json.optString("password"), json.optString("privateKey"), json.optString("passphrase"))
    } catch (e: JSONException) {
        null
    }
}
