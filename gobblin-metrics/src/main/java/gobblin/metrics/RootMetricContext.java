/*
 * Copyright (C) 2014-2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.metrics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;

import gobblin.metrics.context.ContextWeakReference;
import gobblin.metrics.context.NameConflictException;
import gobblin.metrics.notification.MetricContextCleanupNotification;
import gobblin.metrics.notification.NewMetricContextNotification;
import gobblin.metrics.reporter.ContextAwareReporter;
import gobblin.util.ExecutorsUtils;


/**
 * Special singleton {@link MetricContext} used as the root of the {@link MetricContext} tree. This is the only
 * {@link MetricContext} that is allowed to not have a parent. Any {@link MetricContext} that does not explicitly
 * have a parent will automatically become a child of the {@link RootMetricContext}.
 */
@Slf4j
public class RootMetricContext extends MetricContext {

  public static final String ROOT_METRIC_CONTEXT = "RootMetricContext";

  @Getter
  private final ReferenceQueue<MetricContext> referenceQueue;
  private final Set<InnerMetricContext> innerMetricContexts;
  private final ScheduledExecutorService referenceQueueExecutorService;

  private final Set<ContextAwareReporter> reporters;

  private volatile boolean reportingStarted;

  private RootMetricContext(List<Tag<?>> tags) throws NameConflictException {
    super(ROOT_METRIC_CONTEXT, null, tags, true);
    this.innerMetricContexts = Sets.newConcurrentHashSet();
    this.referenceQueue = new ReferenceQueue<MetricContext>();
    this.referenceQueueExecutorService = MoreExecutors.getExitingScheduledExecutorService(new ScheduledThreadPoolExecutor(1,
        ExecutorsUtils.newThreadFactory(Optional.of(log), Optional.of("GobblinMetrics-ReferenceQueue"))));
    this.referenceQueueExecutorService.scheduleWithFixedDelay(new CheckReferenceQueue(), 0, 2, TimeUnit.SECONDS);

    this.reporters = Sets.newConcurrentHashSet();
    this.reportingStarted = false;
  }

  private static void initialize() {
    try {
      INSTANCE = new RootMetricContext(Lists.<Tag<?>>newArrayList());
    } catch (NameConflictException nce) {
      // Should never happen, as there is no parent, so no conflict.
      throw new RuntimeException("Failed to generate root metric context. This is an error in the code.");
    }
  }

  /**
   * Get the singleton {@link RootMetricContext}.
   * @return singleton instance of {@link RootMetricContext}.
   */
  public synchronized static RootMetricContext get() {

    if(INSTANCE == null) {
      initialize();
    }
    return INSTANCE;
  }

  private static RootMetricContext INSTANCE;

  /**
   * Checks the {@link ReferenceQueue} to find any {@link MetricContext}s that have been garbage collected, and
   * sends a {@link MetricContextCleanupNotification} to all targets.
   */
  private class CheckReferenceQueue implements Runnable {
    @Override public void run() {
      Reference<? extends MetricContext> reference;
      while((reference = referenceQueue.poll()) != null) {
        ContextWeakReference contextReference = (ContextWeakReference)reference;

        sendNotification(new MetricContextCleanupNotification(contextReference.getInnerContext()));
        innerMetricContexts.remove(contextReference.getInnerContext());
      }
    }
  }

  /**
   * Add a new {@link ContextAwareReporter} to the {@link RootMetricContext} for it to manage.
   * @param reporter {@link ContextAwareReporter} to manage.
   */
  public void addNewReporter(ContextAwareReporter reporter) {
    this.reporters.add(this.closer.register(reporter));
    if(this.reportingStarted) {
      reporter.start();
    }
  }

  /**
   * Remove {@link ContextAwareReporter} from the set of managed reporters.
   * @param reporter {@link ContextAwareReporter} to remove.
   */
  public void removeReporter(ContextAwareReporter reporter) {
    if(this.reporters.contains(reporter)) {
      reporter.stop();
      this.reporters.remove(reporter);
    }
  }

  /**
   * Start all {@link ContextAwareReporter}s managed by the {@link RootMetricContext}.
   */
  public void startReporting() {
    this.reportingStarted = true;
    for(ContextAwareReporter reporter : this.reporters) {
      try {
        reporter.start();
      } catch(Throwable throwable) {
        log.error(String.format("Failed to start reporter with class %s", reporter.getClass().getCanonicalName()),
            throwable);
      }
    }
  }

  /**
   * Stop all {@link ContextAwareReporter}s managed by the {@link RootMetricContext}.
   */
  public void stopReporting() {
    this.reportingStarted = false;
    for(ContextAwareReporter reporter : this.reporters) {
      try {
        reporter.stop();
      } catch(Throwable throwable) {
        log.error(String.format("Failed to stop reporter with class %s", reporter.getClass().getCanonicalName()),
            throwable);
      }
    }
  }

  protected void addMetricContext(MetricContext context) {
    this.innerMetricContexts.add(context.getInnerMetricContext());
    this.sendNotification(new NewMetricContextNotification(context, context.getInnerMetricContext()));
  }

}