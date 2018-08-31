package org.nypl.audiobook.android.tests.sandbox

import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.nypl.audiobook.android.api.PlayerDownloadProviderType
import org.nypl.audiobook.android.api.PlayerDownloadRequest
import org.nypl.audiobook.android.api.PlayerDownloadTaskType
import org.nypl.audiobook.android.api.PlayerSpineElementDownloadStatus.PlayerSpineElementDownloadFailed
import org.nypl.audiobook.android.api.PlayerSpineElementDownloadStatus.PlayerSpineElementDownloaded
import org.nypl.audiobook.android.api.PlayerSpineElementDownloadStatus.PlayerSpineElementDownloading
import org.nypl.audiobook.android.api.PlayerSpineElementDownloadStatus.PlayerSpineElementNotDownloaded
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService

/**
 * A fake download task.
 */

class NullDownloadTask(
  private val downloadStatusExecutor: ExecutorService,
  private val downloadProvider: PlayerDownloadProviderType,
  private val spineElement: NullSpineElement)
  : PlayerDownloadTaskType {

  private val log = LoggerFactory.getLogger(NullDownloadTask::class.java)

  private var percent: Int = 0
  private val stateLock: Any = Object()
  private var state: State = State.Initial

  init {
    this.onBroadcastState()
  }

  private sealed class State {
    object Initial : State()
    object Downloaded : State()
    data class Downloading(val future: ListenableFuture<Unit>) : State()
  }

  override fun fetch() {
    this.log.debug("fetch")

    when (this.stateGetCurrent()) {
      State.Initial -> onStartDownload()
      State.Downloaded -> onDownloaded()
      is State.Downloading -> onDownloading(this.percent)
    }
  }

  private fun stateGetCurrent() =
    synchronized(stateLock) { state }

  private fun stateSetCurrent(new_state: State) =
    synchronized(stateLock) { this.state = new_state }

  private fun onBroadcastState() {
    when (this.stateGetCurrent()) {
      State.Initial -> onNotDownloaded()
      State.Downloaded -> onDownloaded()
      is State.Downloading -> onDownloading(this.percent)
    }
  }

  private fun onNotDownloaded() {
    this.log.debug("not downloaded")
    this.spineElement.setDownloadStatus(PlayerSpineElementNotDownloaded(this.spineElement))
  }

  private fun onDownloading(percent: Int) {
    this.percent = percent
    this.spineElement.setDownloadStatus(PlayerSpineElementDownloading(this.spineElement, percent))
  }

  private fun onDownloaded() {
    this.log.debug("downloaded")
    this.spineElement.setDownloadStatus(PlayerSpineElementDownloaded(this.spineElement))
  }

  private fun onStartDownload(): ListenableFuture<Unit> {
    this.log.debug("starting download")

    val future = this.downloadProvider.download(PlayerDownloadRequest(
      uri = URI.create("urn:" + spineElement.index),
      credentials = null,
      outputFile = File("/"),
      onProgress = { percent -> this.onDownloading(percent) }))

    this.stateSetCurrent(State.Downloading(future))
    this.onBroadcastState()

    /*
     * Add a callback to the future that will report download success and failure.
     */

    Futures.addCallback(future, object : FutureCallback<Unit> {
      override fun onSuccess(result: Unit?) {
        this@NullDownloadTask.onDownloadCompleted()
      }

      override fun onFailure(exception: Throwable?) {
        when (exception) {
          is CancellationException ->
            this@NullDownloadTask.onDownloadCancelled()
          else ->
            this@NullDownloadTask.onDownloadFailed(kotlin.Exception(exception))
        }
      }
    }, this.downloadStatusExecutor)

    return future
  }

  private fun onDownloadCancelled() {
    this.log.error("onDownloadCancelled")
    this.stateSetCurrent(State.Initial)
    this.onDeleteDownloaded()
  }

  private fun onDownloadFailed(e: Exception) {
    this.log.error("onDownloadFailed: ", e)
    this.stateSetCurrent(State.Initial)
    this.spineElement.setDownloadStatus(
      PlayerSpineElementDownloadFailed(
        this.spineElement, e, e.message ?: "Missing exception message"))
  }

  private fun onDownloadCompleted() {
    this.log.debug("onDownloadCompleted")
    this.stateSetCurrent(State.Downloaded)
    this.onBroadcastState()
  }

  override fun delete() {
    this.log.debug("delete")

    val current = stateGetCurrent()
    when (current) {
      State.Initial -> this.onBroadcastState()
      State.Downloaded -> onDeleteDownloaded()
      is State.Downloading -> onDeleteDownloading(current)
    }
  }

  private fun onDeleteDownloading(state: State.Downloading) {
    this.log.debug("cancelling download in progress")

    state.future.cancel(true)
    this.stateSetCurrent(State.Initial)
    this.onDeleteDownloaded()
  }

  private fun onDeleteDownloaded() {
    this.stateSetCurrent(State.Initial)
    this.onBroadcastState()
  }

  override val progress: Double
    get() = this.percent.toDouble()
}