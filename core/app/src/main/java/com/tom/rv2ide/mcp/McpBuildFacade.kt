/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.mcp

import android.content.Context
import android.content.Intent
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.services.builder.BuildInProgressException
import com.tom.rv2ide.services.builder.GradleBuildService
import com.tom.rv2ide.services.builder.GradleBuildServiceConnnection
import com.tom.rv2ide.services.builder.gradleDistributionParams
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Bridges the MCP build tools to the Gradle build service.
 *
 * The service is taken from the [Lookup] registry when the IDE already bound to it; otherwise this
 * facade binds the service itself (using the application context) so that MCP clients can start a
 * build without an open editor. Build output is read from the rolling buffer added to
 * [GradleBuildService], because the service keeps a single [EventListener] slot which belongs to
 * the editor.
 *
 * @author AndroidCodeStudio
 */
class McpBuildFacade(private val context: Context, private val onLog: (String) -> Unit = {}) {

  private val log = LoggerFactory.getLogger(McpBuildFacade::class.java)
  private val connectionLock = Any()
  private val buildCounter = AtomicLong(0)

  @Volatile private var connection: GradleBuildServiceConnnection? = null

  @Volatile
  var currentBuildId: String? = null
    private set

  @Volatile
  var currentProjectDir: File? = null
    private set

  /** Returns the build service if the IDE (or a previous call) already registered it. */
  fun serviceOrNull(): GradleBuildService? {
    return Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
  }

  /** Binds the build service if required and returns it. */
  fun ensureService(timeoutMs: Long = SERVICE_TIMEOUT_MS): GradleBuildService? {
    serviceOrNull()?.let { return it }

    synchronized(connectionLock) {
      serviceOrNull()?.let { return it }
      if (connection != null) {
        // Another thread is already binding; wait for it to publish the service.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          serviceOrNull()?.let { return it }
          Thread.sleep(50)
        }
        return serviceOrNull()
      }

      val latch = CountDownLatch(1)
      val serviceConnection = GradleBuildServiceConnnection()
      serviceConnection.onConnected = { service ->
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, service)
        latch.countDown()
      }
      connection = serviceConnection

      val intent = Intent(context, GradleBuildService::class.java)
      val bound =
          try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
          } catch (e: Exception) {
            log.warn("Unable to bind the Gradle build service", e)
            false
          }

      if (!bound) {
        connection = null
        return null
      }

      latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      return serviceOrNull()
    }
  }

  /** Releases the service binding created by this facade. */
  fun unbind() {
    synchronized(connectionLock) {
      val serviceConnection = connection ?: return
      connection = null
      runCatching { context.unbindService(serviceConnection) }
    }
  }

  /** Ensures that the tooling API server has been started. */
  fun ensureToolingServer(
      service: GradleBuildService,
      timeoutMs: Long = TOOLING_SERVER_TIMEOUT_MS,
  ): Boolean {
    if (service.isToolingServerStarted()) return true

    onLog("Starting the tooling API server...")
    val latch = CountDownLatch(1)
    runCatching { service.startToolingServer { latch.countDown() } }
        .onFailure { log.warn("Unable to start the tooling API server", it) }

    latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    return service.isToolingServerStarted()
  }

  /** Initializes the given project with the tooling API. */
  fun ensureProjectInitialized(
      service: GradleBuildService,
      projectDir: File,
      timeoutMs: Long = INITIALIZE_TIMEOUT_MS,
  ): FacadeResult {
    if (!projectDir.isDirectory) {
      return FacadeResult(false, "Project directory does not exist: ${projectDir.path}")
    }

    onLog("Initializing project ${projectDir.path} ...")
    val params = InitializeProjectParams(projectDir.absolutePath, gradleDistributionParams)
    return try {
      val result = service.initializeProject(params).get(timeoutMs, TimeUnit.MILLISECONDS)
      if (result.isSuccessful) {
        FacadeResult(true, "Project initialized")
      } else {
        FacadeResult(
            false,
            "Unable to initialize project '${projectDir.name}' (stage=initialize): " +
                "${result.failure ?: "unknown failure"}. Restart the Gradle tooling connection and retry.",
        )
      }
    } catch (e: Exception) {
      log.warn("Project initialization failed", e)
      FacadeResult(
          false,
          "Unable to initialize project '${projectDir.name}' (stage=initialize): " +
              "${e.message ?: e::class.java.simpleName}. Restart the Gradle tooling connection and retry.",
      )
    }
  }

  /**
   * Starts a build for the given project.
   *
   * @param queueIfBusy when `true`, waits for the running build to finish (up to [QUEUE_TIMEOUT_MS])
   *   instead of failing immediately.
   */
  fun startBuild(
      projectDir: File,
      tasks: List<String>,
      queueIfBusy: Boolean = false,
  ): FacadeResult {
    if (tasks.isEmpty()) {
      return FacadeResult(false, "No Gradle task was provided")
    }

    val service =
        ensureService()
            ?: return FacadeResult(
                false,
                "The Gradle build service is not available. Open a project once to start it.",
            )

    if (service.isBuildInProgress) {
      val active = activeBuildId() ?: "unknown"
      val origin = stateSnapshot()?.origin ?: "unknown"
      if (!queueIfBusy) {
        return FacadeResult(
            false,
            "A build is already running (buildId=$active, origin=$origin). Cancel it, wait for it " +
                "to finish, or retry with queueIfBusy=true.",
            buildId = active,
        )
      }

      if (!waitForBuildToFinish(service, QUEUE_TIMEOUT_MS)) {
        return FacadeResult(
            false,
            "The running build (buildId=$active) did not finish within " +
                "${QUEUE_TIMEOUT_MS / 1000}s. Try again later.",
            buildId = active,
        )
      }
    }

    // The tooling server and the project initialization are the two long blocking steps; they are
    // bounded so that a slow/unresponsive tooling connection turns into a business error instead of
    // a client side timeout with an SDK stack trace.
    if (!ensureToolingServer(service, SLOT_PHASE_TIMEOUT_MS)) {
      return FacadeResult(
          false,
          "The tooling API server did not start within ${SLOT_PHASE_TIMEOUT_MS / 1000}s. " +
              "Open a project in the IDE and try again.",
      )
    }

    val initialized = ensureProjectInitialized(service, projectDir, INITIALIZE_PHASE_TIMEOUT_MS)
    if (!initialized.ok) {
      return FacadeResult(false, initialized.message)
    }

    val buildId = "build-${buildCounter.incrementAndGet()}"
    currentBuildId = buildId
    currentProjectDir = projectDir
    // Let the service know which id/origin/path to attach to the build it is about to prepare.
    service.beginBuild(buildId, GradleBuildService.ORIGIN_USER, projectDir.absolutePath)

    onLog("Starting build $buildId: ${tasks.joinToString(" ")}")
    try {
      service.executeTasks(*tasks.toTypedArray())
    } catch (e: Exception) {
      // The slot was taken by somebody else in the meantime, or the tooling server went away.
      currentBuildId = null
      return FacadeResult(
          false,
          "Unable to start the build for '${projectDir.name}' (stage=execute): " +
              "${e.message ?: e::class.java.simpleName}." +
              if (e is BuildInProgressException) " Retry with queueIfBusy=true." else "",
      )
    }

    return FacadeResult(
        true,
        "Build started: ${tasks.joinToString(" ")}",
        buildId = buildId,
    )
  }

  /** Cancels the running build, or clears a stuck build record when [force] is requested. */
  fun cancelBuild(force: Boolean = false): FacadeResult {
    val service =
        serviceOrNull() ?: return FacadeResult(false, "The Gradle build service is not running")

    if (force) {
      val id = activeBuildId()
      val cleared = service.failStaleBuild("cancelled by client")
      return FacadeResult(
          true,
          if (cleared) "Build record cleared" else "No running build record to clear",
          buildId = id,
          enqueued = false,
      )
    }

    if (!service.isBuildInProgress) {
      return FacadeResult(false, "No build is currently running")
    }

    return try {
      val result = service.cancelCurrentBuild().get(CANCEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      val reason = result.failureReason?.message
      val message =
          if (result.wasEnqueued) {
            "Build cancellation enqueued"
          } else {
            "Build cancellation was not enqueued" + (reason?.let { ": $it" } ?: "")
          }
      FacadeResult(true, message, enqueued = result.wasEnqueued, reason = reason)
    } catch (e: Exception) {
      FacadeResult(false, "Unable to cancel the build: ${e.message ?: "unknown error"}")
    }
  }

  /** Returns the build state snapshot, if the service is available. */
  internal fun stateSnapshot(): GradleBuildService.BuildStateSnapshot? {
    return serviceOrNull()?.buildStateSnapshot()
  }

  /**
   * Id of the build which is running now, or of the last one which ran.
   *
   * Falls back to the id reported by the service so that IDE initiated sync builds are addressable
   * even though this facade did not start them.
   */
  fun activeBuildId(): String? = currentBuildId ?: stateSnapshot()?.id

  /** Whether the build service looks usable (bound, and the last build did not break the tooling). */
  fun isServiceUsable(): Boolean {
    val service = serviceOrNull() ?: return false
    return !service.hasFatalFailure
  }

  /** Waits until no build is in progress, returning `false` when [timeoutMs] elapses first. */
  private fun waitForBuildToFinish(service: GradleBuildService, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (!service.isBuildInProgress) {
        return true
      }
      Thread.sleep(QUEUE_POLL_INTERVAL_MS)
    }
    return !service.isBuildInProgress
  }

  /** Returns the last [tail] build log lines, if the service is available. */
  fun logSnapshot(tail: Int): List<String> {
    return serviceOrNull()?.buildLogSnapshot(tail) ?: emptyList()
  }

  /** Returns the last [tail] tooling progress events, if the service is available. */
  fun eventSnapshot(tail: Int): List<String> {
    return serviceOrNull()?.buildEventSnapshot(tail) ?: emptyList()
  }

  /** Finds the newest APK produced for the given project. */
  fun findApk(projectDir: File, variant: String): File? {
    val outputs = ArrayList<File>()
    collectApks(projectDir, variant, outputs, 0)
    return outputs.filter { it.isFile }.maxByOrNull { it.lastModified() }
  }

  private fun collectApks(dir: File, variant: String, output: MutableList<File>, depth: Int) {
    if (depth > 5) return
    val children = dir.listFiles() ?: return
    for (child in children) {
      if (child.isDirectory) {
        if (child.name == ".git" || child.name == ".gradle") continue
        collectApks(child, variant, output, depth + 1)
      } else if (child.name.endsWith(".apk", ignoreCase = true)) {
        if (variant.isBlank() || child.path.contains(variant, ignoreCase = true)) {
          output.add(child)
        }
      }
    }
  }

  /** Result of a facade operation. */
  data class FacadeResult(
      val ok: Boolean,
      val message: String,
      val buildId: String? = null,
      val enqueued: Boolean = false,
      val reason: String? = null,
  )

  companion object {

    private const val SERVICE_TIMEOUT_MS = 30_000L
    private const val TOOLING_SERVER_TIMEOUT_MS = 120_000L
    private const val INITIALIZE_TIMEOUT_MS = 180_000L
    private const val CANCEL_TIMEOUT_MS = 30_000L

    /** Maximum time [startBuild] waits for a running build when `queueIfBusy` is requested. */
    private const val QUEUE_TIMEOUT_MS = 120_000L

    /** Poll interval used while waiting for the running build to finish. */
    private const val QUEUE_POLL_INTERVAL_MS = 200L

    /** Upper bound for starting the tooling server inside `build_start`. */
    private const val SLOT_PHASE_TIMEOUT_MS = 5_000L

    /** Upper bound for initializing the project inside `build_start`. */
    private const val INITIALIZE_PHASE_TIMEOUT_MS = 10_000L
  }
}
