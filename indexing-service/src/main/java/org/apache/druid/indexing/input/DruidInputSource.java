/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.input;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.client.coordinator.CoordinatorClient;
import org.apache.druid.data.input.AbstractInputSource;
import org.apache.druid.data.input.InputEntity;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.InputSourceReader;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SegmentsSplitHintSpec;
import org.apache.druid.data.input.SplitHintSpec;
import org.apache.druid.data.input.impl.InputEntityIteratingReader;
import org.apache.druid.data.input.impl.SplittableInputSource;
import org.apache.druid.indexing.common.ReingestionTimelineUtils;
import org.apache.druid.indexing.common.RetryPolicy;
import org.apache.druid.indexing.common.RetryPolicyFactory;
import org.apache.druid.indexing.common.SegmentLoaderFactory;
import org.apache.druid.indexing.firehose.WindowedSegmentId;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.loading.SegmentLoader;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.timeline.partition.PartitionHolder;
import org.joda.time.Duration;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public class DruidInputSource extends AbstractInputSource implements SplittableInputSource<List<WindowedSegmentId>>
{
  private static final Logger LOG = new Logger(DruidInputSource.class);

  private final String dataSource;
  // Exactly one of interval and segmentIds should be non-null. Typically 'interval' is specified directly
  // by the user creating this firehose and 'segmentIds' is used for sub-tasks if it is split for parallel
  // batch ingestion.
  @Nullable
  private final Interval interval;
  @Nullable
  private final List<WindowedSegmentId> segmentIds;
  private final DimFilter dimFilter;
  private final List<String> dimensions;
  private final List<String> metrics;
  private final IndexIO indexIO;
  private final CoordinatorClient coordinatorClient;
  private final SegmentLoaderFactory segmentLoaderFactory;
  private final RetryPolicyFactory retryPolicyFactory;

  @JsonCreator
  public DruidInputSource(
      @JsonProperty("dataSource") final String dataSource,
      @JsonProperty("interval") @Nullable Interval interval,
      // Specifying "segments" is intended only for when this FirehoseFactory has split itself,
      // not for direct end user use.
      @JsonProperty("segments") @Nullable List<WindowedSegmentId> segmentIds,
      @JsonProperty("filter") DimFilter dimFilter,
      @Nullable @JsonProperty("dimensions") List<String> dimensions,
      @Nullable @JsonProperty("metrics") List<String> metrics,
      @JacksonInject IndexIO indexIO,
      @JacksonInject CoordinatorClient coordinatorClient,
      @JacksonInject SegmentLoaderFactory segmentLoaderFactory,
      @JacksonInject RetryPolicyFactory retryPolicyFactory
  )
  {
    Preconditions.checkNotNull(dataSource, "dataSource");
    if ((interval == null && segmentIds == null) || (interval != null && segmentIds != null)) {
      throw new IAE("Specify exactly one of 'interval' and 'segments'");
    }
    this.dataSource = dataSource;
    this.interval = interval;
    this.segmentIds = segmentIds;
    this.dimFilter = dimFilter;
    this.dimensions = dimensions;
    this.metrics = metrics;
    this.indexIO = Preconditions.checkNotNull(indexIO, "null IndexIO");
    this.coordinatorClient = Preconditions.checkNotNull(coordinatorClient, "null CoordinatorClient");
    this.segmentLoaderFactory = Preconditions.checkNotNull(segmentLoaderFactory, "null SegmentLoaderFactory");
    this.retryPolicyFactory = Preconditions.checkNotNull(retryPolicyFactory, "null RetryPolicyFactory");
  }

  @JsonProperty
  public String getDataSource()
  {
    return dataSource;
  }

  @Nullable
  @JsonProperty
  public Interval getInterval()
  {
    return interval;
  }

  @Nullable
  @JsonProperty("segments")
  @JsonInclude(Include.NON_NULL)
  public List<WindowedSegmentId> getSegmentIds()
  {
    return segmentIds;
  }

  @JsonProperty("filter")
  public DimFilter getDimFilter()
  {
    return dimFilter;
  }

  @JsonProperty
  public List<String> getDimensions()
  {
    return dimensions;
  }

  @JsonProperty
  public List<String> getMetrics()
  {
    return metrics;
  }

  @Override
  protected InputSourceReader fixedFormatReader(InputRowSchema inputRowSchema, @Nullable File temporaryDirectory)
  {
    final SegmentLoader segmentLoader = segmentLoaderFactory.manufacturate(temporaryDirectory);

    final List<TimelineObjectHolder<String, DataSegment>> timeline = createTimeline();

    final Stream<InputEntity> entityStream = createTimeline()
        .stream()
        .flatMap(holder -> {
          final PartitionHolder<DataSegment> partitionHolder = holder.getObject();
          return partitionHolder
              .stream()
              .map(chunk -> new DruidSegmentInputEntity(segmentLoader, chunk.getObject(), holder.getInterval()));
        });

    final List<String> effectiveDimensions;
    if (dimensions == null) {
      effectiveDimensions = ReingestionTimelineUtils.getUniqueDimensions(
          timeline,
          inputRowSchema.getDimensionsSpec().getDimensionExclusions()
      );
    } else if (inputRowSchema.getDimensionsSpec().hasCustomDimensions()) {
      effectiveDimensions = inputRowSchema.getDimensionsSpec().getDimensionNames();
    } else {
      effectiveDimensions = dimensions;
    }

    List<String> effectiveMetrics;
    if (metrics == null) {
      effectiveMetrics = ReingestionTimelineUtils.getUniqueMetrics(timeline);
    } else {
      effectiveMetrics = metrics;
    }

    final DruidSegmentInputFormat inputFormat = new DruidSegmentInputFormat(
        indexIO,
        dimFilter,
        effectiveDimensions,
        effectiveMetrics
    );

    return new InputEntityIteratingReader(
        inputRowSchema,
        inputFormat,
        entityStream,
        temporaryDirectory
    );
  }

  private List<TimelineObjectHolder<String, DataSegment>> createTimeline()
  {
    if (interval == null) {
      return getTimelineForSegmentIds(coordinatorClient, dataSource, segmentIds);
    } else {
      return getTimelineForInterval(coordinatorClient, retryPolicyFactory, dataSource, interval);
    }
  }

  @Override
  public Stream<InputSplit<List<WindowedSegmentId>>> createSplits(
      InputFormat inputFormat,
      @Nullable SplitHintSpec splitHintSpec
  )
  {
    // segmentIds is supposed to be specified by the supervisor task during the parallel indexing.
    // If it's not null, segments are already split by the supervisor task and further split won't happen.
    if (segmentIds == null) {
      return createSplits(
          coordinatorClient,
          retryPolicyFactory,
          dataSource,
          interval,
          splitHintSpec == null ? new SegmentsSplitHintSpec(null) : splitHintSpec
      ).stream();
    } else {
      return Stream.of(new InputSplit<>(segmentIds));
    }
  }

  @Override
  public int estimateNumSplits(InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec)
  {
    // segmentIds is supposed to be specified by the supervisor task during the parallel indexing.
    // If it's not null, segments are already split by the supervisor task and further split won't happen.
    if (segmentIds == null) {
      return createSplits(
          coordinatorClient,
          retryPolicyFactory,
          dataSource,
          interval,
          splitHintSpec == null ? new SegmentsSplitHintSpec(null) : splitHintSpec
      ).size();
    } else {
      return 1;
    }
  }

  @Override
  public SplittableInputSource<List<WindowedSegmentId>> withSplit(InputSplit<List<WindowedSegmentId>> split)
  {
    return new DruidInputSource(
        dataSource,
        null,
        split.get(),
        dimFilter,
        dimensions,
        metrics,
        indexIO,
        coordinatorClient,
        segmentLoaderFactory,
        retryPolicyFactory
    );
  }

  @Override
  public boolean needsFormat()
  {
    return false;
  }

  public static List<InputSplit<List<WindowedSegmentId>>> createSplits(
      CoordinatorClient coordinatorClient,
      RetryPolicyFactory retryPolicyFactory,
      String dataSource,
      Interval interval,
      SplitHintSpec splitHintSpec
  )
  {
    final long maxInputSegmentBytesPerTask;
    if (!(splitHintSpec instanceof SegmentsSplitHintSpec)) {
      LOG.warn("Given splitHintSpec[%s] is not a SegmentsSplitHintSpec. Ignoring it.", splitHintSpec);
      maxInputSegmentBytesPerTask = new SegmentsSplitHintSpec(null).getMaxInputSegmentBytesPerTask();
    } else {
      maxInputSegmentBytesPerTask = ((SegmentsSplitHintSpec) splitHintSpec).getMaxInputSegmentBytesPerTask();
    }

    // isSplittable() ensures this is only called when we have an interval.
    final List<TimelineObjectHolder<String, DataSegment>> timelineSegments = getTimelineForInterval(
        coordinatorClient,
        retryPolicyFactory,
        dataSource,
        interval
    );

    // We do the simplest possible greedy algorithm here instead of anything cleverer. The general bin packing
    // problem is NP-hard, and we'd like to get segments from the same interval into the same split so that their
    // data can combine with each other anyway.

    List<InputSplit<List<WindowedSegmentId>>> splits = new ArrayList<>();
    List<WindowedSegmentId> currentSplit = new ArrayList<>();
    Map<DataSegment, WindowedSegmentId> windowedSegmentIds = new HashMap<>();
    long bytesInCurrentSplit = 0;
    for (TimelineObjectHolder<String, DataSegment> timelineHolder : timelineSegments) {
      for (PartitionChunk<DataSegment> chunk : timelineHolder.getObject()) {
        final DataSegment segment = chunk.getObject();
        final WindowedSegmentId existingWindowedSegmentId = windowedSegmentIds.get(segment);
        if (existingWindowedSegmentId != null) {
          // We've already seen this segment in the timeline, so just add this interval to it. It has already
          // been placed into a split.
          existingWindowedSegmentId.getIntervals().add(timelineHolder.getInterval());
        } else {
          // It's the first time we've seen this segment, so create a new WindowedSegmentId.
          List<Interval> intervals = new ArrayList<>();
          // Use the interval that contributes to the timeline, not the entire segment's true interval.
          intervals.add(timelineHolder.getInterval());
          final WindowedSegmentId newWindowedSegmentId = new WindowedSegmentId(segment.getId().toString(), intervals);
          windowedSegmentIds.put(segment, newWindowedSegmentId);

          // Now figure out if it goes in the current split or not.
          final long segmentBytes = segment.getSize();
          if (bytesInCurrentSplit + segmentBytes > maxInputSegmentBytesPerTask && !currentSplit.isEmpty()) {
            // This segment won't fit in the current non-empty split, so this split is done.
            splits.add(new InputSplit<>(currentSplit));
            currentSplit = new ArrayList<>();
            bytesInCurrentSplit = 0;
          }
          if (segmentBytes > maxInputSegmentBytesPerTask) {
            // If this segment is itself bigger than our max, just put it in its own split.
            Preconditions.checkState(currentSplit.isEmpty() && bytesInCurrentSplit == 0);
            splits.add(new InputSplit<>(Collections.singletonList(newWindowedSegmentId)));
          } else {
            currentSplit.add(newWindowedSegmentId);
            bytesInCurrentSplit += segmentBytes;
          }
        }
      }
    }
    if (!currentSplit.isEmpty()) {
      splits.add(new InputSplit<>(currentSplit));
    }

    return splits;
  }

  public static List<TimelineObjectHolder<String, DataSegment>> getTimelineForInterval(
      CoordinatorClient coordinatorClient,
      RetryPolicyFactory retryPolicyFactory,
      String dataSource,
      Interval interval
  )
  {
    Preconditions.checkNotNull(interval);

    // This call used to use the TaskActionClient, so for compatibility we use the same retry configuration
    // as TaskActionClient.
    final RetryPolicy retryPolicy = retryPolicyFactory.makeRetryPolicy();
    Collection<DataSegment> usedSegments;
    while (true) {
      try {
        usedSegments = coordinatorClient.fetchUsedSegmentsInDataSourceForIntervals(
            dataSource,
            Collections.singletonList(interval)
        );
        break;
      }
      catch (Throwable e) {
        LOG.warn(e, "Exception getting database segments");
        final Duration delay = retryPolicy.getAndIncrementRetryDelay();
        if (delay == null) {
          throw e;
        } else {
          final long sleepTime = jitter(delay.getMillis());
          LOG.info("Will try again in [%s].", new Duration(sleepTime).toString());
          try {
            Thread.sleep(sleepTime);
          }
          catch (InterruptedException e2) {
            throw new RuntimeException(e2);
          }
        }
      }
    }

    return VersionedIntervalTimeline.forSegments(usedSegments).lookup(interval);
  }

  public static List<TimelineObjectHolder<String, DataSegment>> getTimelineForSegmentIds(
      CoordinatorClient coordinatorClient,
      String dataSource,
      List<WindowedSegmentId> segmentIds
  )
  {
    final SortedMap<Interval, TimelineObjectHolder<String, DataSegment>> timeline = new TreeMap<>(
        Comparators.intervalsByStartThenEnd()
    );
    for (WindowedSegmentId windowedSegmentId : Preconditions.checkNotNull(segmentIds, "segmentIds")) {
      final DataSegment segment = coordinatorClient.fetchUsedSegment(
          dataSource,
          windowedSegmentId.getSegmentId()
      );
      for (Interval interval : windowedSegmentId.getIntervals()) {
        final TimelineObjectHolder<String, DataSegment> existingHolder = timeline.get(interval);
        if (existingHolder != null) {
          if (!existingHolder.getVersion().equals(segment.getVersion())) {
            throw new ISE("Timeline segments with the same interval should have the same version: " +
                          "existing version[%s] vs new segment[%s]", existingHolder.getVersion(), segment);
          }
          existingHolder.getObject().add(segment.getShardSpec().createChunk(segment));
        } else {
          timeline.put(
              interval,
              new TimelineObjectHolder<>(
                  interval,
                  segment.getInterval(),
                  segment.getVersion(),
                  new PartitionHolder<>(segment.getShardSpec().createChunk(segment))
              )
          );
        }
      }
    }

    // Validate that none of the given windows overlaps (except for when multiple segments share exactly the
    // same interval).
    Interval lastInterval = null;
    for (Interval interval : timeline.keySet()) {
      if (lastInterval != null && interval.overlaps(lastInterval)) {
        throw new IAE(
            "Distinct intervals in input segments may not overlap: [%s] vs [%s]",
            lastInterval,
            interval
        );
      }
      lastInterval = interval;
    }

    return new ArrayList<>(timeline.values());
  }

  private static long jitter(long input)
  {
    final double jitter = ThreadLocalRandom.current().nextGaussian() * input / 4.0;
    long retval = input + (long) jitter;
    return retval < 0 ? 0 : retval;
  }
}
