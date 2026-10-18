package cn.edu.whu.libseat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class WebUser(
    val name: String,
    val username: String,
    val violations: Int = 0,
)

/**
 * 新版座位系统（/jsq）的会话辅助接口。
 *
 * WebView 登录成功后，SPA 会把 token 写进 sessionStorage。
 * 这里用同一个 token 调 getUserInfo，既能校验会话，也能拿到姓名/学号，
 * 避免去猜 SPA 内部 userInfo 的字段结构。
 */
class JsqSessionClient(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchUser(baseUrl: String, token: String): WebUser? = withContext(Dispatchers.IO) {
        val url = jsqBase(baseUrl) + "/static/frontApi/user/getUserInfo"
        val request = Request.Builder()
            .url(url)
            .header("token", token)
            .header("Accept", "application/json")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(text).jsonObject
                if (!isSuccess(root["status"]?.let { it as? JsonPrimitive })) return@use null
                val data = root["data"] as? JsonObject ?: return@use null
                WebUser(
                    name = data.pick("fullName", "name", "realName", "readerName").orEmpty(),
                    username = data.pick("username", "userName", "account", "loginName", "readerNo").orEmpty(),
                    violations = data.pickInt("violationCount", "breachCount", "breachNum") ?: 0,
                )
            }
        }.getOrNull()
    }

    suspend fun logout(baseUrl: String, token: String) = withContext(Dispatchers.IO) {
        runCatching {
            val url = jsqBase(baseUrl) + "/static/frontApi/user/logout"
            val request = Request.Builder()
                .url(url)
                .header("token", token)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().close()
        }
    }

    private fun jsqBase(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/jsq")) base else "$base/jsq"
    }

    private fun isSuccess(status: JsonPrimitive?): Boolean {
        if (status == null) return false
        return status.booleanOrNull == true || status.contentOrNull == "success"
    }

    private fun JsonObject.pick(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun JsonObject.pickInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        }
}
