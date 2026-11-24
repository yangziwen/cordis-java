package cordis.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Thread> mainThread = new AtomicReference<>();
    private final ExecutorService blockingPool;

    public Scheduler() {
        this(Math.max(4, java.lang.Runtime.getRuntime().availableProcessors()));
    }

    public Scheduler(int blockingPoolSize) {
        mainLoop = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "cordis-main-loop");
            t.setDaemon(true);
            mainThread.compareAndSet(null, t);
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
     */
    public void execute(Runnable task) {
        if (Thread.currentThread() == mainThread.get()) {
            task.run();
        } else {
            try {
                mainLoop.execute(task);
            } catch (RejectedExecutionException e) {
                // 调度器已停机：丢弃尾声任务（停机后不再受理新回调，
                // 与 Netty 停机后拒绝写入的行为一致）
            }
        }
    }

    /**
     * 把阻塞任务丢进扩展池执行，返回 Promise；完成回调保证投递回主循环。
     * 这是插件处理 JDBC/HTTP 等阻塞工作的标准入口。
     */
    public <T> Promise<T> offload(final Callable<T> task) {
        final Deferred<T> deferred = defer();
        blockingPool.execute(() -> {
            try {
                deferred.resolve(task.call());
            } catch (Throwable t) {
                deferred.reject(t);
            }
        });
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
        if (inMainLoop()) return;   // 自己等自己 = 死锁，直接跳过
        try {
            mainLoop.submit(() -> { }).get();
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
        return Thread.currentThread() == mainThread.get();
    }
}
