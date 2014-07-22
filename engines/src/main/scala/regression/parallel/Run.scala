package io.prediction.engines.regression.parallel

import io.prediction.controller.Params
import io.prediction.controller.PDataSource
import io.prediction.controller.P2LAlgorithm
import io.prediction.controller.IdentityPreparator
import io.prediction.controller.AverageServing
import io.prediction.controller.MeanSquareError
import io.prediction.workflow.APIDebugWorkflow

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.regression.LinearRegressionWithSGD
import org.apache.spark.mllib.regression.RegressionModel
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD

case class DataSourceParams(
  val filepath: String, val k: Int = 3, val seed: Int = 9527)
extends Params

case class ParallelDataSource(val dsp: DataSourceParams)
  extends PDataSource[
      DataSourceParams, Integer, 
      RDD[LabeledPoint], Vector, Double] {
  def read(sc: SparkContext)
  : Seq[(Integer, RDD[LabeledPoint], RDD[(Vector, Double)])] = {
    val input = sc.textFile(dsp.filepath)
    val points = input.map { line =>
      val parts = line.split(' ').map(_.toDouble)
      LabeledPoint(parts(0), Vectors.dense(parts.drop(1)))
    }

    MLUtils.kFold(points, dsp.k, dsp.seed)
    .zipWithIndex
    .map { case (dataSet, index) =>
      (Int.box(index), dataSet._1, dataSet._2.map(p => (p.features, p.label)))
    }
  }
}

case class AlgorithmParams(
  val numIterations: Int = 200, val stepSize: Double = 0.1) extends Params

case class ParallelSGDAlgorithm(val ap: AlgorithmParams)
  extends P2LAlgorithm[
      AlgorithmParams, RDD[LabeledPoint], RegressionModel, Vector, Double] {

  def train(data: RDD[LabeledPoint]): RegressionModel = {
    LinearRegressionWithSGD.train(data, ap.numIterations, ap.stepSize)
  }

  def predict(model: RegressionModel, feature: Vector): Double = {
    model.predict(feature)
  }
}

object Run {
  def main(args: Array[String]) {
    val filepath = "data/lr_data.txt"
    val dataSourceParams = DataSourceParams(filepath, 3)
    val SGD = "SGD"
    val algorithmParamsList = Seq(
      (SGD, AlgorithmParams(stepSize = 0.1)),
      (SGD, AlgorithmParams(stepSize = 0.2)),
      (SGD, AlgorithmParams(stepSize = 0.4)))
    
    APIDebugWorkflow.run(
        dataSourceClassOpt = Some(classOf[ParallelDataSource]),
        dataSourceParams = dataSourceParams,
        preparatorClassOpt =
          Some(classOf[IdentityPreparator[RDD[LabeledPoint]]]),
        algorithmClassMapOpt = Some(Map(SGD -> classOf[ParallelSGDAlgorithm])),
        algorithmParamsList = algorithmParamsList,
        servingClassOpt = Some(AverageServing(classOf[ParallelSGDAlgorithm])),
        metricsClassOpt = Some(classOf[MeanSquareError]),
        batch = "Imagine: Parallel Regression")
  }
}





/****** TO FIX ******/

import org.json4s._

class VectorSerializer extends CustomSerializer[Vector](format => (
  {
    case JArray(x) =>
      val v = x.toArray.map { y =>
        y match {
          case JDouble(z) => z
        }
      }
      new DenseVector(v)
  },
  {
    case x: Vector =>
      JArray(x.toArray.toList.map(d => JDouble(d)))
  }
))