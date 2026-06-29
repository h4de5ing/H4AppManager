package com.github.appmanager.im

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object ChatSerializer {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun serializeMessage(msg: RoomMessage): String {
        return json.encodeToString(RoomMessage.serializer(), msg)
    }

    fun deserializeMessage(jsonStr: String): RoomMessage? {
        return try {
            json.decodeFromString(RoomMessage.serializer(), jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun deserializeMessageList(jsonStr: String): List<RoomMessage> {
        return try {
            json.decodeFromString(ListSerializer(RoomMessage.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deserializeUpload(jsonStr: String): UploadRequest? {
        return try {
            json.decodeFromString(UploadRequest.serializer(), jsonStr)
        } catch (e: Exception) {
            null
        }
    }
}
