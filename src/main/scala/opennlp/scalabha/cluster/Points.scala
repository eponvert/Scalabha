package opennlp.scalabha.cluster

import pca_transform.PCA
import Jama.Matrix

/**
 * A simple representation of a point in some n-dimensional space.
 *
 * @param coord  A sequence of Doubles that define this point's coordinates
 *               in some space.
 */
case class Point(val coord: IndexedSeq[Double]) {
  import scala.math.sqrt

  // Zip the coordinates of this Point with those of another.
  def zip(that: Point) = this.coord.zip(that.coord)

  // Create a new Point formed by pairwise addition of the coordinates of this
  // Point with those of another.
  def ++(that: Point) = Point(this.zip(that).map { case (a, b) => a + b })

  // Create a new Point formed by pairwise subtraction of the coordinates of this
  // Point with those of another.
  def -(that: Point) = Point(this.zip(that).map { case (a, b) => a - b })

  // Create a new point that divides every value in this Point by a common
  // divisor.
  def /(divisor: Double) = Point(coord.map(_ / divisor))

  // Compute the dot product between this Point and another.
  def dotProduct(that: Point) = this.zip(that).map { case (x, y) => x * y }.sum

  // Create a new point formed by taking the absolute value of every element 
  // of this Point.
  lazy val abs = Point(coord.map(_.abs))

  // Compute the vector norm of this Point.
  lazy val norm = sqrt(this.dotProduct(this))

  // The number of elements in this Point.
  lazy val numDimensions = coord.length

  // The sum of all the values in this Point.
  lazy val sum = coord.sum

  // A terse String representation for the coordinates of this Point.
  override def toString = "[" + coord.mkString(",") + "]"
}

///////////////////////////////////////////////////////////////////////////////
// Distance functions
///////////////////////////////////////////////////////////////////////////////

/**
 * A trait for distance functions, which take two Points as arguments and
 * return a Double representing the distance between them.
 */
trait DistanceFunction extends ((Point, Point) => Double)

/**
 * A companion object to the DistanceFunction trait that helps select the
 * DistanceFunction corresponding to each string description.
 */
object DistanceFunction {
  def apply(description: String) = description match {
    case "cosine" => CosineDistance
    case "manhattan" => ManhattanDistance
    case "euclidean" => EuclideanDistance
    case _ => throw new MatchError("Invalid distance function: " + description)
  }
}

/**
 * Compute the cosine distance between two points. Note that it is a distance
 * because we subtract the cosine similarity from one.
 */
object CosineDistance extends DistanceFunction {
  def apply(x: Point, y: Point) = 1 - x.dotProduct(y) / (x.norm * y.norm)
}

/**
 * Compute the Manhattan (city-block) distance between two points.
 */
object ManhattanDistance extends DistanceFunction {
  def apply(x: Point, y: Point) = (x - y).abs.sum
}

/**
 * Compute the Euclidean distance between two points.
 */
object EuclideanDistance extends DistanceFunction {
  def apply(x: Point, y: Point) = (x - y).norm
}

///////////////////////////////////////////////////////////////////////////////
// Transformation functions
///////////////////////////////////////////////////////////////////////////////

/**
 * A trait for functions that transform a set of points from one space to
 * another space that is potentially scaled, reduced, or both.
 */
trait PointTransformer extends (IndexedSeq[Point] => IndexedSeq[Point])

/**
 * A companion object to the PointTransformer trait to retrieve the point
 * transformation functions corresponding to the given description.
 */
object PointTransformer {
  def apply(description: String, points: IndexedSeq[Point]) = description match {
    case "ident" => new IdentityTransformer
    case "zscore" => ZscoreTransformer(points)
    case "pca" => PcaTransformer(points)
    case _ => throw new MatchError("Invalid transformer type: " + description)
  }
}

/**
 * A point transformer that simply returns the points given to it. The purpose
 * of this is for programming convenience so that one can select from a set of
 * possible transformers, including doing no transformation at all. (Without
 * it, it is necessary to use conditional statements to sometimes transform
 * points, and other times not.)
 */
class IdentityTransformer extends PointTransformer {
  def apply(points: IndexedSeq[Point]) = points
}

/**
 * A class for objects that transform Points to and from z-score values based
 * on means and standard deviations in each dimension. For details see:
 *
 * http://en.wikipedia.org/wiki/Standard_score
 *
 * @param means  A sequence of Doubles, each of which is the mean value for the
 *               corresponding dimension.
 * @param standardDeviations  A sequence of Doubles, each of which is the
 *               standard deviation computed from the values for a given
 *               dimension.
 */
class ZscoreTransformer(
  means: IndexedSeq[Double], standardDeviations: IndexedSeq[Double])
  extends PointTransformer {

  /**
   * Transform a sequence of point to their z-score values, thereby scaling
   * the dataset.
   */
  def apply(points: IndexedSeq[Point]): IndexedSeq[Point] = {
    points.map { point =>
      val transformed = point.coord.zip(means.zip(standardDeviations)).map {
        case (x, (mean, sdev)) => (x - mean) / sdev
      }
      Point(transformed)
    }
  }

}

/**
 * Companion object that computes means and standard deviations to
 * construct a ZscoreTransformer.
 */
object ZscoreTransformer {

  /**
   * Given a set of points, compute the means and standard deviations
   * for each dimension, and construct a ZscoreTransformer from them.
   *
   * @param points The set of points to use as the basis for scaling.
   * @return The ZscoreTransformer using the computed means and standard
   *         deviations.
   */
  def apply(points: IndexedSeq[Point]): ZscoreTransformer = {
    val tpoints = points.map(_.coord).transpose
    val means = tpoints.map(values => values.sum / values.length)
    val standardDeviations = tpoints.zip(means).map {
      case (values, mean) =>
        val squaredDifferences = values.map(v => square(v - mean))
        if (squaredDifferences == 0.0) 1.0
        else math.sqrt(squaredDifferences.sum)
    }
    new ZscoreTransformer(means, standardDeviations)
  }

  // Simple helper function to get the square of a Double.
  private def square = (x: Double) => x * x
}

/**
 * A transformer that scales a set of points, maps them into a PCA space,
 * and then reduces the dimensionality by retaining only the top components.
 *
 * @param pca A PCA object that holds the data structures needed for mapping
 *            points to the PCA space.
 * @param scaler A point transformer that will scale the points before being
 *               passed on to the PCA. For now, only a ZscoreTransformer is
 *               used.
 * @param numComponents The number of principal components to keep. Components
 *                      are ranked according to the amount of variance they
 *                      explain, so the most important dimensions are kept.
 */
class PcaTransformer(
  pca: PCA,
  scaler: ZscoreTransformer,
  numComponents: Int) extends PointTransformer {

  /**
   * Transform a sequence of point to their z-score values, thereby scaling
   * the dataset, and then transform them into principal components space, and
   * finally, reduce their dimensionality by taking the top dimensions.
   */
  def apply(points: IndexedSeq[Point]): IndexedSeq[Point] = {
    val scaledPoints = scaler(points)
    val pointMatrix = new Matrix(scaledPoints.map(_.coord.toArray).toArray)
    val transformed = pca.transform(pointMatrix, PCA.TransformationType.ROTATION)
    transformed.getArray.map { transformedCoord =>
      Point(transformedCoord.take(numComponents).toIndexedSeq)
    }
  }

}
/**
 * Companion object that constructs a ZscoreTransformer from the given points,
 * and then computes the principal components from the scaled points. It
 * computes the number of dimensions needed to explain 95% of the variance and
 * uses this to set the PcaTransformer's numComponents value for reducing
 * dimensionality.
 */
object PcaTransformer {

  /**
   * Given a set of points, compute the ZscoreTransformer and then a
   * PcaTransformer and its cutoff.
   *
   * @param points The set of points to use as the basis for scaling.
   * @return The PcaTransformer using the computed ZscoreTransformer and the
   *         PCA object computed from the scaled points and the 95% cutoff
   *         value.
   */
  def apply(points: IndexedSeq[Point]) = {

    // First scale the points.
    val scaler = ZscoreTransformer(points)
    val scaledPoints = scaler(points)

    // Compute the PCA from the scaled points.
    val pca = new PCA(new Matrix(scaledPoints.map(_.coord.toArray).toArray))

    // Figure out how many components are needed to explain 95% of the variance.
    val eigVals = (0 until pca.getOutputDimsNo).map(pca.getEigenvalue(_))
    val eigValsSq = eigVals.map(x => x * x)
    val propVariance = eigValsSq.map(_ / eigValsSq.sum)
    val numComponents = propVariance.scan(0.0)(_ + _).indexWhere(.95<)

    // Create the PCA Transformer
    new PcaTransformer(pca, scaler, numComponents)
  }
}

