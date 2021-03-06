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

package org.apache.spark.sql.internal

import java.util.{NoSuchElementException, Properties}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.immutable

import org.apache.parquet.hadoop.ParquetOutputCommitter

import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.network.util.ByteUnit
import org.apache.spark.sql.catalyst.CatalystConf

////////////////////////////////////////////////////////////////////////////////////////////////////
// This file defines the configuration options for Spark SQL.
////////////////////////////////////////////////////////////////////////////////////////////////////


object SQLConf {

  private val sqlConfEntries = java.util.Collections.synchronizedMap(
    new java.util.HashMap[String, ConfigEntry[_]]())

  private def register(entry: ConfigEntry[_]): Unit = sqlConfEntries.synchronized {
    require(!sqlConfEntries.containsKey(entry.key),
      s"Duplicate SQLConfigEntry. ${entry.key} has been registered")
    sqlConfEntries.put(entry.key, entry)
  }

  private[sql] object SQLConfigBuilder {

    def apply(key: String): ConfigBuilder = new ConfigBuilder(key).onCreate(register)

  }

  val OPTIMIZER_MAX_ITERATIONS = SQLConfigBuilder("spark.sql.optimizer.maxIterations")
    .internal()
    .doc("The max number of iterations the optimizer and analyzer runs")
    .intConf
    .createWithDefault(100)

  val ALLOW_MULTIPLE_CONTEXTS = SQLConfigBuilder("spark.sql.allowMultipleContexts")
    .doc("When set to true, creating multiple SQLContexts/HiveContexts is allowed. " +
      "When set to false, only one SQLContext/HiveContext is allowed to be created " +
      "through the constructor (new SQLContexts/HiveContexts created through newSession " +
      "method is allowed). Please note that this conf needs to be set in Spark Conf. Once " +
      "a SQLContext/HiveContext has been created, changing the value of this conf will not " +
      "have effect.")
    .booleanConf
    .createWithDefault(true)

  val COMPRESS_CACHED = SQLConfigBuilder("spark.sql.inMemoryColumnarStorage.compressed")
    .internal()
    .doc("When set to true Spark SQL will automatically select a compression codec for each " +
      "column based on statistics of the data.")
    .booleanConf
    .createWithDefault(true)

  val COLUMN_BATCH_SIZE = SQLConfigBuilder("spark.sql.inMemoryColumnarStorage.batchSize")
    .internal()
    .doc("Controls the size of batches for columnar caching.  Larger batch sizes can improve " +
      "memory utilization and compression, but risk OOMs when caching data.")
    .intConf
    .createWithDefault(10000)

  val IN_MEMORY_PARTITION_PRUNING =
    SQLConfigBuilder("spark.sql.inMemoryColumnarStorage.partitionPruning")
      .internal()
      .doc("When true, enable partition pruning for in-memory columnar tables.")
      .booleanConf
      .createWithDefault(true)

  val PREFER_SORTMERGEJOIN = SQLConfigBuilder("spark.sql.join.preferSortMergeJoin")
    .internal()
    .doc("When true, prefer sort merge join over shuffle hash join.")
    .booleanConf
    .createWithDefault(true)

  val AUTO_BROADCASTJOIN_THRESHOLD = SQLConfigBuilder("spark.sql.autoBroadcastJoinThreshold")
    .doc("Configures the maximum size in bytes for a table that will be broadcast to all worker " +
      "nodes when performing a join.  By setting this value to -1 broadcasting can be disabled. " +
      "Note that currently statistics are only supported for Hive Metastore tables where the " +
      "command<code>ANALYZE TABLE &lt;tableName&gt; COMPUTE STATISTICS noscan</code> has been run.")
    .intConf
    .createWithDefault(10 * 1024 * 1024)

  val DEFAULT_SIZE_IN_BYTES = SQLConfigBuilder("spark.sql.defaultSizeInBytes")
    .internal()
    .doc("The default table size used in query planning. By default, it is set to a larger " +
      "value than `spark.sql.autoBroadcastJoinThreshold` to be more conservative. That is to say " +
      "by default the optimizer will not choose to broadcast a table unless it knows for sure " +
      "its size is small enough.")
    .longConf
    .createWithDefault(-1)

  val SHUFFLE_PARTITIONS = SQLConfigBuilder("spark.sql.shuffle.partitions")
    .doc("The default number of partitions to use when shuffling data for joins or aggregations.")
    .intConf
    .createWithDefault(200)

  val SHUFFLE_TARGET_POSTSHUFFLE_INPUT_SIZE =
    SQLConfigBuilder("spark.sql.adaptive.shuffle.targetPostShuffleInputSize")
      .doc("The target post-shuffle input size in bytes of a task.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefault(64 * 1024 * 1024)

  val ADAPTIVE_EXECUTION_ENABLED = SQLConfigBuilder("spark.sql.adaptive.enabled")
    .doc("When true, enable adaptive query execution.")
    .booleanConf
    .createWithDefault(false)

  val SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS =
    SQLConfigBuilder("spark.sql.adaptive.minNumPostShufflePartitions")
      .internal()
      .doc("The advisory minimal number of post-shuffle partitions provided to " +
        "ExchangeCoordinator. This setting is used in our test to make sure we " +
        "have enough parallelism to expose issues that will not be exposed with a " +
        "single partition. When the value is a non-positive value, this setting will " +
        "not be provided to ExchangeCoordinator.")
      .intConf
      .createWithDefault(-1)

  val SUBEXPRESSION_ELIMINATION_ENABLED =
    SQLConfigBuilder("spark.sql.subexpressionElimination.enabled")
      .internal()
      .doc("When true, common subexpressions will be eliminated.")
      .booleanConf
      .createWithDefault(true)

  val CASE_SENSITIVE = SQLConfigBuilder("spark.sql.caseSensitive")
    .doc("Whether the query analyzer should be case sensitive or not.")
    .booleanConf
    .createWithDefault(true)

  val PARQUET_SCHEMA_MERGING_ENABLED = SQLConfigBuilder("spark.sql.parquet.mergeSchema")
    .doc("When true, the Parquet data source merges schemas collected from all data files, " +
         "otherwise the schema is picked from the summary file or a random data file " +
         "if no summary file is available.")
    .booleanConf
    .createWithDefault(false)

  val PARQUET_SCHEMA_RESPECT_SUMMARIES = SQLConfigBuilder("spark.sql.parquet.respectSummaryFiles")
    .doc("When true, we make assumption that all part-files of Parquet are consistent with " +
         "summary files and we will ignore them when merging schema. Otherwise, if this is " +
         "false, which is the default, we will merge all part-files. This should be considered " +
         "as expert-only option, and shouldn't be enabled before knowing what it means exactly.")
    .booleanConf
    .createWithDefault(false)

  val PARQUET_BINARY_AS_STRING = SQLConfigBuilder("spark.sql.parquet.binaryAsString")
    .doc("Some other Parquet-producing systems, in particular Impala and older versions of " +
      "Spark SQL, do not differentiate between binary data and strings when writing out the " +
      "Parquet schema. This flag tells Spark SQL to interpret binary data as a string to provide " +
      "compatibility with these systems.")
    .booleanConf
    .createWithDefault(false)

  val PARQUET_INT96_AS_TIMESTAMP = SQLConfigBuilder("spark.sql.parquet.int96AsTimestamp")
    .doc("Some Parquet-producing systems, in particular Impala, store Timestamp into INT96. " +
      "Spark would also store Timestamp as INT96 because we need to avoid precision lost of the " +
      "nanoseconds field. This flag tells Spark SQL to interpret INT96 data as a timestamp to " +
      "provide compatibility with these systems.")
    .booleanConf
    .createWithDefault(true)

  val PARQUET_CACHE_METADATA = SQLConfigBuilder("spark.sql.parquet.cacheMetadata")
    .doc("Turns on caching of Parquet schema metadata. Can speed up querying of static data.")
    .booleanConf
    .createWithDefault(true)

  val PARQUET_COMPRESSION = SQLConfigBuilder("spark.sql.parquet.compression.codec")
    .doc("Sets the compression codec use when writing Parquet files. Acceptable values include: " +
      "uncompressed, snappy, gzip, lzo.")
    .stringConf
    .transform(_.toLowerCase())
    .checkValues(Set("uncompressed", "snappy", "gzip", "lzo"))
    .createWithDefault("snappy")

  val PARQUET_FILTER_PUSHDOWN_ENABLED = SQLConfigBuilder("spark.sql.parquet.filterPushdown")
    .doc("Enables Parquet filter push-down optimization when set to true.")
    .booleanConf
    .createWithDefault(true)

  val PARQUET_WRITE_LEGACY_FORMAT = SQLConfigBuilder("spark.sql.parquet.writeLegacyFormat")
    .doc("Whether to follow Parquet's format specification when converting Parquet schema to " +
      "Spark SQL schema and vice versa.")
    .booleanConf
    .createWithDefault(false)

  val PARQUET_OUTPUT_COMMITTER_CLASS = SQLConfigBuilder("spark.sql.parquet.output.committer.class")
    .doc("The output committer class used by Parquet. The specified class needs to be a " +
      "subclass of org.apache.hadoop.mapreduce.OutputCommitter.  Typically, it's also a subclass " +
      "of org.apache.parquet.hadoop.ParquetOutputCommitter.  NOTE: 1. Instead of SQLConf, this " +
      "option must be set in Hadoop Configuration.  2. This option overrides " +
      "\"spark.sql.sources.outputCommitterClass\".")
    .stringConf
    .createWithDefault(classOf[ParquetOutputCommitter].getName)

  val PARQUET_VECTORIZED_READER_ENABLED =
    SQLConfigBuilder("spark.sql.parquet.enableVectorizedReader")
      .doc("Enables vectorized parquet decoding.")
      .booleanConf
      .createWithDefault(true)

  val ORC_FILTER_PUSHDOWN_ENABLED = SQLConfigBuilder("spark.sql.orc.filterPushdown")
    .doc("When true, enable filter pushdown for ORC files.")
    .booleanConf
    .createWithDefault(false)

  val HIVE_VERIFY_PARTITION_PATH = SQLConfigBuilder("spark.sql.hive.verifyPartitionPath")
    .doc("When true, check all the partition paths under the table\'s root directory " +
         "when reading data stored in HDFS.")
    .booleanConf
    .createWithDefault(false)

  val HIVE_METASTORE_PARTITION_PRUNING =
    SQLConfigBuilder("spark.sql.hive.metastorePartitionPruning")
      .doc("When true, some predicates will be pushed down into the Hive metastore so that " +
           "unmatching partitions can be eliminated earlier.")
      .booleanConf
      .createWithDefault(false)

  val NATIVE_VIEW = SQLConfigBuilder("spark.sql.nativeView")
    .internal()
    .doc("When true, CREATE VIEW will be handled by Spark SQL instead of Hive native commands.  " +
         "Note that this function is experimental and should ony be used when you are using " +
         "non-hive-compatible tables written by Spark SQL.  The SQL string used to create " +
         "view should be fully qualified, i.e. use `tbl1`.`col1` instead of `*` whenever " +
         "possible, or you may get wrong result.")
    .booleanConf
    .createWithDefault(true)

  val CANONICAL_NATIVE_VIEW = SQLConfigBuilder("spark.sql.nativeView.canonical")
    .internal()
    .doc("When this option and spark.sql.nativeView are both true, Spark SQL tries to handle " +
         "CREATE VIEW statement using SQL query string generated from view definition logical " +
         "plan.  If the logical plan doesn't have a SQL representation, we fallback to the " +
         "original native view implementation.")
    .booleanConf
    .createWithDefault(true)

  val COLUMN_NAME_OF_CORRUPT_RECORD = SQLConfigBuilder("spark.sql.columnNameOfCorruptRecord")
    .doc("The name of internal column for storing raw/un-parsed JSON records that fail to parse.")
    .stringConf
    .createWithDefault("_corrupt_record")

  val BROADCAST_TIMEOUT = SQLConfigBuilder("spark.sql.broadcastTimeout")
    .doc("Timeout in seconds for the broadcast wait time in broadcast joins.")
    .intConf
    .createWithDefault(5 * 60)

  // This is only used for the thriftserver
  val THRIFTSERVER_POOL = SQLConfigBuilder("spark.sql.thriftserver.scheduler.pool")
    .doc("Set a Fair Scheduler pool for a JDBC client session.")
    .stringConf
    .createOptional

  val THRIFTSERVER_UI_STATEMENT_LIMIT =
    SQLConfigBuilder("spark.sql.thriftserver.ui.retainedStatements")
      .doc("The number of SQL statements kept in the JDBC/ODBC web UI history.")
      .intConf
      .createWithDefault(200)

  val THRIFTSERVER_UI_SESSION_LIMIT = SQLConfigBuilder("spark.sql.thriftserver.ui.retainedSessions")
    .doc("The number of SQL client sessions kept in the JDBC/ODBC web UI history.")
    .intConf
    .createWithDefault(200)

  // This is used to set the default data source
  val DEFAULT_DATA_SOURCE_NAME = SQLConfigBuilder("spark.sql.sources.default")
    .doc("The default data source to use in input/output.")
    .stringConf
    .createWithDefault("parquet")

  // This is used to control the when we will split a schema's JSON string to multiple pieces
  // in order to fit the JSON string in metastore's table property (by default, the value has
  // a length restriction of 4000 characters). We will split the JSON string of a schema
  // to its length exceeds the threshold.
  val SCHEMA_STRING_LENGTH_THRESHOLD =
    SQLConfigBuilder("spark.sql.sources.schemaStringLengthThreshold")
      .doc("The maximum length allowed in a single cell when " +
        "storing additional schema information in Hive's metastore.")
      .internal()
      .intConf
      .createWithDefault(4000)

  val PARTITION_DISCOVERY_ENABLED = SQLConfigBuilder("spark.sql.sources.partitionDiscovery.enabled")
    .doc("When true, automatically discover data partitions.")
    .booleanConf
    .createWithDefault(true)

  val PARTITION_COLUMN_TYPE_INFERENCE =
    SQLConfigBuilder("spark.sql.sources.partitionColumnTypeInference.enabled")
      .doc("When true, automatically infer the data types for partitioned columns.")
      .booleanConf
      .createWithDefault(true)

  val PARTITION_MAX_FILES =
    SQLConfigBuilder("spark.sql.sources.maxConcurrentWrites")
      .doc("The maximum number of concurrent files to open before falling back on sorting when " +
            "writing out files using dynamic partitioning.")
      .intConf
      .createWithDefault(1)

  val BUCKETING_ENABLED = SQLConfigBuilder("spark.sql.sources.bucketing.enabled")
    .doc("When false, we will treat bucketed table as normal table")
    .booleanConf
    .createWithDefault(true)

  val ORDER_BY_ORDINAL = SQLConfigBuilder("spark.sql.orderByOrdinal")
    .doc("When true, the ordinal numbers are treated as the position in the select list. " +
         "When false, the ordinal numbers in order/sort By clause are ignored.")
    .booleanConf
    .createWithDefault(true)

  val GROUP_BY_ORDINAL = SQLConfigBuilder("spark.sql.groupByOrdinal")
    .doc("When true, the ordinal numbers in group by clauses are treated as the position " +
      "in the select list. When false, the ordinal numbers are ignored.")
    .booleanConf
    .createWithDefault(true)

  // The output committer class used by HadoopFsRelation. The specified class needs to be a
  // subclass of org.apache.hadoop.mapreduce.OutputCommitter.
  //
  // NOTE:
  //
  //  1. Instead of SQLConf, this option *must be set in Hadoop Configuration*.
  //  2. This option can be overridden by "spark.sql.parquet.output.committer.class".
  val OUTPUT_COMMITTER_CLASS =
    SQLConfigBuilder("spark.sql.sources.outputCommitterClass").internal().stringConf.createOptional

  val PARALLEL_PARTITION_DISCOVERY_THRESHOLD =
    SQLConfigBuilder("spark.sql.sources.parallelPartitionDiscovery.threshold")
      .doc("The degree of parallelism for schema merging and partition discovery of " +
        "Parquet data sources.")
      .intConf
      .createWithDefault(32)

  // Whether to perform eager analysis when constructing a dataframe.
  // Set to false when debugging requires the ability to look at invalid query plans.
  val DATAFRAME_EAGER_ANALYSIS = SQLConfigBuilder("spark.sql.eagerAnalysis")
    .internal()
    .doc("When true, eagerly applies query analysis on DataFrame operations.")
    .booleanConf
    .createWithDefault(true)

  // Whether to automatically resolve ambiguity in join conditions for self-joins.
  // See SPARK-6231.
  val DATAFRAME_SELF_JOIN_AUTO_RESOLVE_AMBIGUITY =
    SQLConfigBuilder("spark.sql.selfJoinAutoResolveAmbiguity")
      .internal()
      .booleanConf
      .createWithDefault(true)

  // Whether to retain group by columns or not in GroupedData.agg.
  val DATAFRAME_RETAIN_GROUP_COLUMNS = SQLConfigBuilder("spark.sql.retainGroupColumns")
    .internal()
    .booleanConf
    .createWithDefault(true)

  val DATAFRAME_PIVOT_MAX_VALUES = SQLConfigBuilder("spark.sql.pivotMaxValues")
    .doc("When doing a pivot without specifying values for the pivot column this is the maximum " +
      "number of (distinct) values that will be collected without error.")
    .intConf
    .createWithDefault(10000)

  val RUN_SQL_ON_FILES = SQLConfigBuilder("spark.sql.runSQLOnFiles")
    .internal()
    .doc("When true, we could use `datasource`.`path` as table in SQL query.")
    .booleanConf
    .createWithDefault(true)

  val WHOLESTAGE_CODEGEN_ENABLED = SQLConfigBuilder("spark.sql.codegen.wholeStage")
    .internal()
    .doc("When true, the whole stage (of multiple operators) will be compiled into single java" +
      " method.")
    .booleanConf
    .createWithDefault(true)

  val WHOLESTAGE_MAX_NUM_FIELDS = SQLConfigBuilder("spark.sql.codegen.maxFields")
    .internal()
    .doc("The maximum number of fields (including nested fields) that will be supported before" +
      " deactivating whole-stage codegen.")
    .intConf
    .createWithDefault(200)

  val MAX_CASES_BRANCHES = SQLConfigBuilder("spark.sql.codegen.maxCaseBranches")
    .internal()
    .doc("The maximum number of switches supported with codegen.")
    .intConf
    .createWithDefault(20)

  val FILES_MAX_PARTITION_BYTES = SQLConfigBuilder("spark.sql.files.maxPartitionBytes")
    .doc("The maximum number of bytes to pack into a single partition when reading files.")
    .longConf
    .createWithDefault(128 * 1024 * 1024) // parquet.block.size

  val FILES_OPEN_COST_IN_BYTES = SQLConfigBuilder("spark.sql.files.openCostInBytes")
    .internal()
    .doc("The estimated cost to open a file, measured by the number of bytes could be scanned in" +
      " the same time. This is used when putting multiple files into a partition. It's better to" +
      " over estimated, then the partitions with small files will be faster than partitions with" +
      " bigger files (which is scheduled first).")
    .longConf
    .createWithDefault(4 * 1024 * 1024)

  val EXCHANGE_REUSE_ENABLED = SQLConfigBuilder("spark.sql.exchange.reuse")
    .internal()
    .doc("When true, the planner will try to find out duplicated exchanges and re-use them.")
    .booleanConf
    .createWithDefault(true)

  val STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT =
    SQLConfigBuilder("spark.sql.streaming.stateStore.minDeltasForSnapshot")
      .internal()
      .doc("Minimum number of state store delta files that needs to be generated before they " +
        "consolidated into snapshots.")
      .intConf
      .createWithDefault(10)

  val STATE_STORE_MIN_VERSIONS_TO_RETAIN =
    SQLConfigBuilder("spark.sql.streaming.stateStore.minBatchesToRetain")
      .internal()
      .doc("Minimum number of versions of a state store's data to retain after cleaning.")
      .intConf
      .createWithDefault(2)

  val CHECKPOINT_LOCATION = SQLConfigBuilder("spark.sql.streaming.checkpointLocation")
    .doc("The default location for storing checkpoint data for continuously executing queries.")
    .stringConf
    .createOptional

  val UNSUPPORTED_OPERATION_CHECK_ENABLED =
    SQLConfigBuilder("spark.sql.streaming.unsupportedOperationCheck")
      .internal()
      .doc("When true, the logical plan for continuous query will be checked for unsupported" +
        " operations.")
      .booleanConf
      .createWithDefault(true)

  val VARIABLE_SUBSTITUTE_ENABLED =
    SQLConfigBuilder("spark.sql.variable.substitute")
      .doc("This enables substitution using syntax like ${var} ${system:var} and ${env:var}.")
      .booleanConf
      .createWithDefault(true)

  val VARIABLE_SUBSTITUTE_DEPTH =
    SQLConfigBuilder("spark.sql.variable.substitute.depth")
      .doc("The maximum replacements the substitution engine will do.")
      .intConf
      .createWithDefault(40)

  // TODO: This is still WIP and shouldn't be turned on without extensive test coverage
  val COLUMNAR_AGGREGATE_MAP_ENABLED = SQLConfigBuilder("spark.sql.codegen.aggregate.map.enabled")
    .internal()
    .doc("When true, aggregate with keys use an in-memory columnar map to speed up execution.")
    .booleanConf
    .createWithDefault(false)

  val FILE_SINK_LOG_DELETION = SQLConfigBuilder("spark.sql.streaming.fileSink.log.deletion")
    .internal()
    .doc("Whether to delete the expired log files in file stream sink.")
    .booleanConf
    .createWithDefault(true)

  val FILE_SINK_LOG_COMPACT_INTERVAL =
    SQLConfigBuilder("spark.sql.streaming.fileSink.log.compactInterval")
      .internal()
      .doc("Number of log files after which all the previous files " +
        "are compacted into the next log file.")
      .intConf
      .createWithDefault(10)

  val FILE_SINK_LOG_CLEANUP_DELAY =
    SQLConfigBuilder("spark.sql.streaming.fileSink.log.cleanupDelay")
      .internal()
      .doc("How long in milliseconds a file is guaranteed to be visible for all readers.")
      .timeConf(TimeUnit.MILLISECONDS)
      .createWithDefault(60 * 1000L) // 10 minutes

  object Deprecated {
    val MAPRED_REDUCE_TASKS = "mapred.reduce.tasks"
    val EXTERNAL_SORT = "spark.sql.planner.externalSort"
    val USE_SQL_AGGREGATE2 = "spark.sql.useAggregate2"
    val TUNGSTEN_ENABLED = "spark.sql.tungsten.enabled"
    val CODEGEN_ENABLED = "spark.sql.codegen"
    val UNSAFE_ENABLED = "spark.sql.unsafe.enabled"
    val SORTMERGE_JOIN = "spark.sql.planner.sortMergeJoin"
    val PARQUET_UNSAFE_ROW_RECORD_READER_ENABLED = "spark.sql.parquet.enableUnsafeRowRecordReader"
  }
}

/**
 * A class that enables the setting and getting of mutable config parameters/hints.
 *
 * In the presence of a SQLContext, these can be set and queried by passing SET commands
 * into Spark SQL's query functions (i.e. sql()). Otherwise, users of this class can
 * modify the hints by programmatically calling the setters and getters of this class.
 *
 * SQLConf is thread-safe (internally synchronized, so safe to be used in multiple threads).
 */
private[sql] class SQLConf extends Serializable with CatalystConf with Logging {
  import SQLConf._

  /** Only low degree of contention is expected for conf, thus NOT using ConcurrentHashMap. */
  @transient protected[spark] val settings = java.util.Collections.synchronizedMap(
    new java.util.HashMap[String, String]())

  /** ************************ Spark SQL Params/Hints ******************* */

  def optimizerMaxIterations: Int = getConf(OPTIMIZER_MAX_ITERATIONS)

  def checkpointLocation: String = getConf(CHECKPOINT_LOCATION)

  def filesMaxPartitionBytes: Long = getConf(FILES_MAX_PARTITION_BYTES)

  def filesOpenCostInBytes: Long = getConf(FILES_OPEN_COST_IN_BYTES)

  def useCompression: Boolean = getConf(COMPRESS_CACHED)

  def parquetCompressionCodec: String = getConf(PARQUET_COMPRESSION)

  def parquetCacheMetadata: Boolean = getConf(PARQUET_CACHE_METADATA)

  def parquetVectorizedReaderEnabled: Boolean = getConf(PARQUET_VECTORIZED_READER_ENABLED)

  def columnBatchSize: Int = getConf(COLUMN_BATCH_SIZE)

  def numShufflePartitions: Int = getConf(SHUFFLE_PARTITIONS)

  def targetPostShuffleInputSize: Long =
    getConf(SHUFFLE_TARGET_POSTSHUFFLE_INPUT_SIZE)

  def adaptiveExecutionEnabled: Boolean = getConf(ADAPTIVE_EXECUTION_ENABLED)

  def minNumPostShufflePartitions: Int =
    getConf(SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS)

  def parquetFilterPushDown: Boolean = getConf(PARQUET_FILTER_PUSHDOWN_ENABLED)

  def orcFilterPushDown: Boolean = getConf(ORC_FILTER_PUSHDOWN_ENABLED)

  def verifyPartitionPath: Boolean = getConf(HIVE_VERIFY_PARTITION_PATH)

  def metastorePartitionPruning: Boolean = getConf(HIVE_METASTORE_PARTITION_PRUNING)

  def nativeView: Boolean = getConf(NATIVE_VIEW)

  def wholeStageEnabled: Boolean = getConf(WHOLESTAGE_CODEGEN_ENABLED)

  def wholeStageMaxNumFields: Int = getConf(WHOLESTAGE_MAX_NUM_FIELDS)

  def maxCaseBranchesForCodegen: Int = getConf(MAX_CASES_BRANCHES)

  def exchangeReuseEnabled: Boolean = getConf(EXCHANGE_REUSE_ENABLED)

  def canonicalView: Boolean = getConf(CANONICAL_NATIVE_VIEW)

  def caseSensitiveAnalysis: Boolean = getConf(SQLConf.CASE_SENSITIVE)

  def subexpressionEliminationEnabled: Boolean =
    getConf(SUBEXPRESSION_ELIMINATION_ENABLED)

  def autoBroadcastJoinThreshold: Int = getConf(AUTO_BROADCASTJOIN_THRESHOLD)

  def preferSortMergeJoin: Boolean = getConf(PREFER_SORTMERGEJOIN)

  def defaultSizeInBytes: Long =
    getConf(DEFAULT_SIZE_IN_BYTES, autoBroadcastJoinThreshold + 1L)

  def isParquetBinaryAsString: Boolean = getConf(PARQUET_BINARY_AS_STRING)

  def isParquetINT96AsTimestamp: Boolean = getConf(PARQUET_INT96_AS_TIMESTAMP)

  def writeLegacyParquetFormat: Boolean = getConf(PARQUET_WRITE_LEGACY_FORMAT)

  def inMemoryPartitionPruning: Boolean = getConf(IN_MEMORY_PARTITION_PRUNING)

  def columnNameOfCorruptRecord: String = getConf(COLUMN_NAME_OF_CORRUPT_RECORD)

  def broadcastTimeout: Int = getConf(BROADCAST_TIMEOUT)

  def defaultDataSourceName: String = getConf(DEFAULT_DATA_SOURCE_NAME)

  def partitionDiscoveryEnabled(): Boolean =
    getConf(SQLConf.PARTITION_DISCOVERY_ENABLED)

  def partitionColumnTypeInferenceEnabled(): Boolean =
    getConf(SQLConf.PARTITION_COLUMN_TYPE_INFERENCE)

  def parallelPartitionDiscoveryThreshold: Int =
    getConf(SQLConf.PARALLEL_PARTITION_DISCOVERY_THRESHOLD)

  def bucketingEnabled: Boolean = getConf(SQLConf.BUCKETING_ENABLED)

  // Do not use a value larger than 4000 as the default value of this property.
  // See the comments of SCHEMA_STRING_LENGTH_THRESHOLD above for more information.
  def schemaStringLengthThreshold: Int = getConf(SCHEMA_STRING_LENGTH_THRESHOLD)

  def dataFrameEagerAnalysis: Boolean = getConf(DATAFRAME_EAGER_ANALYSIS)

  def dataFrameSelfJoinAutoResolveAmbiguity: Boolean =
    getConf(DATAFRAME_SELF_JOIN_AUTO_RESOLVE_AMBIGUITY)

  def dataFrameRetainGroupColumns: Boolean = getConf(DATAFRAME_RETAIN_GROUP_COLUMNS)

  def runSQLOnFile: Boolean = getConf(RUN_SQL_ON_FILES)

  def columnarAggregateMapEnabled: Boolean = getConf(COLUMNAR_AGGREGATE_MAP_ENABLED)

  def variableSubstituteEnabled: Boolean = getConf(VARIABLE_SUBSTITUTE_ENABLED)

  def variableSubstituteDepth: Int = getConf(VARIABLE_SUBSTITUTE_DEPTH)

  override def orderByOrdinal: Boolean = getConf(ORDER_BY_ORDINAL)

  override def groupByOrdinal: Boolean = getConf(GROUP_BY_ORDINAL)
  /** ********************** SQLConf functionality methods ************ */

  /** Set Spark SQL configuration properties. */
  def setConf(props: Properties): Unit = settings.synchronized {
    props.asScala.foreach { case (k, v) => setConfString(k, v) }
  }

  /** Set the given Spark SQL configuration property using a `string` value. */
  def setConfString(key: String, value: String): Unit = {
    require(key != null, "key cannot be null")
    require(value != null, s"value cannot be null for key: $key")
    val entry = sqlConfEntries.get(key)
    if (entry != null) {
      // Only verify configs in the SQLConf object
      entry.valueConverter(value)
    }
    setConfWithCheck(key, value)
  }

  /** Set the given Spark SQL configuration property. */
  def setConf[T](entry: ConfigEntry[T], value: T): Unit = {
    require(entry != null, "entry cannot be null")
    require(value != null, s"value cannot be null for key: ${entry.key}")
    require(sqlConfEntries.get(entry.key) == entry, s"$entry is not registered")
    setConfWithCheck(entry.key, entry.stringConverter(value))
  }

  /** Return the value of Spark SQL configuration property for the given key. */
  @throws[NoSuchElementException]("if key is not set")
  def getConfString(key: String): String = {
    Option(settings.get(key)).
      orElse {
        // Try to use the default value
        Option(sqlConfEntries.get(key)).map(_.defaultValueString)
      }.
      getOrElse(throw new NoSuchElementException(key))
  }

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue`. This is useful when `defaultValue` in ConfigEntry is not the
   * desired one.
   */
  def getConf[T](entry: ConfigEntry[T], defaultValue: T): T = {
    require(sqlConfEntries.get(entry.key) == entry, s"$entry is not registered")
    Option(settings.get(entry.key)).map(entry.valueConverter).getOrElse(defaultValue)
  }

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue` in [[ConfigEntry]].
   */
  def getConf[T](entry: ConfigEntry[T]): T = {
    require(sqlConfEntries.get(entry.key) == entry, s"$entry is not registered")
    Option(settings.get(entry.key)).map(entry.valueConverter).orElse(entry.defaultValue).
      getOrElse(throw new NoSuchElementException(entry.key))
  }

  /**
   * Return the value of an optional Spark SQL configuration property for the given key. If the key
   * is not set yet, throw an exception.
   */
  def getConf[T](entry: OptionalConfigEntry[T]): T = {
    require(sqlConfEntries.get(entry.key) == entry, s"$entry is not registered")
    Option(settings.get(entry.key)).map(entry.rawValueConverter).
      getOrElse(throw new NoSuchElementException(entry.key))
  }

  /**
   * Return the `string` value of Spark SQL configuration property for the given key. If the key is
   * not set yet, return `defaultValue`.
   */
  def getConfString(key: String, defaultValue: String): String = {
    val entry = sqlConfEntries.get(key)
    if (entry != null && defaultValue != "<undefined>") {
      // Only verify configs in the SQLConf object
      entry.valueConverter(defaultValue)
    }
    Option(settings.get(key)).getOrElse(defaultValue)
  }

  /**
   * Return all the configuration properties that have been set (i.e. not the default).
   * This creates a new copy of the config properties in the form of a Map.
   */
  def getAllConfs: immutable.Map[String, String] =
    settings.synchronized { settings.asScala.toMap }

  /**
   * Return all the configuration definitions that have been defined in [[SQLConf]]. Each
   * definition contains key, defaultValue and doc.
   */
  def getAllDefinedConfs: Seq[(String, String, String)] = sqlConfEntries.synchronized {
    sqlConfEntries.values.asScala.filter(_.isPublic).map { entry =>
      (entry.key, entry.defaultValueString, entry.doc)
    }.toSeq
  }

  private def setConfWithCheck(key: String, value: String): Unit = {
    if (key.startsWith("spark.") && !key.startsWith("spark.sql.")) {
      logWarning(s"Attempt to set non-Spark SQL config in SQLConf: key = $key, value = $value")
    }
    settings.put(key, value)
  }

  def unsetConf(key: String): Unit = {
    settings.remove(key)
  }

  private[spark] def unsetConf(entry: ConfigEntry[_]): Unit = {
    settings.remove(entry.key)
  }

  def clear(): Unit = {
    settings.clear()
  }
}

