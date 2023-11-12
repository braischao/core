package com.metabolic.data.core.services.spark.writer.file

import com.amazonaws.regions.Regions
import com.metabolic.data.core.services.glue.{AthenaCatalogueService, GlueCatalogService}
import com.metabolic.data.core.services.spark.writer.DataframeUnifiedWriter
import com.metabolic.data.core.services.util.ConfigUtilsService
import com.metabolic.data.mapper.domain.io.WriteMode
import com.metabolic.data.mapper.domain.io.WriteMode.WriteMode
import io.delta.tables._
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}

import scala.reflect.io.File

class DeltaWriter(val outputPath: String, val writeMode: WriteMode,
                  val dateColumnName: Option[String], val idColumnName: Option[String],
                  val checkpointLocation: String,
                  val dbName: String, val namespaces: Seq[String], val retention: Double = 168d) (implicit val region: Regions, implicit  val spark: SparkSession)

  extends DataframeUnifiedWriter {

    import io.delta.implicits._

    override val output_identifier: String = outputPath

    private val dateColumnNameDelta: String = dateColumnName.getOrElse("")
    private val idColumnNameDelta: String = idColumnName.getOrElse("")
    //var deltaTable: DeltaTable = null

    def upsertToDelta(df: DataFrame, batchId: Long = 1): Unit = {
      val mergeStatement = dateColumnNameDelta match {
        case "" =>
          s"output.${idColumnNameDelta} = updates.${idColumnNameDelta}"
        case _ =>
          s"output.${idColumnNameDelta} = updates.${idColumnNameDelta} AND" +
            s" output.${dateColumnNameDelta} = updates.${dateColumnNameDelta}"
      }
      DeltaTable.forPath(outputPath).as("output")
        .merge(
          df.as("updates"), mergeStatement
        )
        .whenMatched().updateAll()
        .whenNotMatched().insertAll()
        .execute()
    }
    override def writeBatch(df: DataFrame): Unit = {

      writeMode match {
        case WriteMode.Append => df
          .write
          .mode(SaveMode.Append)
          .option("overwriteSchema", "true")
          .option("mergeSchema", "true")
          .delta(outputPath)
        case WriteMode.Overwrite => df
          .write
          .mode(SaveMode.Overwrite)
          .option("overwriteSchema", "true")
          .option("mergeSchema", "true")
          .delta(outputPath)
        case WriteMode.Upsert =>
          //Append empty to force schema update then upsert
          spark.createDataFrame(spark.sparkContext.emptyRDD[Row], df.schema)
            .write
            .mode (SaveMode.Append)
            .option ("overwriteSchema", "true")
            .option ("mergeSchema", "true")
            .delta (outputPath)

          upsertToDelta(df)
      }

    }

    override def writeStream(df: DataFrame): StreamingQuery = {

      writeMode match {
        case WriteMode.Append => df
          .writeStream
          .format("delta")
          .outputMode("append")
          .option("mergeSchema", "true")
          .option("checkpointLocation", checkpointLocation)
          .start(output_identifier)
        case WriteMode.Overwrite => df
          .writeStream
          .format("delta")
          .outputMode("complete")
          .option("mergeSchema", "true")
          .option("checkpointLocation", checkpointLocation)
          .start(output_identifier)
        case WriteMode.Upsert => df
          .writeStream
          .format("delta")
          .foreachBatch(upsertToDelta _)
          .outputMode("append")
          .option("mergeSchema", "true")
          .option("checkpointLocation", checkpointLocation)
          .start(output_identifier)
        }

    }



  override def preHook(df: DataFrame): DataFrame = {

    val tableName: String = ConfigUtilsService.getTablePrefix(namespaces, output_identifier)+ConfigUtilsService.getTableNameFileSink(output_identifier)

    if (!DeltaTable.isDeltaTable(outputPath)) {
      if (!File(outputPath).exists) {
        //In this way a table is created which can be read but the schema is not visible.
        /*
        //Check if the database has location
        new GlueCatalogService()
          .checkDatabase(dbName, ConfigUtilsService.getDataBaseName(outputPath))
        //Create the delta table
        val deltaTable = DeltaTable.createIfNotExists()
          .tableName(dbName + "." + tableName)
          .location(output_identifier)
          .addColumns(df.schema)
          .partitionedBy(partitionColumnNames: _*)
          .execute()
        deltaTable.toDF.write.format("delta").mode(SaveMode.Append).save(output_identifier)
        //Create table in Athena (to see the columns)
        new AthenaCatalogueService()
          .createDeltaTable(dbName, tableName, output_identifier, true)*/

        //Redo if the other way works for us
        // create an empty RDD with original schema
        val emptyRDD = spark.sparkContext.emptyRDD[Row]
        val emptyDF = spark.createDataFrame(emptyRDD, df.schema)
        emptyDF
          .write
          .format("delta")
          .mode(SaveMode.Append)
          .save(output_identifier)
        //Create table in Athena
        new AthenaCatalogueService()
          .createDeltaTable(dbName, tableName, output_identifier)

      } else {
        //Convert to delta if parquet
        DeltaTable.convertToDelta(spark, s"parquet.`$outputPath`")
      }
    }
    df
  }

  override def postHook(df: DataFrame, query: Option[StreamingQuery]): Boolean = {

    query match {
      case stream: StreamingQuery =>
        stream.awaitTermination()
      case None =>
        val deltaTable = DeltaTable.forPath(outputPath)
        deltaTable.optimize().executeCompaction()
        deltaTable.vacuum(retention)
    }

    true
  }

}
