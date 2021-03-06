package server.map

import model.{GameId, Span}
import model.map._
import model.property.{ScalarProperty, VectorProperty, VectorPropertyElement}
import org.http4s.{Request, Response}
import server.ApiHelper._
import server.properties.GamePropertyRegistry
import server.{Game, QueryParamHelper}
import state.property.{BasicSparseVectorProperty, ElementId, PropertyId}
import topology.space.CartesianCell

import scalaz.concurrent.Task
import scalaz.syntax.either._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scalaz.std.option._
import scalaz.syntax.foldable._
import scalaz.std.list._
import server.util.SpanOps._
import AreaOps._
import SimpleRegion._
import monocle.Lens
import server.player.User
import server.util.RectangleOps._

object Maps extends QueryParamHelper {
  val paramTopY = "topY"
  val paramBottomY = "bottomY"
  val paramLeftX = "leftX"
  val paramRightX = "rightX"

  val testGame = Game.buildTestGame

  def getMapData(req: Request, gameId: GameId, mapId: MapId): Task[Response] = {
    //  TODO - validate API key and infer identity
    join {
      for {
        maybeBounds <- validateBounds(req)
        user <- User.getCurrentUser(req)
        game <- Game.getGame(gameId)
        map <- game.getMap(mapId)
        mapView <- wrapM(user.maps.get(mapId) \/> notFound(s"Map '$mapId' not found"))
      } yield buildMapResponse(maybeBounds, map, mapView)
    }
  }

  def getMapMetadata(req: Request, gameId: GameId, mapId: MapId): Task[Response] = {
    join {
      for {
        user <- User.getCurrentUser(req)
        game <- Game.getGame(gameId)
        map <- game.getMap(mapId)
        mapView <- wrapM(user.maps.get(mapId) \/> notFound(s"Map '$mapId' not found"))
      } yield buildMapMetadataResponse(map, mapView)
    }
  }

  private def validateBounds(req: Request) = {
    for {
      maybeTopY <- optionalDouble(req, paramTopY)
      maybeBottomY <- optionalDouble(req, paramBottomY)
      maybeLeftX <- optionalDouble(req, paramLeftX)
      maybeRightX <- optionalDouble(req, paramRightX)
      bounds <-
        maybeTopY.flatMap(topY =>
          maybeBottomY.flatMap(bottomY =>
            maybeLeftX.flatMap(leftX =>
              maybeRightX.map(rightX => Rectangle(Point(leftX, topY), Point(rightX, bottomY)))))).fold(
          if (maybeTopY.isDefined || maybeBottomY.isDefined || maybeLeftX.isDefined || maybeRightX.isDefined)
            wrapM(badRequest(s"If bounds are specified they must be complete and specify all 4 bounding rectangle bounds").left[Option[Rectangle]])
          else successM(None:Option[Rectangle]))(r => successM(Some(r)))
      _ <- wrapM(bounds.fold(true)(r =>
        (r.topLeft.x < r.bottomRight.x) && (r.topLeft.y < r.bottomRight.y)) either
          () or
          badRequest(s"Bounds top left must be above and left of the bottom right"))
    } yield bounds
  }

  private def normalizeDimension(from: Double, max: Double) = {
    if ( from < 0 ) from + ((-from/max).toInt+1)*max
    else if (from > max) from - (from/max).toInt*max
    else from
  }

  private def normalizeRectDimension(r: Rectangle,
                                     max: Double,
                                     lens: Lens[Rectangle,Span]): SimpleRegion = {
    def normalizeRange(range: Span) = {
      if (range.size > max) {
        Span(range.from, range.from + max)
      }
      else range
    }

    val raw = normalizeRange(lens.get(r))
    val normalized = Span(normalizeDimension(raw.from,max), normalizeDimension(raw.to,max))

    if (normalized.from < normalized.to) SimpleRegion(List(lens.set(normalized)(r)))
    else
      SimpleRegion(List(
        lens.set(Span(0.0,normalized.to))(r),
        lens.set(Span(normalized.from, max))(r)
      ))
  }

  private def normalizeRegionDimension(r: SimpleRegion,
                                       max: Double,
                                       lens: Lens[Rectangle,Span]): SimpleRegion = {
    def normalizeRect(rect: Rectangle) = normalizeRectDimension(rect, max, lens)
    r.areas.map(normalizeRect).suml
  }

  private def buildMapMetadataResponse(map: GameMap, view: MapView) =
    MapMetadata(map.id.value, map.description, knownTopology(map.mapData.getMapTopology,view))

  private def buildMapResponse(maybeBounds: Option[Rectangle], map: GameMap, view: MapView) = {
    val topology = map.mapData.getMapTopology

    def boundsChecker(bounds: SimpleRegion)(cell: CartesianCell) = {
      val cellLocation = map.mapData.topology.cellCoordinates(cell).toPoint
      bounds.contains(cellLocation)
    }

    def makeBoundsList(rawBounds: Rectangle) = {
      normalizeRegionDimension(
        normalizeRegionDimension(
          SimpleRegion(List(rawBounds)),
          map.mapData.projectionWidth,
          Span.rectxLens),
        map.mapData.projectionHeight,
        Span.rectyLens
      )
    }

    def toScalarProperty(el: (PropertyId, Double)): ScalarProperty = {
      val property = GamePropertyRegistry.scalarProperties.getOrElse(el._1, throw new RuntimeException(s"Unknown scalar property ${el._1.value} encountered"))
      ScalarProperty(property.name, el._2)
    }

    def toVectorElement(el: (ElementId, Double)): VectorPropertyElement = {
      VectorPropertyElement(el._1.value, el._2)
    }

    def toVectorProperty(el: (PropertyId, BasicSparseVectorProperty[Double])): VectorProperty = {
      val property = GamePropertyRegistry.vectorProperties.getOrElse(el._1, throw new RuntimeException(s"Unknown vector property ${el._1.value} encountered"))
      VectorProperty(property.name, el._2.toList.map(toVectorElement))
    }

    //  Normalize into user visible frame ASSUMING the provided global coordinates
    //  do fall into that visible frame
    def toFrame(frame: Rectangle, p: Point) = {
      Point(
        (frame.topLeft.x > p.x) ?
          (p.x + map.mapData.projectionWidth) |
          ((frame.bottomRight.x < p.x) ?
            (p.x - map.mapData.projectionWidth) |
            p.x),
        (frame.topLeft.y > p.y) ?
          (p.y + map.mapData.projectionHeight) |
          ((frame.bottomRight.y < p.y) ?
            (p.y - map.mapData.projectionHeight) |
            p.y))
    }

    def toCellInfo(cell: CartesianCell) = {
      val cellState = map.mapData.cellStateValue(cell)
      val cellUserCoords =
        toFrame(
          view.visibleBounds,
          map.mapData.topology.cellCoordinates(cell).toPoint - view.origin)
      CellInfo(
        cellUserCoords,
        cellState.scalarProperties.toList.map(toScalarProperty),
        cellState.vectorProperties.toList.map(toVectorProperty))
    }

    val empty: Traversable[CartesianCell] = Nil
    val filteredCells =
      maybeBounds
        .fold(
          map.mapData.cells)(bounds =>
          bounds.intersect(view.visibleBounds).fold(empty)(clippedBounds =>
            map.mapData.cells.filter(boundsChecker(makeBoundsList(clippedBounds + view.origin)))))
    MapResponse(
      knownTopology(topology, view),
      Nil,
      filteredCells
        .map(toCellInfo)
        .toList
    )
  }

  private def knownTopology(t: MapTopology, view: MapView) = {
    MapTopology(
      t.baseType,
      t.xWrap.flatMap(d => (view.visibleBounds.xspan.size >= d) ? some(d) | None),
      t.yWrap.flatMap(d => (view.visibleBounds.yspan.size >= d) ? some(d) | None),
      t.cellSpacing
    )
  }
}
