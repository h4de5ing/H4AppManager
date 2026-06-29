package com.github.appmanager.im

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object ChatSerializer {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun serializeSingle(obj: ChatMessage): String {
        return json.encodeToString(ChatMessage.serializer(), obj)
    }

    fun serializeMessageList(list: List<ChatMessage>): String {
        return json.encodeToString(ListSerializer(ChatMessage.serializer()), list)
    }

    fun serializeFileList(list: List<FileInfo>): String {
        return json.encodeToString(ListSerializer(FileInfo.serializer()), list)
    }

    fun serializeSessionList(list: List<ChatSession>): String {
        return json.encodeToString(ListSerializer(ChatSession.serializer()), list)
    }

    fun deserializeMessage(jsonStr: String): ChatMessage? {
        return try {
            json.decodeFromString(ChatMessage.serializer(), jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun deserializeMessageList(jsonStr: String): List<ChatMessage> {
        return try {
            json.decodeFromString(ListSerializer(ChatMessage.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deserializeFileList(jsonStr: String): List<FileInfo> {
        return try {
            json.decodeFromString(ListSerializer(FileInfo.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deserializeSessionList(jsonStr: String): List<ChatSession> {
        return try {
            json.decodeFromString(ListSerializer(ChatSession.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
