package com.example.studiomobile.github

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

class GithubApiException(val status: Int, message: String) : IOException(message)

class RepoInfo(val fullName: String, val defaultBranch: String, val isPrivate: Boolean)

class RepoItem(val owner: String, val name: String, val fullName: String, val isPrivate: Boolean)

class WorkflowInfo(val id: Long, val name: String, val path: String, val state: String)

class RunInfo(
    val id: Long,
    val number: Int,
    val status: String,
    val conclusion: String,
    val htmlUrl: String,
    val branch: String,
    val title: String,
    val name: String,
    val startedAt: Long,
    val updatedAt: Long
)

class StepInfo(val number: Int, val name: String, val status: String, val conclusion: String)

class JobInfo(val id: Long, val name: String, val status: String, val conclusion: String, val steps: List<StepInfo>)

class ArtifactInfo(val id: Long, val name: String, val size: Long, val expired: Boolean)

class GithubApi(private val token: String) {
    private fun connect(method: String, url: String, body: String?, authorized: Boolean, accept: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw GithubApiException(0, "Only HTTPS addresses are allowed.")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "StudioMobile")
        if (authorized) {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (method == "POST") {
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            if (bytes.isNotEmpty()) conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun readAll(stream: InputStream?): String =
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private fun failure(status: Int, text: String): GithubApiException {
        val detail = try {
            JSONObject(text).optString("message", "")
        } catch (e: Exception) {
            ""
        }
        val message = when (status) {
            401 -> "The token was rejected. It may be wrong or expired. Delete it and enter a new one."
            404 -> "Not found. Check the address, or the token may not have access to this repository."
            else -> if (detail.isNotEmpty()) detail else "GitHub answered with status $status."
        }
        return GithubApiException(status, message)
    }

    private fun call(method: String, path: String, body: String? = null, accept: String = JSON): String {
        val conn = connect(method, API + path, body, true, accept)
        try {
            val status = conn.responseCode
            val text = readAll(if (status >= 400) conn.errorStream else conn.inputStream)
            if (status >= 400) throw failure(status, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun redirected(path: String): HttpURLConnection {
        var conn = connect("GET", API + path, null, true, JSON)
        var hops = 0
        while (true) {
            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || !location.startsWith("https://") || hops >= 4) {
                    throw GithubApiException(status, "Blocked an unsafe redirect.")
                }
                conn = connect("GET", location, null, false, "*/*")
                hops++
                continue
            }
            if (status >= 400) {
                val text = readAll(conn.errorStream)
                conn.disconnect()
                throw failure(status, text)
            }
            return conn
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    private fun text(json: JSONObject, key: String): String = if (json.isNull(key)) "" else json.optString(key, "")

    private fun millis(value: String): Long = try {
        Instant.parse(value).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    private fun parseRun(json: JSONObject): RunInfo {
        val created = millis(text(json, "created_at"))
        val started = millis(text(json, "run_started_at"))
        return RunInfo(
            json.optLong("id"),
            json.optInt("run_number"),
            text(json, "status"),
            text(json, "conclusion"),
            text(json, "html_url"),
            text(json, "head_branch"),
            text(json, "display_title"),
            text(json, "name"),
            if (started > 0) started else created,
            millis(text(json, "updated_at"))
        )
    }

    fun user(): String = JSONObject(call("GET", "/user")).optString("login", "")

    fun repo(owner: String, repo: String): RepoInfo {
        val json = JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}"))
        return RepoInfo(json.optString("full_name", "$owner/$repo"), json.optString("default_branch", "main"), json.optBoolean("private"))
    }

    fun repositories(): List<RepoItem> {
        val out = ArrayList<RepoItem>()
        val seen = HashSet<String>()
        for (page in 1..10) {
            val array = JSONArray(call("GET", "/user/repos?per_page=100&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val fullName = item.optString("full_name")
                val ownerName = item.optJSONObject("owner")?.optString("login").orEmpty()
                val name = item.optString("name")
                if (fullName.isEmpty() || ownerName.isEmpty() || name.isEmpty()) continue
                if (seen.add(fullName.lowercase())) out.add(RepoItem(ownerName, name, fullName, item.optBoolean("private")))
            }
            if (array.length() < 100) break
        }
        return out
    }

    private fun names(owner: String, repo: String, kind: String): List<String> {
        val out = ArrayList<String>()
        for (page in 1..5) {
            val array = JSONArray(call("GET", "/repos/${encode(owner)}/${encode(repo)}/$kind?per_page=100&page=$page"))
            for (i in 0 until array.length()) out.add(array.getJSONObject(i).optString("name"))
            if (array.length() < 100) break
        }
        return out
    }

    fun branches(owner: String, repo: String): List<String> = names(owner, repo, "branches")

    fun tags(owner: String, repo: String): List<String> = names(owner, repo, "tags")

    fun workflows(owner: String, repo: String): List<WorkflowInfo> {
        val json = JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}/actions/workflows?per_page=100"))
        val array = json.optJSONArray("workflows") ?: return emptyList()
        val out = ArrayList<WorkflowInfo>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val state = text(item, "state")
            if (state == "deleted") continue
            out.add(WorkflowInfo(item.optLong("id"), text(item, "name"), text(item, "path"), state))
        }
        return out
    }

    fun workflowFile(owner: String, repo: String, path: String, ref: String): String =
        call("GET", "/repos/${encode(owner)}/${encode(repo)}/contents/${encodePath(path)}?ref=${encode(ref)}", null, RAW)

    private fun runs(owner: String, repo: String, workflowId: Long): List<RunInfo> {
        val json = JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}/actions/workflows/$workflowId/runs?event=workflow_dispatch&per_page=10"))
        val array = json.optJSONArray("workflow_runs") ?: return emptyList()
        val out = ArrayList<RunInfo>()
        for (i in 0 until array.length()) out.add(parseRun(array.getJSONObject(i)))
        return out
    }

    fun recentRunIds(owner: String, repo: String, workflowId: Long): Set<Long> =
        runs(owner, repo, workflowId).map { it.id }.toSet()

    fun newRun(owner: String, repo: String, workflowId: Long, known: Set<Long>): RunInfo? =
        runs(owner, repo, workflowId).filter { it.id !in known }.maxByOrNull { it.id }

    fun dispatch(owner: String, repo: String, workflowId: Long, ref: String, inputs: Map<String, String>) {
        val body = JSONObject()
        body.put("ref", ref)
        val values = JSONObject()
        for ((key, value) in inputs) values.put(key, value)
        body.put("inputs", values)
        call("POST", "/repos/${encode(owner)}/${encode(repo)}/actions/workflows/$workflowId/dispatches", body.toString())
    }

    fun run(owner: String, repo: String, runId: Long): RunInfo =
        parseRun(JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId")))

    fun jobs(owner: String, repo: String, runId: Long): List<JobInfo> {
        val json = JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/jobs?per_page=100"))
        val array = json.optJSONArray("jobs") ?: return emptyList()
        val out = ArrayList<JobInfo>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val steps = ArrayList<StepInfo>()
            val stepArray = item.optJSONArray("steps")
            if (stepArray != null) {
                for (j in 0 until stepArray.length()) {
                    val step = stepArray.getJSONObject(j)
                    steps.add(StepInfo(step.optInt("number"), text(step, "name"), text(step, "status"), text(step, "conclusion")))
                }
            }
            out.add(JobInfo(item.optLong("id"), text(item, "name"), text(item, "status"), text(item, "conclusion"), steps))
        }
        return out
    }

    fun jobLogTail(owner: String, repo: String, jobId: Long, maxLines: Int): String? {
        return try {
            val conn = redirected("/repos/${encode(owner)}/${encode(repo)}/actions/jobs/$jobId/logs")
            try {
                val tail = ArrayDeque<String>()
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val raw = reader.readLine() ?: break
                        val line = GithubLogText.line(raw) ?: continue
                        tail.addLast(line)
                        if (tail.size > maxLines) tail.removeFirst()
                    }
                }
                tail.joinToString("\n")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        }
    }

    fun saveJobLog(owner: String, repo: String, jobId: Long, target: File) {
        val conn = redirected("/repos/${encode(owner)}/${encode(repo)}/actions/jobs/$jobId/logs")
        try {
            target.parentFile?.mkdirs()
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    fun downloadRepoZip(
        owner: String,
        repo: String,
        ref: String,
        target: OutputStream,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val conn = redirected("/repos/${encode(owner)}/${encode(repo)}/zipball/${encodePath(ref)}")
        try {
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    if (cancelled()) throw IOException("Cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    done += count
                    if (done - reported >= 256 * 1024) {
                        reported = done
                        progress(done)
                    }
                }
                target.flush()
                progress(done)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun cancel(owner: String, repo: String, runId: Long) {
        call("POST", "/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/cancel")
    }

    fun artifacts(owner: String, repo: String, runId: Long): List<ArtifactInfo> {
        val json = JSONObject(call("GET", "/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/artifacts?per_page=100"))
        val array = json.optJSONArray("artifacts") ?: return emptyList()
        val out = ArrayList<ArtifactInfo>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            out.add(ArtifactInfo(item.optLong("id"), text(item, "name"), item.optLong("size_in_bytes"), item.optBoolean("expired")))
        }
        return out
    }

    fun downloadArtifact(
        owner: String,
        repo: String,
        artifactId: Long,
        target: File,
        progress: (Long, Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val conn = redirected("/repos/${encode(owner)}/${encode(repo)}/actions/artifacts/$artifactId/zip")
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        done += count
                        if (done - reported >= 256 * 1024) {
                            reported = done
                            progress(done, total)
                        }
                    }
                    progress(done, total)
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save the file.")
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.github.com"
        private const val JSON = "application/vnd.github+json"
        private const val RAW = "application/vnd.github.raw+json"
    }
}
