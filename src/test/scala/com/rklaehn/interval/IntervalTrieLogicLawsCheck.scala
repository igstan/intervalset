package com.rklaehn.interval

import org.scalacheck.Properties
import spire.laws.LogicLaws

object IntervalTrieLogicLawsCheck extends Properties("IntervalTrie") with AddProperties {

  val algebra = IntervalTrie.algebra[Long]

  val arb = IntervalTrieArbitrary.arbIntervalTrie

  addProperties("LogicLaws", LogicLaws(algebra, arb).bool(algebra))
}
