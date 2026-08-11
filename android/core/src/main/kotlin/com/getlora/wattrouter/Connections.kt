// Connections.kt: which servers this person has connected, and what may be one.
//
// History
//   2026-08-10  A. Sigdel  Created with #596.
//
// Contents
//   Connection   One server, as somebody saved it.
//   refusing     Why a pair cannot be saved, or null.
//   Connections  The list, between launches.
//
// Credential.kt's shape, and its reason: the decisions are pure functions this
// module's JVM suite can reach, and the class around them only stores.
//
// Not the Keystore, which is where the provider key goes. A server address is
// not a secret and sealing it would say it was; what a server needs to prove
// who is calling is a header the caller holds, and a header is a secret that
// goes where the credential goes rather than in this list.
//
// The rule that is not obvious is https. A release build has no network
// security config, so it inherits the platform default and cannot send
// cleartext anywhere: `app/src/debug/res/xml/network_security_config.xml` says
// so about itself and permits exactly one address for the stub model server.
// A plain-http server is therefore not a server this application can reach once
// it ships, and accepting one here would store a connection that works for the
// person who built the APK and fails for everybody else. Refusing at the point
// of saving is the only place that failure has a sentence attached to it.

package com.getlora.wattrouter

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One server, as somebody saved it.
 *
 * @property label what they called it. It becomes part of every tool name the
 *   server offers, which is [McpServer]'s reason for taking one: a server that
 *   named itself would be choosing how it appears in the list somebody uses to
 *   decide whether to trust it.
 * @property endpoint where it listens.
 */
data class Connection(val label: String, val endpoint: String)

/**
 * Why this pair cannot be saved, in words to put under a field.
 *
 * # Arguments
 * * `label`: what somebody typed as a name.
 * * `endpoint`: what they typed as an address.
 * * `taken`: the labels already saved, WHERE the one being edited is absent.
 *
 * # Returns
 * The reason, or null when it may be saved. A reason rather than a boolean
 * because every one of these is a different thing to fix, and a field that goes
 * red without saying why is a field people retype unchanged.
 */
fun refusing(label: String, endpoint: String, taken: Set<String> = emptySet()): String? {
    val name = label.trim()
    val where = endpoint.trim()

    return when {
        name.isEmpty() -> "Give it a name. It goes in front of every tool it offers."

        // A label of punctuation survives `prefixed` as underscores, so the
        // model would be offered `mcp___lookup` and somebody reading a tool
        // list could not tell which server it came from.
        name.none { it.isLetterOrDigit() } ->
            "Use some letters or numbers. The name goes into every tool name."

        name in taken -> "There is already a server called that."

        where.isEmpty() -> "Give it an address, starting https://"

        // Http is refused rather than warned about, and this is the rule worth
        // reading twice. A release build cannot send cleartext at all, so a
        // plain-http server works for whoever built the APK and fails for
        // everybody else, at the first tool call, with a platform error.
        where.startsWith("http://") ->
            "Use https. A released build cannot reach a plain http address at all."

        !where.startsWith("https://") -> "The address should start with https://"

        // Enough of a URL to have a host. `https://` alone passes the check
        // above and is not an address.
        where.removePrefix("https://").substringBefore('/').isEmpty() ->
            "The address is missing its host."

        else -> null
    }
}

/** The servers this person has connected, between launches. */
class Connections(private val store: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    /**
     * Every connection, in the order they were added.
     *
     * # Returns
     * What was saved, or empty. Anything that will not parse is empty rather
     * than a throw: a preferences file somebody edited should leave the phone
     * working, which is the rule `modeFrom` follows for the same reason.
     */
    val all: List<Connection>
        get() = runCatching {
            Json.parseToJsonElement(store.getString(KEY, null) ?: "[]").jsonArray
                .mapNotNull { entry ->
                    val row = entry.jsonObject
                    val label = row["label"]?.jsonPrimitive?.contentOrNull
                    val endpoint = row["endpoint"]?.jsonPrimitive?.contentOrNull
                    if (label.isNullOrBlank() || endpoint.isNullOrBlank()) {
                        null
                    } else {
                        Connection(label, endpoint)
                    }
                }
        }.getOrDefault(emptyList())

    /**
     * Add one, or answer why not.
     *
     * # Returns
     * Null when it was saved, otherwise [refusing]'s reason. The check is here
     * rather than only at the field, so a caller cannot store a pair the screen
     * would have refused.
     */
    fun add(label: String, endpoint: String): String? {
        val why = refusing(label, endpoint, all.map { it.label }.toSet())
        if (why != null) return why

        write(all + Connection(label.trim(), endpoint.trim()))
        return null
    }

    /** Take one away. Unknown labels are ignored rather than reported. */
    fun forget(label: String) = write(all.filterNot { it.label == label })

    private fun write(connections: List<Connection>) {
        val json = buildJsonArray {
            connections.forEach { connection ->
                add(
                    buildJsonObject {
                        put("label", connection.label)
                        put("endpoint", connection.endpoint)
                    },
                )
            }
        }
        store.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val FILE = "connections"
        const val KEY = "servers"
    }
}
