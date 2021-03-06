package com.azavea.run

import com.azavea._

import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.cog.COGLayerStorageMetadata
import geotrellis.spark.io.s3._
import geotrellis.spark.io.s3.cog._
import geotrellis.vector.Extent
import cats.effect.IO
import cats.implicits._
import com.amazonaws.services.s3.AmazonS3URI
import spire.syntax.cfor._
import org.apache.spark.SparkContext

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object COGBench extends Bench {
  def availableZoomLevels(path: String)(name: String): List[Int] = {
    val s3Path = new AmazonS3URI(path)
    val attributeStore = S3AttributeStore(s3Path.getBucket, s3Path.getKey)
    attributeStore.availableZoomLevels(name).toList
  }

  def runValueReader(path: String)(name: String, zoomLevels: List[Int], extent: Option[Extent] = None, threads: Int = 16)(implicit sc: SparkContext): Logged = {
    val pool = Executors.newFixedThreadPool(threads)
    implicit val ec = ExecutionContext.fromExecutor(pool)

    val s3Path = new AmazonS3URI(path)
    val attributeStore = S3AttributeStore(s3Path.getBucket, s3Path.getKey)

    val COGLayerStorageMetadata(cogLayerMetadata, _) = attributeStore.readMetadata[COGLayerStorageMetadata[SpatialKey]](LayerId(name, 0))

    val layersData: List[(LayerId, KeyBounds[SpatialKey], TileLayerMetadata[SpatialKey])] =
      zoomLevels
        .map(LayerId(name, _))
        .map { layerId =>
          val metadata = cogLayerMetadata.tileLayerMetadata(layerId.zoom)
          (layerId, metadata.bounds match { case kb: KeyBounds[SpatialKey] => kb }, metadata)
        }

    val valueReader = new S3COGValueReader(attributeStore)

    val res: IO[List[(Long, Long)]] =
      layersData
        .map { case (layerId, kb, metadata) =>
          IO.shift(ec) *> IO {
            val gb @ GridBounds(minCol, minRow, maxCol, maxRow) = extent.map(metadata.mapTransform.extentToBounds).getOrElse(kb.toGridBounds)
            val reader = valueReader.reader[SpatialKey, MultibandTile](layerId)

            val (time, _) = timedCreateLong {
              cfor(minCol)(_ < maxCol, _ + 1) { col =>
                cfor(minRow)(_ < maxRow, _ + 1) { row =>
                  try {
                    reader.read(SpatialKey(col, row)) // skip all errors
                  } catch { case _ => }
                }
              }
            }

            (time, gb.size)
          }
        }
        .parSequence

    for {
      _ <- {
        val calculated = res.unsafeRunSync()
        pool.shutdown()
        val averageTime = calculated.map(_._1).sum / calculated.length
        val averageCount = calculated.map(_._2).sum / calculated.length

        ((calculated
          .toVector
          .map { case (time, size) => s"COGBench.runValueReader:: ${"%,d".format(time / size)} ms" }
          :+ s"COGBench.runValueReader:: total time: ${averageTime} ms"
          :+ s"COGBench.runValueReader:: avg number of tiles: ${averageCount}")
          ++ zoomLevels.toVector.map(zoom => s"COGBench.runValueReader:: zoom levels: $zoom")
        ).tell
      }
    } yield ()
  }


  def runLayerReader(path: String)(name: String, zoomLevels: List[Int], extent: Option[Extent] = None)(implicit sc: SparkContext): Logged = {
    val s3Path = new AmazonS3URI(path)
    val attributeStore = S3AttributeStore(s3Path.getBucket, s3Path.getKey)

    val layersData: List[LayerId] = zoomLevels.map(LayerId(name, _))
    val layerReader = new S3COGLayerReader(attributeStore)

    val res: IO[List[(Long, Long)]] =
      layersData
        .map { layerId =>
          IO {
            timedCreateLong {
              extent match {
                case Some(ext) =>
                  layerReader
                    .query[SpatialKey, MultibandTile](layerId)
                    .where(Intersects(ext))
                    .result
                    .count()
                case _ =>
                  layerReader
                    .read[SpatialKey, MultibandTile](layerId)
                    .count()
              }
            }
          }
        }
        .sequence

    for {
      _ <- {
        val calculated = res.unsafeRunSync()
        val averageTime = calculated.map(_._1).sum / calculated.length
        val averageCount = calculated.map(_._2).sum / calculated.length
        (Vector(
          s"COGBench.runLayerReader:: avg number of tiles: ${averageCount}",
          s"COGBench.runLayerReader:: ${"%,d".format(averageTime)} ms"
        ) ++ zoomLevels.toVector.map(zoom => s"COGBench.runLayerReader:: zoom levels: $zoom")).tell
      }
    } yield ()
  }
}
