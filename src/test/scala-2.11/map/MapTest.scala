package map

import state.test.{TestPropertyCellState, TestCellState}

object MapTest extends App {
  val numProperties = 50
  val startTime = System.currentTimeMillis()
  var testMap = CartesianSpaceMap(1000,1000,true)(TestPropertyCellState.testCellStateOps(numProperties))
  val mapCreatedTime = System.currentTimeMillis()
  implicit val stateDoubleValued = TestPropertyCellState.stateDoubleValued
  val testMapStats = MapStats(testMap)
  println(s"Initial map mean and std dev,: ${testMapStats.cellAverage}, ${testMapStats.cellStdDev}")
  //testMap.render
  val genStartTime = System.currentTimeMillis()
  val numGens = 100
  for(i <- 1 to numGens) {
    testMap = testMap.run

    //val genMapStats = MapStats(testMap)
    //println(s"Gen $i map mean and std dev,: ${genMapStats.cellAverage}, ${genMapStats.cellStdDev}")
    //println(s"Generation $i:")
    //testMap.render
  }
  val endTime = System.currentTimeMillis()
  val finalMapStats = MapStats(testMap)

  println(s"Map generated in ${mapCreatedTime-startTime}ms, $numGens gens in ${endTime-genStartTime}ms")

  println(s"Final map mean and std dev,: ${finalMapStats.cellAverage}, ${finalMapStats.cellStdDev}")
}
