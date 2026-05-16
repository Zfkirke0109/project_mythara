package com.mythara.agent.tools

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.LinuxBridgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `linux_vm` — execute a command inside the Android 15 experimental
 * Linux Terminal (Debian VM running via crosvm).
 *
 * ## How it works
 *
 * The Linux Terminal is sandboxed in its own VM, separate from the
 * host Android runtime. The only programmatic way to reach it is
 * over the network: the user opens the Terminal app once, installs
 * `openssh-server`, starts it, and configures Mythara with the SSH
 * host/port/credentials in Settings → Linux Bridge.
 *
 * This tool SSHes in using the JSch pure-Java SSH client (the
 * Android system image does NOT bundle an `ssh` binary on PATH, so
 * shelling out via ProcessBuilder fails with `No such file or
 * directory` — we MUST use a JVM SSH library).
 *
 * Auth modes (in priority order):
 *   1. **Private key (PEM)** — preferred. JSch handles RSA / DSA /
 *      ECDSA / Ed25519.
 *   2. **Password** — falls back to interactive-style password auth
 *      via UserInfo + setPassword. Works with stock OpenSSH server
 *      configurations (PasswordAuthentication yes).
 *
 * ## First-use UX
 *
 * When the user calls this tool with no SSH config saved, we return
 * a setup-guidance JSON instead of attempting the connection. The
 * agent should READ this response and surface the steps to the user
 * verbatim — do NOT hallucinate alternate setup paths.
 */
@Singleton
class LinuxVmBridgeTool @Inject constructor(
    private val bridge: LinuxBridgeStore,
) : Tool {
    override val name = "linux_vm"
    override val description =
        "Run a command inside the Android 15 Linux Terminal (Debian VM) via SSH. " +
            "Requires one-time setup (openssh-server in the VM + creds in Mythara Settings)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "Shell command to run inside the Debian VM. Quotes preserved.")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds before kill. Default 15000, max 120000.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("command"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.fail("command must be non-empty")
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 15_000L)
            .coerceIn(500L, 120_000L)

        val cfg = bridge.current()
        if (!cfg.isConfigured) {
            return ToolResult.ok(setupCard())
        }

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runCatching {
                        runSsh(cfg, command, timeoutMs.toInt())
                    }.getOrElse { t ->
                        ToolResult.fail(
                            "ssh_failed: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ToolResult.ok("""{"status":"timeout","timeout_ms":$timeoutMs}""")
            }
        }
    }

    private fun runSsh(
        cfg: LinuxBridgeStore.Config,
        command: String,
        timeoutMs: Int,
    ): ToolResult {
        val jsch = JSch()

        // Identity (private key) is preferred when set. JSch accepts
        // the key bytes directly via addIdentity(name, privKey, pub,
        // passphrase). We only support unencrypted PEM keys for now —
        // generating one in the VM with `ssh-keygen -N ""` is the
        // documented path.
        if (!cfg.privateKeyPem.isNullOrBlank()) {
            runCatching {
                jsch.addIdentity(
                    /* name = */ "mythara_linux_bridge",
                    /* prvkey = */ cfg.privateKeyPem.trim().toByteArray(Charsets.UTF_8),
                    /* pubkey = */ null,
                    /* passphrase = */ null,
                )
            }.onFailure {
                return ToolResult.fail("key_load_failed: ${it.message}")
            }
        }

        val session: Session = jsch.getSession(cfg.user, cfg.host, cfg.port)
        if (cfg.privateKeyPem.isNullOrBlank() && !cfg.password.isNullOrBlank()) {
            session.setPassword(cfg.password)
        }

        // Disable strict host-key checking — every connect is to the
        // user's own localhost VM, never a remote server. Saves us
        // from juggling a known_hosts file across reinstalls.
        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", if (!cfg.privateKeyPem.isNullOrBlank()) "publickey,password" else "password,publickey")
        }
        session.setConfig(props)
        session.connect(CONNECT_TIMEOUT_MS)

        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            channel.outputStream = ByteArrayOutputStream() // ignore stdin
            channel.setErrStream(errStream)
            channel.outputStream = outStream
            channel.connect(timeoutMs)

            // Wait for the command to finish. JSch's channel.isClosed
            // becomes true when the remote side signals EOF.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            val exit = if (channel.isClosed) channel.exitStatus else -1
            val merged = outStream.toString(Charsets.UTF_8) + errStream.toString(Charsets.UTF_8)
            val truncated = if (merged.length > MAX_OUT) merged.take(MAX_OUT) + "\n…[truncated]" else merged
            channel.disconnect()
            return ToolResult.ok(
                """{"status":"ok","exit":$exit,"out":${jsonString(truncated)}}""",
            )
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun setupCard(): String =
        """{"status":"not_configured","setup_steps":[
            "1. On Android 15, open Settings → Developer options → 'Linux development environment' and install the Debian VM.",
            "2. Open the new Terminal app from the launcher.",
            "3. Inside the VM, run: sudo apt update && sudo apt install -y openssh-server",
            "4. Start sshd: sudo service ssh start (or 'sudo systemctl enable --now ssh')",
            "5. (Recommended) Generate a key: ssh-keygen -t ed25519 -f ~/mythara_key -N '' && cat ~/mythara_key.pub >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && cat ~/mythara_key",
            "6. Open Mythara → Settings → 'linux bridge'. Set host=127.0.0.1, port=22, user=droid, paste the private key (BEGIN/END block) into the key field.",
            "7. Retry this command."
        ]}""".trimIndent()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    companion object {
        private const val MAX_OUT = 8_192
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val POLL_INTERVAL_MS = 100L
    }
}
