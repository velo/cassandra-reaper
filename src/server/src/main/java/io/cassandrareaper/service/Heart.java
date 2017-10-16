/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cassandrareaper.service;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.ReaperApplicationConfiguration;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.NodeMetrics;
import io.cassandrareaper.jmx.HostConnectionCounters;
import io.cassandrareaper.jmx.JmxProxy;
import io.cassandrareaper.storage.IDistributedStorage;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.management.JMException;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


final class Heart implements AutoCloseable {

  private static final AtomicBoolean GUAGES_REGISTERED = new AtomicBoolean(false);
  private static final Logger LOG = LoggerFactory.getLogger(Heart.class);

  private final AtomicReference<DateTime> lastBeat = new AtomicReference(DateTime.now().minusMinutes(1));
  private final ForkJoinPool forkJoinPool = new ForkJoinPool(256);


  void beat(AppContext context) {
    if (context.storage instanceof IDistributedStorage && lastBeat.get().plusMinutes(1).isBeforeNow()) {

      lastBeat.set(DateTime.now());
      ((IDistributedStorage) context.storage).saveHeartbeat();

      if (ReaperApplicationConfiguration.DatacenterAvailability.EACH == context.config.getDatacenterAvailability()) {
        updateAllReachableNodeMetrics(context);
      }
    }
  }

  @Override
  public void close() throws Exception {
    forkJoinPool.shutdownNow();
  }

  private void updateAllReachableNodeMetrics(AppContext context) {
    Preconditions.checkArgument(context.storage instanceof IDistributedStorage);
    IDistributedStorage storage = ((IDistributedStorage) context.storage);
    registerGuages(context);

    if (!forkJoinPool.hasQueuedSubmissions()) {
      HostConnectionCounters hostConnectionCounters = context.jmxConnectionFactory.getHostConnectionCounters();
      int jmxTimeoutSeconds = context.config.getJmxConnectionTimeoutInSeconds();

      forkJoinPool.submit(() -> {
        try (Timer.Context t0 = timer(context, "metrics", "all")) {

          Set<String> metrics = storage.getNodeMetrics().stream()
              .map(nodeMetric -> nodeMetric.getHostAddress())
              .collect(Collectors.toSet());

          forkJoinPool.submit(() -> {
            context.storage.getClusters().parallelStream().forEach(cluster -> {

              try (Timer.Context t1 = timer(context, "metrics", cluster.getName())) {
                Set<String> updatedNodes = Sets.newConcurrentHashSet(metrics);

                try (JmxProxy seedProxy = context.jmxConnectionFactory.connectAny(cluster, jmxTimeoutSeconds)) {

                  seedProxy.getLiveNodes().parallelStream()
                      .filter(node -> 0 <= hostConnectionCounters.getSuccessfulConnections(node))
                      .filter(node -> !updatedNodes.add(node))
                      .forEach(node -> {

                        try (
                            Timer.Context t2 = timer(context, "metrics", cluster.getName(), node);
                            JmxProxy nodeProxy = context.jmxConnectionFactory.connect(node, jmxTimeoutSeconds)) {

                          assert nodeProxy.getHost().equals(node);

                          storage.storeNodeMetrics(
                              NodeMetrics.builder()
                                  .withHostAddress(node)
                                  .withDatacenter(nodeProxy.getDataCenter())
                                  .withPendingCompactions(nodeProxy.getPendingCompactions())
                                  .withHasRepairRunning(nodeProxy.isRepairRunning())
                                  .withActiveAnticompactions(0) // for future use
                                  .build());
                        } catch (ReaperException | JMException ex) {
                          LOG.warn("failed NodeMetrics update on " + node + " in " + cluster.getName(), ex);
                        }
                      });

                  LOG.info(
                      "Updated metrics in cluster {} for the nodes {}",
                      cluster.getName(),
                      String.join(",", updatedNodes));
                } catch (ReaperException | RuntimeException ex) {
                  LOG.warn("failed seed connection in cluster " + cluster.getName(), ex);
                }
              }
            });
          }).get();
        } catch (ExecutionException | InterruptedException | RuntimeException ex) {
          LOG.warn("failed updateAllReachableNodeMetrics submission", ex);
        }
      });
    }
  }

  private static Timer.Context timer(AppContext context, String... names) {
    return context.metricRegistry.timer(MetricRegistry.name(Heart.class, names)).time();
  }

  private void registerGuages(AppContext context) throws IllegalArgumentException {
    if (!GUAGES_REGISTERED.getAndSet(true)) {

      context.metricRegistry.register(
          MetricRegistry.name(Heart.class, "runningThreadCount"),
          (Gauge<Integer>) () -> forkJoinPool.getRunningThreadCount());

      context.metricRegistry.register(
          MetricRegistry.name(Heart.class, "activeThreadCount"),
          (Gauge<Integer>) () -> forkJoinPool.getActiveThreadCount());

      context.metricRegistry.register(
          MetricRegistry.name(Heart.class, "queuedTaskCount"),
          (Gauge<Long>) () -> forkJoinPool.getQueuedTaskCount());

      context.metricRegistry.register(
          MetricRegistry.name(Heart.class, "queuedSubmissionCount"),
          (Gauge<Integer>) () -> forkJoinPool.getQueuedSubmissionCount());
    }
  }
}
