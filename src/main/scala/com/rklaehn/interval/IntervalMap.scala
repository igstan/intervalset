package com.rklaehn.interval

import com.rklaehn.sonicreducer.Reducer

import language.implicitConversions
import spire.algebra._
import spire.math.Interval
import spire.math.interval._
import spire.algebra.Order.ordering
import spire.implicits._

import scala.collection.AbstractTraversable
import scala.collection.immutable.SortedSet

sealed abstract class IntervalMap[K, V] { lhs ⇒
  import IntervalMap._

  def belowAll: V

  def apply(k: K)(implicit K: Order[K]): V

  def at(k: K)(implicit K: Order[K]): V

  def below(k: K)(implicit K: Order[K]): V

  def above(k: K)(implicit K: Order[K]): V

  def unary_~(implicit b: Bool[V]): IntervalMap[K, V] = IntervalMap.negate[K, V](this)

  def ^(rhs: IntervalMap[K, V])(implicit K: Order[K], V: Value[V]): IntervalMap[K, V] = IntervalMap.xor(lhs, rhs)

  def &(rhs: IntervalMap[K, V])(implicit K: Order[K], V: Value[V]): IntervalMap[K, V] = IntervalMap.and(lhs, rhs)

  def |(rhs: IntervalMap[K, V])(implicit K: Order[K], V: Value[V]): IntervalMap[K, V] = IntervalMap.or(lhs, rhs)

  def mapValues[V2: Eq](f: V => V2): IntervalMap[K, V2]

  /**
    * Allows traversing over all keys (places where values change)
    */
  def keys: Traversable[K]

  /**
    * Allows traversing over all values. Note that the number of values is not the same as the number of keys or entries
    * There will always be at least one value (belowAll)
    */
  def values: Traversable[V]

  def entries(implicit o: Order[K], v: Eq[V]): Traversable[(Interval[K], V)]
}

private[interval] trait IntervalMap0 {

  implicit def monoid[K: Order, V: Monoid: Eq]: Monoid[IntervalMap[K, V]] = new Monoid[IntervalMap[K, V]] {
    def id = IntervalMap.constant(Monoid[V].id)
    def op(x: IntervalMap[K, V], y: IntervalMap[K, V]) = IntervalMap.combine(x, y)
  }
}

object IntervalMap extends IntervalMap0 {

  implicit def eqv[K: Order, V: Eq]: Eq[IntervalMap[K, V]] = new Eq[IntervalMap[K, V]] {
    def eqv(x: IntervalMap[K, V], y: IntervalMap[K, V]) = x == y
  }

  implicit def bool[K: Order, V: Value: Bool] = new Bool[IntervalMap[K, V]] {
    def zero = IntervalMap.constant(Value[V].zero)
    def one = IntervalMap.constant(Bool[V].one)
    def complement(a: IntervalMap[K, V]) = ~a
    def or(a: IntervalMap[K, V], b: IntervalMap[K, V]) = a | b
    def and(a: IntervalMap[K, V], b: IntervalMap[K, V]) = a & b
    override def xor(a: IntervalMap[K, V], b: IntervalMap[K, V]) = a ^ b
  }

  implicit def group[K: Order, V: Group: Eq]: Group[IntervalMap[K, V]] = new Group[IntervalMap[K, V]] {
    def id = IntervalMap.constant(Monoid[V].id)
    def op(x: IntervalMap[K, V], y: IntervalMap[K, V]) = IntervalMap.combine(x, y)
    def inverse(a: IntervalMap[K, V]) = a.mapValues(Group[V].inverse)
    override def opInverse(a: IntervalMap[K, V], b: IntervalMap[K, V]) = IntervalMap.remove(a, b)
  }

  /**
    * This does not implement Eq so it does not interfere with the default Eq instance
    * @tparam V
    */
  trait Value[V] {

    def eqv(a: V, b: V): Boolean

    def isOne(x: V): Boolean

    def isZero(x: V): Boolean

    def zero: V

    def xor(a: V, b: V): V

    def and(a: V, b: V): V

    def or(a: V, b: V): V
  }

  object Value {

    implicit object booleanIsValue extends Value[Boolean] {

      def eqv(x: Boolean, y: Boolean) = x == y

      def zero = false

      def or(a: Boolean, b: Boolean) = a | b

      def and(a: Boolean, b: Boolean) = a & b

      def xor(a: Boolean, b: Boolean) = a ^ b

      def isZero(x: Boolean) = !x

      def isOne(x: Boolean) = x
    }

    implicit def setIsValue[T]: Value[Set[T]] = new Value[Set[T]] {

      def eqv(x: Set[T], y: Set[T]) = x == y

      val zero = Set.empty[T]

      def or(a: Set[T], b: Set[T]) = a union b

      def and(a: Set[T], b: Set[T]) = a intersect b

      def xor(a: Set[T], b: Set[T]) = (a diff b) union (b diff a)

      def isZero(x: Set[T]) = x.isEmpty

      def isOne(x: Set[T]) = false
    }

    implicit def sortedSetIsValue[T: Order]: Value[SortedSet[T]] = new Value[SortedSet[T]] {

      def eqv(x: SortedSet[T], y: SortedSet[T]) = x == y

      val zero = SortedSet.empty[T]

      def or(a: SortedSet[T], b: SortedSet[T]) = a union b

      def and(a: SortedSet[T], b: SortedSet[T]) = a intersect b

      def xor(a: SortedSet[T], b: SortedSet[T]) = (a diff b) union (b diff a)

      def isZero(x: SortedSet[T]) = x.isEmpty

      def isOne(x: SortedSet[T]) = false
    }

    implicit def boolAndEqIsValue[T](implicit e: Eq[T], bool: Bool[T]): Value[T] = new Value[T] {

      def zero = Bool[T].zero

      def or(a: T, b: T) = bool.or(a, b)

      def isOne(x: T) = e.eqv(x, bool.one)

      def isZero(x: T) = e.eqv(x, bool.zero)

      def and(a: T, b: T) = a & b

      def xor(a: T, b: T) = a ^ b

      def eqv(x: T, y: T) = e.eqv(x, y)
    }

    implicit def apply[V: Value]: Value[V] = implicitly[Value[V]]
  }

  private implicit def intervalsSetIsImpl[K, V](x: IntervalMap[K, V]): Impl[K, V] =
    x.asInstanceOf[Impl[K, V]]

  private final class Impl[K, V](
      val belowAll: V,
      val edges: Array[IntervalMap.Edge[K, V]]
  ) extends IntervalMap[K, V] {
    lhs ⇒

    // $COVERAGE-OFF$
    private def isCanonical(implicit v: Eq[V]): Boolean = {
      var current = belowAll
      for(edge ← edges) {
        if(v.eqv(current, edge.at) && v.eqv(edge.at, edge.above))
          return false
        current = edge.above
      }
      true
    }
    // $COVERAGE-ON$

    private def binarySearch(key: K)(implicit K: Order[K]): Int = {
      var low = 0
      var high = edges.length - 1
      while (low <= high) {
        val mid = (low + high) >>> 1
        val midVal = edges(mid).x
        val c = K.compare(midVal, key)
        if (c < 0) {
          low = mid + 1
        }
        else if (c > 0) {
          high = mid - 1
        }
        else {
          // scalastyle:off return
          return mid
          // scalastyle:on return
        }
      }
      -(low + 1)
    }

    private def belowIndex(index: Int)(implicit K: Order[K]): V =
      if (index == 0)
        belowAll
      else
        edges(index - 1).above

    def at(value: K)(implicit K: Order[K]): V = {
      val index = binarySearch(value)
      if (index >= 0)
        edges(index).at
      else
        belowIndex(-index - 1)
    }

    def above(value: K)(implicit K: Order[K]): V = {
      val index = binarySearch(value)
      if (index >= 0)
        edges(index).above
      else
        belowIndex(-index - 1)
    }

    def below(value: K)(implicit K: Order[K]): V = {
      val index = binarySearch(value)
      if (index > 0)
        edges(index - 1).above
      else if (index == 0)
        belowAll
      else
        belowIndex(-index - 1)
    }

    def apply(value: K)(implicit K: Order[K]) = at(value)

    override def equals(rhs: Any) = rhs match {
      case rhs: Impl[K, V] =>
        lhs.belowAll == rhs.belowAll && lhs.edges === rhs.edges
      case _ ⇒
        false
    }

    override def hashCode: Int =
      (belowAll.hashCode, edges.toIndexedSeq.hashCode).hashCode

    def format(implicit K: Order[K], v: Eq[V]): String =
      entries
        .map { case (k, v) => s"$k -> $v" }
        .mkString("IntervalMap(", ",", ")")

    override def toString: String = {
      val dummyOrder: Order[K] = new Order[K] { def compare(x: K, y: K) = 0 }
      val dummyEq: Eq[V] = new Eq[V] { def eqv(x: V, y: V) = false }
      format(dummyOrder, dummyEq)
    }

    def entries(implicit ev0: Order[K], ev1: Eq[V]) = new AbstractTraversable[(Interval[K], V)] {
      override def foreach[U](f: ((Interval[K], V)) => U): Unit = foreachInterval(f)
    }

    def values = new AbstractTraversable[V] {
      override def foreach[U](f: V => U): Unit = foreachValue(f)
    }

    def keys = new AbstractTraversable[K] {
      override def foreach[U](f: K => U): Unit = foreachKey(f)
    }

    private def foreachKey[U](f: K => U): Unit = {
      for(edge <- edges) {
        f(edge.x)
      }
    }

    private def foreachValue[U](f: V => U): Unit = {
      f(belowAll)
      for(edge <- edges) {
        f(edge.at)
        f(edge.above)
      }
    }

    private def foreachInterval[U](f: ((Interval[K], V)) => U)(implicit o: Order[K], v: Eq[V]): Unit = {
      var prevBound: Bound[K] = Unbound[K]()
      var prevValue: V = belowAll
      for(c <- edges) {
        val below = prevValue
        val at = c.at
        val above = c.above
        prevBound = (!v.eqv(below, at), !v.eqv(at, above)) match {
          case (true, true) ⇒
            // change both below and above. We have to produce a point
            f((Interval.fromBounds(prevBound, Open(c.x)), below))
            f((Interval.point(c.x), at))
            Open(c.x)
          case (true, false) ⇒
            // change just below. The preceding interval must be open
            f((Interval.fromBounds(prevBound, Open(c.x)), below))
            Closed(c.x)
          case (false, true) ⇒
            // change just above. The preceding interval must be closed
            f((Interval.fromBounds(prevBound, Closed(c.x)), below))
            Open(c.x)
          case (false, false) ⇒
            // no change at all. we should not ever get here with a valid set
            prevBound
        }
        prevValue = above
      }
      f((Interval.fromBounds(prevBound, Unbound()), prevValue))
    }

    def mapValues[V2: Eq](f: V => V2) = {
      val edgeBuilder = Array.newBuilder[Edge[K, V2]]
      edgeBuilder.sizeHint(edges.length)
      val Eq = implicitly[Eq[V2]]
      val belowAll1 = f(belowAll)
      var prev1 = belowAll1
      for(Edge(x, at, above) <- edges) {
        val at1 = f(at)
        val above1 = f(above)
        if(Eq.neqv(prev1, at1) || Eq.neqv(at1, above1))
          edgeBuilder += Edge(x, at1, above1)
        prev1 = above1
      }
      new Impl(belowAll1, edgeBuilder.result())
    }
  }

  def constant[K, V](value: V): IntervalMap[K, V] =
    IntervalMap(value, Array.empty[Edge[K, V]])

  object CreateFromBool {
    implicit def fromBoolOps(x: IntervalMap.type): FromBool.type = FromBool
  }

  object CreateFromMonoid {
    implicit def fromBoolOps(x: IntervalMap.type): FromMonoid.type = FromMonoid
  }

  object FromMonoid {

    def empty[K, V](implicit vb: Monoid[V]): IntervalMap[K, V] = constant(vb.id)

    def point[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(vv.id, x, value, vv.id)

    def hole[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(value, x, vv.id, value)

    def atOrAbove[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(vv.id, x, value, value)

    def above[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(vv.id, x, vv.id, value)

    def atOrBelow[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(value, x, value, vv.id)

    def below[K, V](x: K, value: V)(implicit vv: Monoid[V], ev: Eq[V]): IntervalMap[K, V] =
      step1(value, x, vv.id, vv.id)

    def apply[K: Order, V: Monoid: Eq](iv: (Interval[K], V)*): IntervalMap[K, V] = {
      val singles = iv.map { case (i, v) ⇒ apply(i, v) }
      val m = monoid[K, V]
      Reducer.reduce(singles)(m.op).getOrElse(empty)
    }

    def apply[K, V](interval: Interval[K], value: V)(implicit vv: Monoid[V], ve: Eq[V]): IntervalMap[K, V] = if (vv.isId(value)) empty
    else {
      interval.fold {
        case (Closed(a), Closed(b)) if a == b => point(a, value)
        case (Unbound(), Open(x)) => below(x, value)
        case (Unbound(), Closed(x)) => atOrBelow(x, value)
        case (Open(x), Unbound()) => above(x, value)
        case (Closed(x), Unbound()) => atOrAbove(x, value)
        case (Closed(a), Closed(b)) => step2(vv.id, a, value, value, b, value, vv.id)
        case (Closed(a), Open(b)) => step2(vv.id, a, value, value, b, vv.id, vv.id)
        case (Open(a), Closed(b)) => step2(vv.id, a, vv.id, value, b, value, vv.id)
        case (Open(a), Open(b)) => step2(vv.id, a, vv.id, value, b, vv.id, vv.id)
        case (Unbound(), Unbound()) => constant[K, V](value)
        case (EmptyBound(), EmptyBound()) => empty[K, V]
      }
    }
  }

  object FromBool {

    def zero[K, V](implicit vv: Value[V]): IntervalMap[K, V] =
      IntervalMap.constant(vv.zero)

    def one[K, V](implicit vb: Bool[V]): IntervalMap[K, V] =
      IntervalMap.constant(vb.one)

    def point[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(vv.zero, Array(Edge(x, value, vv.zero)))

    def hole[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(value, Array(Edge(x, vv.zero, value)))

    def atOrAbove[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(vv.zero, Array(Edge(x, value, value)))

    def above[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(vv.zero, Array(Edge(x, vv.zero, value)))

    def atOrBelow[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(value, Array(Edge(x, value, vv.zero)))

    def below[K, V](x: K, value: V)(implicit vv: Value[V]): IntervalMap[K, V] =
      if (vv.isZero(value)) zero
      else IntervalMap(value, Array(Edge(x, vv.zero, vv.zero)))

    def apply[K: Order, V: Value: Eq](iv: (Interval[K], V)*): IntervalMap[K, V] = {
      val singles = iv.map { case (i, v) ⇒ apply(i, v) }
      Reducer.reduce(singles)(_ | _).getOrElse(zero)
    }

    def apply[K, V](interval: Interval[K], value: V)(implicit vv: Value[V], ve: Eq[V]): IntervalMap[K, V] = if (vv.isZero(value)) zero
    else {
      interval.fold {
        case (Closed(a), Closed(b)) if a == b => point(a, value)
        case (Unbound(), Open(x)) => below(x, value)
        case (Unbound(), Closed(x)) => atOrBelow(x, value)
        case (Open(x), Unbound()) => above(x, value)
        case (Closed(x), Unbound()) => atOrAbove(x, value)
        case (Closed(a), Closed(b)) => step2(vv.zero, a, value, value, b, value, vv.zero)
        case (Closed(a), Open(b)) => step2(vv.zero, a, value, value, b, vv.zero, vv.zero)
        case (Open(a), Closed(b)) => step2(vv.zero, a, vv.zero, value, b, value, vv.zero)
        case (Open(a), Open(b)) => step2(vv.zero, a, vv.zero, value, b, vv.zero, vv.zero)
        case (Unbound(), Unbound()) => constant[K, V](value)
        case (EmptyBound(), EmptyBound()) => zero[K, V]
      }
    }
  }

  private[interval] def step1[K, V](belowA: V, a: K, atA: V, aboveA: V)(implicit Eq: Eq[V]): IntervalMap[K, V] =
    if(Eq.eqv(belowA, atA) && Eq.eqv(atA, aboveA))
      constant[K, V](belowA)
    else
      IntervalMap[K, V](belowA, Array(Edge(a, atA, aboveA)))

  private[interval] def step2[K, V](belowA: V, a: K, atA: V, aboveA: V, b: K, atB: V, aboveB: V)(implicit Eq: Eq[V]): IntervalMap[K, V] = {
    if(Eq.eqv(belowA, atA) && Eq.eqv(atA, aboveA))
      step1(aboveA, b, atB, aboveB)
    else if(Eq.eqv(aboveA, atB) && Eq.eqv(atB, aboveB))
      step1(belowA, a, atA, aboveA)
    else
      IntervalMap(belowA, Array(Edge(a, atA, aboveA), Edge(b, atB, aboveB)))
  }

  private def negate[K, V](x: Impl[K, V])(implicit b: Bool[V]): IntervalMap[K, V] = {
    val belowAll1 = ~x.belowAll
    val edges1 = x.edges.map(e ⇒ Edge(e.x, ~e.at, ~e.above))
    IntervalMap(belowAll1, edges1)
  }

  private def apply[K, V](belowAll: V, edges: Array[Edge[K, V]]) =
    new Impl[K, V](belowAll, edges)

  private[interval] case class Edge[K, V](x: K, at: V, above: V)

  private[interval] object Edge {

    implicit def eqv[K, V]: spire.algebra.Eq[Edge[K, V]] = spire.optional.genericEq.generic[Edge[K, V]]
  }

  private abstract class MergeOperation[K, V] {

    def vEqv(a: V, b: V): Boolean

    def kOrder: Order[K]

    def lhs: Impl[K, V]

    def rhs: Impl[K, V]

    def r0: V

    protected[this] val as = lhs.edges

    protected[this] val bs = rhs.edges

    protected[this] val rs = Array.ofDim[Edge[K, V]](as.length + bs.length)

    protected[this] var ri = 0

    def collision(ai: Int, bi: Int): Unit

    def fromA(a0: Int, a1: Int, b: Int): Unit

    def fromB(a: Int, b0: Int, b1: Int): Unit

    def binarySearch(array: Array[Edge[K, V]], key: K, from: Int, until: Int): Int = {
      var low = from
      var high = until - 1
      while (low <= high) {
        val mid = (low + high) >>> 1
        val midVal = array(mid)
        val c = kOrder.compare(midVal.x, key)
        if (c < 0) {
          low = mid + 1
        } else if (c > 0) {
          high = mid - 1
        } else {
          // scalastyle:off return
          return mid
          // scalastyle:on return
        }
      }
      -(low + 1)
    }

    def merge0(a0: Int, a1: Int, b0: Int, b1: Int): Unit = {
      if (a0 == a1) {
        if (b0 != b1)
          fromB(a0, b0, b1)
      } else if (b0 == b1) {
        fromA(a0, a1, b0)
      } else {
        val am = (a0 + a1) / 2
        val res = binarySearch(bs, as(am).x, b0, b1)
        if (res >= 0) {
          // same elements
          val bm = res
          // merge everything below a(am) with everything below the found element
          merge0(a0, am, b0, bm)
          // add the elements a(am) and b(bm)
          collision(am, bm)
          // merge everything above a(am) with everything above the found element
          merge0(am + 1, a1, bm + 1, b1)
        } else {
          val bm = -res - 1
          // merge everything below a(am) with everything below the found insertion point
          merge0(a0, am, b0, bm)
          // add a(am)
          fromA(am, am + 1, bm)
          // everything above a(am) with everything above the found insertion point
          merge0(am + 1, a1, bm, b1)
        }
      }
    }

    def add(c: Edge[K, V]): Unit = {
      rs(ri) = c
      ri += 1
    }

    def result: IntervalMap[K, V] = {
      merge0(0, as.length, 0, bs.length)
      val result = IntervalMap(r0, rs.take(ri))
      //      val canonical = result.isCanonical(vValue)
      //      if(!canonical)
      //        require(result.isCanonical(vValue))
      result
    }
  }

  private abstract class BinaryOp[K, V] extends MergeOperation[K, V] {

    val r0 = op(lhs.belowAll, rhs.belowAll)
    var aCurr = lhs.belowAll
    var bCurr = rhs.belowAll
    def rCurr = if (ri == 0) r0 else rs(ri - 1).above

    def op(a: V, b: V): V

    def edge(x: K, below: V, at: V, above: V): Unit = {
      if (!vEqv(below, at) || !vEqv(at, above)) {
        add(Edge(x, at, above))
      }
    }

    def collision(ai: Int, bi: Int) = {
      val a = as(ai)
      val b = bs(bi)
      edge(a.x, rCurr, op(a.at, b.at), op(a.above, b.above))
      aCurr = a.above
      bCurr = b.above
    }

    def fromA(a0: Int, a1: Int, b: Int) = {
      var i = a0
      while (i < a1) {
        val a = as(i)
        edge(a.x, rCurr, op(a.at, bCurr), op(a.above, bCurr))
        i += 1
      }
      aCurr = as(a1 - 1).above
    }

    def fromB(a: Int, b0: Int, b1: Int) = {
      var i = b0
      while (i < b1) {
        val b = bs(i)
        edge(b.x, rCurr, op(aCurr, b.at), op(aCurr, b.above))
        i += 1
      }
      bCurr = bs(b1 - 1).above
    }

    def copyA(a0: Int, a1: Int): Unit = {
      System.arraycopy(as, a0, rs, ri, a1 - a0)
      ri += a1 - a0
      aCurr = as(a1 - 1).above
    }

    def copyB(b0: Int, b1: Int): Unit = {
      System.arraycopy(bs, b0, rs, ri, b1 - b0)
      ri += b1 - b0
      bCurr = bs(b1 - 1).above
    }

  }

  private abstract class BooleanMergeOperation[K, V] extends BinaryOp[K, V] {
    def isZero(x: V): Boolean = vValue.isZero(x)

    def isOne(x: V): Boolean = vValue.isOne(x)

    def and(a: V, b: V): V = vValue.and(a, b)

    def or(a: V, b: V): V = vValue.or(a, b)

    def xor(a: V, b: V): V = vValue.xor(a, b)

    def vEqv(a: V, b: V) = vValue.eqv(a, b)

    def vValue: Value[V]
  }

  private def and[K: Order, V: Value](lhs: IntervalMap[K, V], rhs: IntervalMap[K, V]): IntervalMap[K, V] =
    new And[K, V](lhs, rhs).result

  private def or[K: Order, V: Value](lhs: IntervalMap[K, V], rhs: IntervalMap[K, V]): IntervalMap[K, V] =
    new Or[K, V](lhs, rhs).result

  private def xor[K: Order, V: Value](lhs: IntervalMap[K, V], rhs: IntervalMap[K, V]): IntervalMap[K, V] =
    new Xor[K, V](lhs, rhs).result

  private final class And[K, V](val lhs: Impl[K, V], val rhs: Impl[K, V])(implicit val kOrder: Order[K], val vValue: Value[V]) extends BooleanMergeOperation[K, V] {
    def op(a: V, b: V) =
      and(a, b)

    override def fromA(a0: Int, a1: Int, b: Int) = {
      if (isOne(bCurr))
        super.copyA(a0, a1)
      else if (!isZero(bCurr))
        super.fromA(a0, a1, b)
      else
        aCurr = as(a1 - 1).above
    }

    override def fromB(a: Int, b0: Int, b1: Int) = {
      if (isOne(aCurr))
        super.copyB(b0, b1)
      else if (!isZero(aCurr))
        super.fromB(a, b0, b1)
      else
        bCurr = bs(b1 - 1).above
    }
  }

  private final class Or[K, V](val lhs: Impl[K, V], val rhs: Impl[K, V])(implicit val kOrder: Order[K], val vValue: Value[V]) extends BooleanMergeOperation[K, V] {
    def op(a: V, b: V) =
      or(a, b)

    override def fromA(a0: Int, a1: Int, b: Int) = {
      if (isZero(bCurr))
        super.copyA(a0, a1)
      else if (!isOne(bCurr))
        super.fromA(a0, a1, b)
      else
        aCurr = as(a1 - 1).above
    }

    override def fromB(a: Int, b0: Int, b1: Int) = {
      if (isZero(aCurr))
        super.copyB(b0, b1)
      else if (!isOne(aCurr))
        super.fromB(a, b0, b1)
      else
        bCurr = bs(b1 - 1).above
    }
  }

  private final class Xor[K, V](val lhs: Impl[K, V], val rhs: Impl[K, V])(implicit val kOrder: Order[K], val vValue: Value[V]) extends BooleanMergeOperation[K, V] {
    def op(a: V, b: V) =
      xor(a, b)

    override def fromA(a0: Int, a1: Int, b: Int) = {
      if (isZero(bCurr))
        super.copyA(a0, a1)
      else
        super.fromA(a0, a1, b)
    }

    override def fromB(a: Int, b0: Int, b1: Int) = {
      if (isZero(aCurr))
        super.copyB(b0, b1)
      else
        super.fromB(a, b0, b1)
    }
  }

  private[interval] def combine[K: Order, V: Monoid: Eq](a: IntervalMap[K, V], b: IntervalMap[K, V]): IntervalMap[K, V] =
    new MonoidCombine[K, V](a, b).result

  private final class MonoidCombine[K, V](val lhs: Impl[K, V], val rhs: Impl[K, V])(implicit val kOrder: Order[K], val vMonoid: Monoid[V], vEq: Eq[V]) extends BinaryOp[K, V] {
    def op(a: V, b: V) = vMonoid.op(a, b)

    def vEqv(a: V, b: V) = vEq.eqv(a, b)

    def isId(a: V) = vEq.eqv(a, vMonoid.id)

    override def fromA(a0: Int, a1: Int, b: Int) = {
      if (isId(bCurr))
        super.copyA(a0, a1)
      else
        super.fromA(a0, a1, b)
    }

    override def fromB(a: Int, b0: Int, b1: Int) = {
      if (isId(aCurr))
        super.copyB(b0, b1)
      else
        super.fromB(a, b0, b1)
    }
  }

  private[interval] def remove[K: Order, V: Group: Eq](a: IntervalMap[K, V], b: IntervalMap[K, V]): IntervalMap[K, V] =
    new GroupRemove[K, V](a, b).result

  private final class GroupRemove[K, V](val lhs: Impl[K, V], val rhs: Impl[K, V])(implicit val kOrder: Order[K], val vGroup: Group[V], vEq: Eq[V]) extends BinaryOp[K, V] {
    def op(a: V, b: V) = vGroup.opInverse(a, b)

    def vEqv(a: V, b: V) = vEq.eqv(a, b)

    def isId(a: V) = vEq.eqv(a, vGroup.id)

    override def fromA(a0: Int, a1: Int, b: Int) = {
      if (isId(bCurr))
        super.copyA(a0, a1)
      else
        super.fromA(a0, a1, b)
    }
  }
}