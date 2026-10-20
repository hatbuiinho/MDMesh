package com.mdmesh.agent.web

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-private, bounded history. No browsing paths, query strings, or message content. */
internal object NetworkAccessStore {
    private var cached: LinkedHashMap<String, JSONObject>? = null
    private var lastSave = 0L
    private fun prefs(context: Context) = context.getSharedPreferences("network_access", Context.MODE_PRIVATE)
    private fun entries(context: Context): LinkedHashMap<String, JSONObject> {
        cached?.let { return it }
        val loaded = LinkedHashMap<String, JSONObject>()
        runCatching {
            val array = JSONArray(prefs(context).getString("events", "[]"))
            for (i in 0 until array.length()) { val row = array.getJSONObject(i); loaded[row.getString("key")] = row }
        }
        cached = loaded; return loaded
    }
    @Synchronized fun record(context: Context, hash: String, domain: String, pkg: String?, rules: List<String>) {
        val now = System.currentTimeMillis()
        val key = "$hash|${pkg.orEmpty()}|$domain"
        val rows = entries(context)
        val row = rows.remove(key) ?: JSONObject().put("key", key).put("policyHash", hash).put("domain", domain).put("packageName", pkg.orEmpty()).put("firstAt", now).put("count", 0)
        row.put("lastAt", now).put("count", row.optLong("count") + 1).put("rules", JSONArray(rules))
        rows[key] = row
        while (rows.size > 300) rows.remove(rows.keys.first())
        if (now - lastSave > 5000) { save(context); lastSave = now }
    }
    @Synchronized private fun save(context: Context) { prefs(context).edit().putString("events", JSONArray(entries(context).values.toList()).toString()).apply() }
    @Synchronized fun history(context: Context): List<JSONObject> = entries(context).values.toList().reversed().map { JSONObject(it.toString()) }
    @Synchronized fun enqueue(context: Context, event: JSONObject, reason: String) {
        val outbox = JSONArray(prefs(context).getString("outbox", "[]"))
        val key = event.getString("key")
        if ((0 until outbox.length()).any { outbox.getJSONObject(it).getString("key") == key }) return
        require(outbox.length() < 30) { "Có quá nhiều yêu cầu chưa gửi." }
        outbox.put(JSONObject().put("key", key).put("packageName", event.getString("packageName")).put("domain", event.getString("domain")).put("policyHash", event.getString("policyHash")).put("reason", reason))
        // Commit the user action before scheduling work; it must survive process death.
        check(prefs(context).edit().putString("outbox", outbox.toString()).commit()) { "Không lưu được yêu cầu." }
    }
    @Synchronized fun outbox(context: Context): List<JSONObject> {
        val array = JSONArray(prefs(context).getString("outbox", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Synchronized fun sent(context: Context, key: String) {
        val remaining = outbox(context).filter { it.getString("key") != key }
        check(prefs(context).edit().putString("outbox", JSONArray(remaining).toString()).commit())
    }
    @Synchronized fun recordFailure(context: Context, row: JSONObject) {
        val rows = JSONArray(prefs(context).getString("failures", "[]")); rows.put(row)
        val bounded = JSONArray((maxOf(0, rows.length()-30) until rows.length()).map { rows.getJSONObject(it) })
        prefs(context).edit().putString("failures", bounded.toString()).apply()
    }
    @Synchronized fun failures(context: Context): List<JSONObject> {
        val rows = JSONArray(prefs(context).getString("failures", "[]")); return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    @Synchronized fun requests(context: Context): List<JSONObject> {
        val array = JSONArray(prefs(context).getString("requests", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Synchronized fun requests(context: Context, value: JSONArray) { prefs(context).edit().putString("requests", value.toString()).apply() }
}
