/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.adaptive

import org.apache.spark.MapOutputStatistics
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.internal.SQLConf

/**
 * A rule to coalesce the shuffle partitions based on the map output statistics, which can
 * avoid many small reduce tasks that hurt performance.
 */
case class CoalesceShufflePartitions(conf: SQLConf) extends Rule[SparkPlan] {
  import CoalesceShufflePartitions._

  override def apply(plan: SparkPlan): SparkPlan = {
    if (!conf.coalesceShufflePartitionsEnabled) {
      return plan
    }
    if (!plan.collectLeaves().forall(_.isInstanceOf[QueryStageExec])
        || plan.find(_.isInstanceOf[CustomShuffleReaderExec]).isDefined) {
      // If not all leaf nodes are query stages, it's not safe to reduce the number of
      // shuffle partitions, because we may break the assumption that all children of a spark plan
      // have same number of output partitions.
      return plan
    }

    def collectShuffleStages(plan: SparkPlan): Seq[ShuffleQueryStageExec] = plan match {
      case stage: ShuffleQueryStageExec => Seq(stage)
      case _ => plan.children.flatMap(collectShuffleStages)
    }

    val shuffleStages = collectShuffleStages(plan)
    // ShuffleExchanges introduced by repartition do not support changing the number of partitions.
    // We change the number of partitions in the stage only if all the ShuffleExchanges support it.
    if (!shuffleStages.forall(_.shuffle.canChangeNumPartitions)) {
      plan
    } else {
      val shuffleMetrics = shuffleStages.map { stage =>
        assert(stage.resultOption.isDefined, "ShuffleQueryStageExec should already be ready")
        stage.resultOption.get.asInstanceOf[MapOutputStatistics]
      }

      // `ShuffleQueryStageExec` gives null mapOutputStatistics when the input RDD has 0 partitions,
      // we should skip it when calculating the `partitionStartIndices`.
      val validMetrics = shuffleMetrics.filter(_ != null)
      // We may have different pre-shuffle partition numbers, don't reduce shuffle partition number
      // in that case. For example when we union fully aggregated data (data is arranged to a single
      // partition) and a result of a SortMergeJoin (multiple partitions).
      val distinctNumPreShufflePartitions =
        validMetrics.map(stats => stats.bytesByPartitionId.length).distinct
      if (validMetrics.nonEmpty && distinctNumPreShufflePartitions.length == 1) {
        val partitionSpecs = ShufflePartitionsUtil.coalescePartitions(
          validMetrics.toArray,
          firstPartitionIndex = 0,
          lastPartitionIndex = distinctNumPreShufflePartitions.head,
          advisoryTargetSize = conf.getConf(SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES),
          minNumPartitions = conf.minShufflePartitionNum)
        // This transformation adds new nodes, so we must use `transformUp` here.
        val stageIds = shuffleStages.map(_.id).toSet
        plan.transformUp {
          // even for shuffle exchange whose input RDD has 0 partition, we should still update its
          // `partitionStartIndices`, so that all the leaf shuffles in a stage have the same
          // number of output partitions.
          case stage: ShuffleQueryStageExec if stageIds.contains(stage.id) =>
            CustomShuffleReaderExec(stage, partitionSpecs, COALESCED_SHUFFLE_READER_DESCRIPTION)
        }
      } else {
        plan
      }
    }
  }
}

object CoalesceShufflePartitions {
  val COALESCED_SHUFFLE_READER_DESCRIPTION = "coalesced"
}
