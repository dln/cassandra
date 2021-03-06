package org.apache.cassandra.db.commitlog;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.WrappedRunnable;

class CommitLogExecutorService extends AbstractExecutorService implements CommitLogExecutorServiceMBean
{
    private final BlockingQueue<CheaterFutureTask> queue;

    private volatile long completedTaskCount = 0;

    public CommitLogExecutorService()
    {
        this(DatabaseDescriptor.getCommitLogSync() == DatabaseDescriptor.CommitLogSync.batch
             ? DatabaseDescriptor.getConcurrentWriters()
             : 1024 * Runtime.getRuntime().availableProcessors());
    }

    public CommitLogExecutorService(int queueSize)
    {
        queue = new LinkedBlockingQueue<CheaterFutureTask>(queueSize);
        Runnable runnable = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                if (DatabaseDescriptor.getCommitLogSync() == DatabaseDescriptor.CommitLogSync.batch)
                {
                    while (true)
                    {
                        processWithSyncBatch();
                        completedTaskCount++;
                    }
                }
                else
                {
                    while (true)
                    {
                        process();
                        completedTaskCount++;
                    }
                }
            }
        };
        new Thread(runnable, "COMMIT-LOG-WRITER").start();

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName("org.apache.cassandra.db:type=Commitlog"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }


    /**
     * Get the current number of running tasks
     */
    public int getActiveCount()
    {
        return 1;
    }

    /**
     * Get the number of completed tasks
     */
    public long getCompletedTasks()
    {
        return completedTaskCount;
    }

    /**
     * Get the number of tasks waiting to be executed
     */
    public long getPendingTasks()
    {
        return queue.size();
    }

    private void process() throws InterruptedException
    {
        queue.take().run();
    }

    private final ArrayList<CheaterFutureTask> incompleteTasks = new ArrayList<CheaterFutureTask>();
    private final ArrayList taskValues = new ArrayList(); // TODO not sure how to generify this
    private void processWithSyncBatch() throws Exception
    {
        CheaterFutureTask firstTask = queue.take();
        if (!(firstTask.getRawCallable() instanceof CommitLog.LogRecordAdder))
        {
            firstTask.run();
            return;
        }

        // attempt to do a bunch of LogRecordAdder ops before syncing
        // (this is a little clunky since there is no blocking peek method,
        //  so we have to break it into firstTask / extra tasks)
        incompleteTasks.clear();
        taskValues.clear();
        long end = System.nanoTime() + (long)(1000000 * DatabaseDescriptor.getCommitLogSyncBatchWindow());

        // it doesn't seem worth bothering future-izing the exception
        // since if a commitlog op throws, we're probably screwed anyway
        incompleteTasks.add(firstTask);
        taskValues.add(firstTask.getRawCallable().call());
        while (!queue.isEmpty()
               && queue.peek().getRawCallable() instanceof CommitLog.LogRecordAdder
               && System.nanoTime() < end)
        {
            CheaterFutureTask task = queue.remove();
            incompleteTasks.add(task);
            taskValues.add(task.getRawCallable().call());
        }

        // now sync and set the tasks' values (which allows thread calling get() to proceed)
        try
        {
            CommitLog.instance().sync();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < incompleteTasks.size(); i++)
        {
            incompleteTasks.get(i).set(taskValues.get(i));
        }
    }


    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value)
    {
        return newTaskFor(Executors.callable(runnable, value));
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable)
    {
        return new CheaterFutureTask(callable);
    }

    public void execute(Runnable command)
    {
        try
        {
            queue.put((CheaterFutureTask)command);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean isShutdown()
    {
        return false;
    }

    public boolean isTerminated()
    {
        return false;
    }

    // cassandra is crash-only so there's no need to implement the shutdown methods
    public void shutdown()
    {
        throw new UnsupportedOperationException();
    }

    public List<Runnable> shutdownNow()
    {
        throw new UnsupportedOperationException();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
    {
        throw new UnsupportedOperationException();
    }

    private static class CheaterFutureTask<V> extends FutureTask<V>
    {
        private final Callable rawCallable;

        public CheaterFutureTask(Callable<V> callable)
        {
            super(callable);
            rawCallable = callable;
        }

        public Callable getRawCallable()
        {
            return rawCallable;
        }

        @Override
        public void set(V v)
        {
            super.set(v);
        }
    }
}
