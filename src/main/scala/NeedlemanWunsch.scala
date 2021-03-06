package algorithm

import shapeless.nat._
import shapeless.ops.nat.Sum
import shapeless.syntax.typeable
import shapeless.Witness
import simulacrum._
import cats._
import shapeless.{ Nat, Succ }
import cats.implicits._
import higherkindness.droste._
import higherkindness.droste.data._
import breeze.linalg._
import scala.reflect.ClassTag
import breeze.storage.Zero
import matrices._

object NeedlemanWunsch {
  case class Alignment[A](left: List[A], right: List[A] )
  def findAlignments[Label: ClassTag: Eq](
      placeholder: Label,
      left: Array[Label],
      right: Array[Label]
    ): List[Alignment[Label]] = {
    val m = NeedlemanWunschMatrix( placeholder, left, right )
    val matrix = scoredMatrix( m )
    alignments(
      placeholder = placeholder,
      row = matrix.rows - 1,
      col = matrix.cols - 1,
      matrix = matrix,
      acc = List.empty[Alignment[Label]]
    )
  }

  private def alignments[A: ClassTag](
      placeholder: A,
      row: Int,
      col: Int,
      matrix: DenseMatrix[Scores[A]],
      acc: List[Alignment[A]]
    ): List[Alignment[A]] = {
    matrix( row, col ) match {
      case Corner( _ ) => acc
      case LeftBorder( item, score ) =>
        alignments(
          placeholder = placeholder,
          row = row - 1,
          col = col,
          matrix = matrix,
          acc = updateAccumulator( item, placeholder, acc )
        )
      case TopBorder( item, score ) =>
        alignments(
          placeholder = placeholder,
          row = row,
          col = col - 1,
          matrix = matrix,
          acc = updateAccumulator( placeholder, item, acc )
        )
      case InnerNode( rowHeader, colHeader, _, Neighbors( left, diag, top ) ) =>
        val scores = List( left, diag, top ).groupBy( _.score )
        val directions: List[Score] = scores.maxBy( _._1 )._2
        directions.flatMap {
          case Diag( _ ) =>
            alignments(
              placeholder = placeholder,
              row = row - 1,
              col = col - 1,
              matrix = matrix,
              acc = updateAccumulator( rowHeader, colHeader, acc )
            )
          case Top( _ ) =>
            alignments(
              placeholder = placeholder,
              row = row - 1,
              col = col,
              matrix = matrix,
              acc = updateAccumulator( rowHeader, placeholder, acc )
            )
          case Left( _ ) =>
            alignments(
              placeholder = placeholder,
              row = row,
              col = col - 1,
              matrix = matrix,
              acc = updateAccumulator( placeholder, colHeader, acc )
            )
        }
    }
  }

  private sealed trait Score {
    def score: Int
  }
  private case class Left(score: Int ) extends Score
  private case class Top(score: Int ) extends Score
  private case class Diag(score: Int ) extends Score
  private case class Neighbors(left: Left, diag: Diag, top: Top )
  private sealed trait Scores[+A] {
    def score: Int
  }
  private case class TopBorder[+A](item: A, score: Int ) extends Scores[A]
  private case class LeftBorder[+A](item: A, score: Int ) extends Scores[A]
  private case class Corner[+A](score: Int ) extends Scores[A]
  private case class InnerNode[+A](rowHeader: A, colHeader: A, score: Int, neighbors: Neighbors ) extends Scores[A]

  private def scores[A: Eq](m: NeedlemanWunschMatrix[A], row: Int, col: Int ): Scores[A] = {
    val rowHeader = m.rowLabels( row - 1 )
    val colHeader = m.colLabels( col - 1 )
    val left = m.matrix( row, col - 1 ) - 1
    val top = m.matrix( row - 1, col ) - 1
    val diff = if (colHeader === rowHeader) 1 else -1
    val diag = m.matrix( row - 1, col - 1 ) + diff
    val score = List( left, top, diag ).max
    m.matrix.update( row, col, score )
    InnerNode(
      rowHeader = rowHeader,
      colHeader = colHeader,
      score = score,
      neighbors = Neighbors( Left( left ), Diag( diag ), Top( top ) )
    )
  }

  private def scoredMatrix[A: Eq](m: NeedlemanWunschMatrix[A] ): DenseMatrix[Scores[A]] = {
    val updated: DenseMatrix[Scores[A]] =
      m.matrix( 1 until m.matrix.rows, 1 until m.matrix.cols ).mapPairs {
        case ( ( _row, _col ), value ) =>
          val row = _row + 1
          val col = _col + 1
          val s = scores( m, row, col )
          s
      }
    val scoredRowVector: DenseVector[Scores[A]] = m.columnVector.mapPairs {
      case ( 0, score )     => Corner( score )
      case ( index, score ) => LeftBorder( m.rowLabels( index - 1 ), score )
    }
    val scoredColVector: DenseVector[Scores[A]] = m.horizontalVector( 1 until m.horizontalVector.length ).mapPairs {
      case ( index, score ) => TopBorder( m.colLabels( index ), score )
    }
    val matrixWithCols = DenseMatrix.vertcat( scoredColVector.toDenseMatrix, updated )
    val matrixWithRows = DenseMatrix.horzcat( scoredRowVector.toDenseMatrix.t, matrixWithCols )
    matrixWithRows
  }
  private def updateAccumulator[A](row: A, col: A, acc: List[Alignment[A]] ): List[Alignment[A]] = {
    if (acc.isEmpty) List( Alignment( List( row ), List( col ) ) )
    else
      acc.map {
        case Alignment( left, right ) =>
          Alignment( row :: left, col :: right )
      }
  }
}
