package com.flip5.dockpilot

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shell through Shizuku. `cmd device_state` needs the shell uid on Samsung firmware, and
 * CONTROL_DEVICE_STATE is signature|privileged, so Shizuku is the only practical route.
 */
object Shell {

    data class Result(val ok: Boolean, val stdout: String, val stderr: String) {
        /** stdout or stderr, whichever carries the useful text. */
        val text: String get() = stdout.ifBlank { stderr }
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun isPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (t: Throwable) {
        true
    }

    /**
     * Runs one `sh -c` command as the shell user. Blocking; call it from an IO dispatcher.
     */
    fun exec(command: String, timeoutMs: Long = 8_000L): Result {
        if (!isBinderAlive()) {
            return Result(false, "", "Shizuku binder not available")
        }
        if (!hasPermission()) {
            return Result(false, "", "Shizuku permission not granted")
        }
        var process: Process? = null
        return try {
            process = newProcessCompat(command)
                ?: return Result(false, "", "cannot create shell process (Shizuku API mismatch)")
            // Drain stderr on a side thread so a chatty command cannot block stdout.
            val errBuffer = StringBuilder()
            val errReader = Thread {
                try {
                    errBuffer.append(process?.errorStream?.bufferedReader()?.readText().orEmpty())
                } catch (_: Throwable) {
                }
            }
            errReader.isDaemon = true
            errReader.start()

            val stdout = process.inputStream.bufferedReader().readText()
            // ShizukuRemoteProcess.waitFor(timeout) returns true without actually waiting on some
            // Shizuku builds, so wait on our own thread and keep the exit code optional.
            val finished = waitForCompat(process, timeoutMs)
            if (!finished) {
                process.destroy()
                return Result(false, stdout.trim(), "timeout after ${timeoutMs}ms: $command")
            }
            errReader.join(500)
            val stderr = errBuffer.toString().trim()
            val exitCode = try {
                process.exitValue()
            } catch (_: Throwable) {
                null
            }
            // A drained stdout with an unavailable exit code is treated as success unless the
            // command wrote to stderr.
            val ok = exitCode?.let { it == 0 } ?: stderr.isEmpty()
            Result(ok, stdout.trim(), stderr)
        } catch (t: Throwable) {
            Result(false, "", t.message ?: t.toString())
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun waitForCompat(process: Process, timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        val waiter = Thread {
            try {
                process.waitFor()
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        waiter.isDaemon = true
        waiter.start()
        return try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Shizuku 13.x hides `newProcess` (only system-service helpers stay public), but the binder
     * ABI is the same since 11. Reflectively calling it keeps arbitrary shell commands working
     * on both 12.x and 13.x clients without shipping a copy of Shizuku's internal AIDL.
     */
    private fun newProcessCompat(command: String): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (t: Throwable) {
            AppLog.error("Shizuku.newProcess 调用失败: ${t.message}")
            null
        }
    }
}
