package io.github.michaaels.hop.mcp;

/** Tracks the native worker separately from the caller waiting for its result. */
final class HopExecutionLifecycle {
  private enum Worker {
    NONE,
    PENDING,
    RUNNING,
    FINISHED
  }

  private final Runnable onFinished;
  private Worker worker = Worker.NONE;
  private boolean cancelled;
  private boolean callerFinished;
  private boolean notified;

  HopExecutionLifecycle(Runnable onFinished) {
    this.onFinished = onFinished;
  }

  synchronized void checkCancellation() throws InterruptedException {
    if (cancelled || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Execution cancelled");
    }
  }

  synchronized void registerWorker() throws InterruptedException {
    checkCancellation();
    if (worker != Worker.NONE) throw new IllegalStateException("Native worker already registered");
    worker = Worker.PENDING;
  }

  boolean workerStarted() {
    boolean started;
    boolean notify;
    synchronized (this) {
      started = worker == Worker.PENDING && !cancelled;
      if (started) worker = Worker.RUNNING;
      else if (worker == Worker.PENDING) worker = Worker.FINISHED;
      notify = shouldNotify();
    }
    if (notify) onFinished.run();
    return started;
  }

  void workerFinished() {
    boolean notify;
    synchronized (this) {
      worker = Worker.FINISHED;
      notify = shouldNotify();
    }
    if (notify) onFinished.run();
  }

  void cancelBeforeWorkerStarts() {
    boolean notify;
    synchronized (this) {
      if (worker == Worker.PENDING) worker = Worker.FINISHED;
      notify = shouldNotify();
    }
    if (notify) onFinished.run();
  }

  synchronized void cancel() {
    cancelled = true;
  }

  synchronized boolean cancellationRequested() {
    return cancelled;
  }

  synchronized boolean nativeWorkerActive() {
    return worker == Worker.PENDING || worker == Worker.RUNNING;
  }

  void callerFinished() {
    boolean notify;
    synchronized (this) {
      callerFinished = true;
      notify = shouldNotify();
    }
    if (notify) onFinished.run();
  }

  // Called with the lifecycle lock; invoke callbacks outside it to avoid lock inversion with jobs.
  private boolean shouldNotify() {
    if (!notified && callerFinished && !nativeWorkerActive()) {
      notified = true;
      return true;
    }
    return false;
  }
}
