package com._4paradigm.fesql.spark

import com._4paradigm.fesql.FeSqlLibrary
import com._4paradigm.fesql.`type`.TypeOuterClass._
import com._4paradigm.fesql.common.{SQLEngine, UnsupportedFesqlException}
import com._4paradigm.fesql.spark.nodes._
import com._4paradigm.fesql.spark.utils.FesqlUtil
import com._4paradigm.fesql.vm._
import org.apache.spark.sql.{DataFrame, SparkSession}

import org.apache.hadoop.fs.{FileSystem, Path}

import org.slf4j.LoggerFactory

import scala.collection.mutable


class SparkPlanner(session: SparkSession, config: FeSQLConfig) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Ensure native initialized
  FeSqlLibrary.initCore()
  Engine.InitializeGlobalLLVM()
  var node: PhysicalOpNode = _

  def this(session: SparkSession) = {
    this(session, FeSQLConfig.fromSparkSession(session))
  }

  def plan(sql: String, tableDict: Map[String, DataFrame]): SparkInstance = {
    // spark translation state
    val planCtx = new PlanContext(sql, session, this, config)

    // set spark input tables
    tableDict.foreach {
      case (name, df) => planCtx.registerDataFrame(name, df)
    }

    withSQLEngine(sql, FesqlUtil.getDatabase(config.configDBName, tableDict)) { engine =>
      val irBuffer = engine.getIRBuffer
      planCtx.setModuleBuffer(irBuffer)

      val root = engine.getPlan
      node = root
      logger.info("Get FeSQL physical plan: ")
      root.Print()

      if (config.slowRunCacheDir != null) {
        slowRunWithHDFSCache(root, planCtx, config.slowRunCacheDir, isRoot = true)
      } else {
        getSparkOutput(root, planCtx)
      }
    }
  }

  def getSparkOutput(root: PhysicalOpNode, ctx: PlanContext): SparkInstance = {
    val optCache = ctx.getPlanResult(root)
    if (optCache.isDefined) {
      return optCache.get
    }

    val children = mutable.ArrayBuffer[SparkInstance]()
    for (i <- 0 until root.GetProducerCnt().toInt) {
      children += getSparkOutput(root.GetProducer(i), ctx)
    }
    visitNode(root, ctx, children.toArray)
  }

  def visitNode(root: PhysicalOpNode, ctx: PlanContext, children: Array[SparkInstance]): SparkInstance = {
    val opType = root.GetOpType()
    opType match {
      case PhysicalOpType.kPhysicalOpDataProvider =>
        DataProviderPlan.gen(ctx, PhysicalDataProviderNode.CastFrom(root), children)
      case PhysicalOpType.kPhysicalOpSimpleProject =>
        SimpleProjectPlan.gen(ctx, PhysicalSimpleProjectNode.CastFrom(root), children)
      case PhysicalOpType.kPhysicalOpConstProject =>
        ConstProjectPlan.gen(ctx, PhysicalConstProjectNode.CastFrom(root))
      case PhysicalOpType.kPhysicalOpProject =>
        val projectNode = PhysicalProjectNode.CastFrom(root)
        projectNode.getProject_type_ match {
          case ProjectType.kTableProject =>
            RowProjectPlan.gen(ctx, PhysicalTableProjectNode.CastFrom(projectNode), children)

          case ProjectType.kWindowAggregation =>
            WindowAggPlan.gen(ctx, PhysicalWindowAggrerationNode.CastFrom(projectNode), children.head)

          case ProjectType.kGroupAggregation =>
            GroupByAggregationPlan.gen(ctx, PhysicalGroupAggrerationNode.CastFrom(projectNode), children.head)

          case _ => throw new UnsupportedFesqlException(
            s"Project type ${projectNode.getProject_type_} not supported")
        }
      case PhysicalOpType.kPhysicalOpGroupBy =>
        GroupByPlan.gen(ctx, PhysicalGroupNode.CastFrom(root), children.head)
      case PhysicalOpType.kPhysicalOpJoin =>
        JoinPlan.gen(ctx, PhysicalJoinNode.CastFrom(root), children.head, children.last)
      case PhysicalOpType.kPhysicalOpLimit =>
        LimitPlan.gen(ctx, PhysicalLimitNode.CastFrom(root), children.head)
      case PhysicalOpType.kPhysicalOpRename =>
        RenamePlan.gen(ctx, PhysicalRenameNode.CastFrom(root), children.head)
      //case PhysicalOpType.kPhysicalOpFilter =>
      //  FilterPlan.gen(ctx, PhysicalFilterNode.CastFrom(root), children.head)
      case _ =>
        throw new UnsupportedFesqlException(s"Plan type $opType not supported")
    }
  }


  /**
    * Run plan slowly by storing and loading each intermediate result from external data path.
    */
  def slowRunWithHDFSCache(root: PhysicalOpNode, ctx: PlanContext,
                           cacheDir: String, isRoot: Boolean): SparkInstance = {
    val sess = ctx.getSparkSession
    val fileSystem = FileSystem.get(sess.sparkContext.hadoopConfiguration)
    val rootKey = root.GetTypeName() + "_" + CoreAPI.GetUniqueID(root)

    val children = mutable.ArrayBuffer[SparkInstance]()
    for (i <- 0 until root.GetProducerCnt().toInt) {
      val child = root.GetProducer(i)
      val key = child.GetTypeName() + "_" + CoreAPI.GetUniqueID(child)
      logger.info(s"Compute $rootKey ${i}th child: $key")

      val cacheDataPath = cacheDir + "/" + key + "/data"
      val existCache = fileSystem.isDirectory(new Path(cacheDataPath)) &&
        fileSystem.exists(new Path(cacheDataPath + "/_SUCCESS"))
      val childResult = if (existCache) {
        logger.info(s"Load cached $key: $cacheDataPath")
        SparkInstance.fromDataFrame(sess.read.parquet(cacheDataPath))
      } else if (child.GetOpType() == PhysicalOpType.kPhysicalOpDataProvider) {
        visitNode(child, ctx, Array())
      } else {
        slowRunWithHDFSCache(child, ctx, cacheDir, isRoot=false)
      }
      children += childResult
    }
    logger.info(s"Schedule $rootKey")
    val rootResult = visitNode(root, ctx, children.toArray)
    if (isRoot) {
      return rootResult
    }
    val cacheDataPath = cacheDir + "/" + rootKey + "/data"
    logger.info(s"Store $rootKey: $cacheDataPath")
    rootResult.getDf(sess).write.parquet(cacheDataPath)

    logger.info(s"Reload $rootKey: $cacheDataPath")
    SparkInstance.fromDataFrame(sess.read.parquet(cacheDataPath))
  }

  private def withSQLEngine[T](sql: String, db: Database)(body: SQLEngine => T): T = {
    val engine = new SQLEngine(sql, db)
    val res = body(engine)
    engine.close()
    res
  }
}


