package cordis.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * cordis 的调度中枢，对应“JS 主线程 + libuv 线程池”的双层结构：
 *
 * <ul>
 *   <li><b>mainLoop</b>：唯一的调度线程（对应 JS 主线程 / Netty EventLoop）。
 *       所有生命周期状态迁移、事件派发、Promise 回调都在它上面串行执行，
 *       因此框架内部状态（fiber 状态机、hooks、serviceStore）无需任何锁；</li>
 *   <li><b>blockingPool</b>：扩展线程池（对应 libuv 线程池）。承接一切阻塞型
 *       工作（JDBC、HTTP、文件、重计算），结果经 {@link Promise} 投递回主循环。</li>
 * </ul>
 *
 * 铁律：主循环上只允许微秒级的调度决策，任何可能阻塞的操作必须
 * {@link #offload} 到扩展池。这与 Netty 的“不要阻塞 EventLoop”完全同义。
 */
public final class Scheduler {

    private final ScheduledThreadPoolExecutor mainLoop;

    /**
     * “当前线程是不是主循环线程”的标记。
     * 不能用 Thread 引用做判定：主循环线程一旦因异常被线程池替换，旧引用就永远
     * 失效（inMainLoop 永假 → awaitIdle 的自死锁保护、effect 断言全部失灵）。
     * ThreadLocal 随线程出生/消亡自动失效，替换后新线程会在首个任务里重新打标。
     */
    private final ThreadLocal<Boolean> onMainLoop = new ThreadLocal<>();

    /** 仅供诊断/日志展示的当前主循环线程（线程被替换时自动刷新） */
    private volatile Thread mainThread;

    private final ExecutorService blockingPool;

    public Scheduler() {
        this(Math.max(4, java.lang.Runtime.getRuntime().availableProcessors()));
    }

    public Scheduler(int blockingPoolSize) {
        mainLoop = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "cordis-main-loop");
            t.setDaemon(true);
            return t;
        });
        blockingPool = Executors.newFixedThreadPool(blockingPoolSize, r -> {
            Thread t = new Thread(r, "cordis-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 把任务投递到主循环。已在主循环线程则内联执行（对应 Netty 的
     * inEventLoop 优化，避免无谓的队列往返）。
     *
     * 任务内的异常在此统一捕获并记录：主循环是 ScheduledThreadPoolExecutor，
     * 直接 execute 会把 Runnable 包进 FutureTask，异常被吞进 outcome、既不打
     * 日志也不触发 uncaughtExceptionHandler——fiber 会静默停在 PENDING。
     * 因此这里显式兜底，让失败可观测。
     */
    public void execute(final Runnable task) {
        if (inMainLoop()) {
            runSafely(task);
        } else {
            enqueue(task);
        }
    }

    /**
     * 把任务排到“当前 tick 之后”执行，即使调用方就在主循环上也不内联。
     * 用于需要与调用栈脱钩的通知（如 provide 之后的服务变更广播：必须等
     * Service 子类构造器跑完，消费者才不至于拿到半初始化的实例）。
     */
    public void executeLater(final Runnable task) {
        enqueue(task);
    }

    private void enqueue(final Runnable task) {
        try {
            mainLoop.execute(() -> runOnLoop(task));
        } catch (RejectedExecutionException e) {
            // 调度器已停机：丢弃尾声任务（停机后不再受理新回调，
            // 与 Netty 停机后拒绝写入的行为一致）
        }
    }

    /** 主循环线程的任务入口：打标 + 异常兜底 */
    private void runOnLoop(Runnable task) {
        onMainLoop.set(Boolean.TRUE);
        mainThread = Thread.currentThread();   // 线程池替换线程时自动刷新
        runSafely(task);
    }

    /** 主循环任务的统一异常出口（对应 TS 侧未捕获异常的 logger.error） */
    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.println("[cordis] main-loop task failed: " + t);
            t.printStackTrace();
        }
    }

    /**
     * 断言当前线程是主循环线程。
     * 框架内部状态（fiber 状态机、hooks、serviceStore、provisions）只在
     * 主循环上读写，跨线程触碰属于编程错误，应在发生点 fail-fast。
     */
    public void assertInMainLoop(String what) {
        if (!inMainLoop()) {
            throw new IllegalStateException(what
                    + " must run on the main loop, but was called from \""
                    + Thread.currentThread().getName() + "\"");
        }
    }

    /**
     * 把阻塞任务丢进扩展池执行，返回 Promise；完成回调保证投递回主循环。
     * 这是插件处理 JDBC/HTTP 等阻塞工作的标准入口。
     */
    public <T> Promise<T> offload(final Callable<T> task) {
        final Deferred<T> deferred = defer();
        try {
            blockingPool.execute(() -> {
                try {
                    deferred.resolve(task.call());
                } catch (Throwable t) {
                    deferred.reject(t);
                }
            });
        } catch (RejectedExecutionException e) {
            // 扩展池已停机：把失败交给 Promise，而不是抛给可能正在收尾的调用方
            deferred.reject(new IllegalStateException("scheduler has been shut down", e));
        }
        return deferred.promise();
    }

    /** 创建 Deferred（defer 模式入口：生产方持有它，消费方拿 promise()） */
    public <T> Deferred<T> defer() {
        return new Deferred<>(this);
    }

    /** 创建已成功的 Promise（等价于 Promise.resolved） */
    public <T> Promise<T> resolved(T value) {
        return Promise.resolved(this, value);
    }

    /**
     * 等待主循环排空当前已入队的任务（测试/演示编排用，严禁在主循环调用）。
     * 注意：只保证“提交时刻之前入队”的任务执行完毕。
     */
    public void awaitIdle() {
        if (inMainLoop()) {
            return;                  // 自己等自己 = 死锁，直接跳过
        }
        try {
            mainLoop.submit(() -> {
                onMainLoop.set(Boolean.TRUE);        // 屏障任务同样打标，保持线程判定一致
                mainThread = Thread.currentThread();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("main loop task failed", e);
        }
    }

    public void shutdown() {
        mainLoop.shutdownNow();
        blockingPool.shutdownNow();
    }

    boolean inMainLoop() {
        return Boolean.TRUE.equals(onMainLoop.get());
    }

    /** 当前主循环线程（仅诊断用；线程被替换后自动指向新线程） */
    public Thread mainLoopThread() {
        return mainThread;
    }

}
