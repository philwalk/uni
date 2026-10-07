package uni.apps

import uni.data.NumPyRNG

/** A READ'S PRIMITIVE FORMS: each reading the read computes on arrays, by selection or at the
  * sessions a row looks at is its definition's to the bit -- the quantile a sort indexes, the
  * trailing rank of the whole series, the volatility exit of the day list, and the sector rows of
  * the panel's sequences, blank months included. */
class ReadFormsSuite extends munit.FunSuite:

  private def same(a: Double, b: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(a) == java.lang.Double.doubleToRawLongBits(b) || (a.isNaN && b.isNaN)

  private def assertSame(a: Double, b: Double, clue: => String): Unit =
    assert(same(a, b), s"$clue: $a vs $b")

  test("a quantile by selection is the sorted series' to the bit"):
    val specials = Array(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, 0.0, -0.0, 1.0, -1.0,
                         1e-300, -1e-300)
    val qs = Array(0.0, 0.1, 0.1, 0.5, 0.9, 0.999, 1.0)
    for seed <- 1 to 40; n <- Vector(0, 1, 2, 3, 10, 51, 300) do
      val rng = new NumPyRNG(seed * 7919L + n)
      val x = Array.fill(n):
        rng.nextBoundedInt(10) match
          case 0 | 1 => specials(rng.nextBoundedInt(9))
          case 2 | 3 => rng.nextBoundedInt(5) - 2.0
          case _     => rng.randn()
      val s = MarketSim.finiteSorted(x)
      val got = MarketSim.finitePctiles(x, qs)
      for (q, j) <- qs.zipWithIndex do
        assertSame(got(j), MarketSim.pctileOf(s, q), s"seed $seed n $n q $q")
        assertSame(MarketSim.pctile(x.toSeq, q), MarketSim.pctileOf(s, q), s"seed $seed n $n q $q")
    val flat = Array.fill(20000)(0.5)
    val ramp = Array.tabulate(20000)(_.toDouble)
    for v <- Vector(flat, ramp, ramp.reverse) do
      val got = MarketSim.finitePctiles(v, Array(0.1, 0.5, 0.9))
      val s = MarketSim.finiteSorted(v)
      assertEquals(got.toVector, Vector(0.1, 0.5, 0.9).map(MarketSim.pctileOf(s, _)))

  test("a trailing rank at one session is the whole series' there"):
    val rng = new NumPyRNG(17L)
    val x = Array.fill(700):
      rng.nextBoundedInt(12) match
        case 0 => Double.NaN
        case 1 => -0.0
        case 2 => 0.0
        case _ => math.rint(rng.randn() * 4.0) / 4.0
    for win <- Vector(1, 5, 52, 252) do
      val all = MarketSim.trailingRank(x, win)
      for i <- x.indices do
        assertSame(MarketSim.trailingRankAt(x, win, i), all(i), s"win $win session $i")

  test("a path's volatility exit read off its arrays is its day list's"):
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    for world <- Vector(w, w.copy(divYield = 2.0)); seed <- Vector(3L, 11L) do
      val p = MarketSim.simulate(world, 12, seed)
      val want = MarketSim.volExitOf(MarketSim.volExitDaysOfPath(p))
      val got  = MarketSim.volExitOfPath(p)
      assertEquals(got.length, want.length)
      got.zip(want).foreach((a, b) => assertSame(a, b, s"seed $seed div ${world.divYield}"))
    val short = MarketSim.simulate(w, 1, 5L)
    assert(MarketSim.volExitOfPath(short).forall(_.isNaN), "under a year after the window")

  /** The sector rows as the panel's sequences defined them. */
  private object Ref:
    def cumSimple(r: Seq[Double]): Double = r.foldLeft(1.0)((acc, x) => acc * (1.0 + x)) - 1.0
    def meanOf(v: Seq[Double]): Double = v.foldLeft(0.0)(_ + _) / v.length
    def sd1(v: Seq[Double]): Double =
      val m = meanOf(v)
      math.sqrt(v.foldLeft(0.0)((a, x) => a + (x - m) * (x - m)) / (v.length - 1))
    def pearsonOf(x: Seq[Double], y: Seq[Double]): Double =
      val mx = meanOf(x); val my = meanOf(y)
      var sxy = 0.0; var sxx = 0.0; var syy = 0.0
      var i = 0
      while i < x.length do
        sxy += (x(i) - mx) * (y(i) - my); sxx += (x(i) - mx) * (x(i) - mx); syy += (y(i) - my) * (y(i) - my)
        i += 1
      sxy / math.sqrt(sxx * syy)
    def linearPctile(sorted: Array[Double], q: Double): Double =
      if sorted.isEmpty then Double.NaN
      else
        val pos = q * (sorted.length - 1)
        val lo  = math.floor(pos).toInt
        val hi  = math.min(lo + 1, sorted.length - 1)
        sorted(lo) + (pos - lo) * (sorted(hi) - sorted(lo))

    def momentum(p: MarketSim.SectorPanel, form: Int, top: Int, from: Int): Vector[Double] =
      val months = p.market.length
      val spreads = Vector.newBuilder[Double]
      for t <- math.max(form + 1, from) until months do
        val lo  = t - form - 1
        val mkt = cumSimple(p.market.slice(lo, t - 1))
        val scored = p.returns.flatMap { ind =>
          val window = ind.slice(lo, t - 1)
          if ind(t).isDefined && window.forall(_.isDefined) then Some((cumSimple(window.map(_.get)) - mkt, ind(t).get))
          else None
        }
        if scored.length >= 2 * top then
          val sorted = scored.sortWith((a, b) => a._1 > b._1)
          spreads += meanOf(sorted.take(top).map(_._2)) - meanOf(sorted.drop(sorted.length - top).map(_._2))
      val s = spreads.result()
      val mean = meanOf(s)
      Vector(mean, mean / (sd1(s) / math.sqrt(s.length.toDouble)), s.count(_ > 0.0).toDouble / s.length) ++ s

    def trend(p: MarketSim.SectorPanel, sign12: Boolean, from: Int): (Double, Int) =
      val months = p.market.length
      val look = if sign12 then 12 else 10
      val pairs = p.returns.map { ind =>
        val px = Array.fill(months)(Double.NaN)
        var level = 1.0
        for k <- 0 until months do
          ind(k) match
            case Some(x) => level *= 1.0 + x; px(k) = level
            case None    => level = 1.0
        (math.max(look, from) until months).flatMap { t =>
          val window = ind.slice(t - look, t)
          if ind(t).isEmpty || !window.forall(_.isDefined) then None
          else
            val signal =
              if sign12 then Some(cumSimple(window.map(_.get)) > cumSimple(p.rf.slice(t - look, t)))
              else
                val pxw = px.slice(t - look, t)
                if pxw.exists(_.isNaN) then None else Some(px(t - 1) > meanOf(pxw.toSeq))
            signal.map(sg => (sg, ind(t).get - p.rf(t)))
        }.toVector
      }
      val per = pairs.flatMap { v =>
        val pos = v.filter(_._1).map(_._2)
        val neg = v.filterNot(_._1).map(_._2)
        if pos.nonEmpty && neg.nonEmpty then Some(meanOf(pos) - meanOf(neg)) else None
      }
      (meanOf(per), pairs.map(_.length).maxOption.getOrElse(0))

    def shape(p: MarketSim.SectorPanel): (Vector[Double], Vector[Int]) =
      val months = p.market.length
      val cs = (0 until months).flatMap { t =>
        val v = p.returns.flatMap(_(t))
        if v.length >= 2 then Some(sd1(v)) else None
      }.toVector
      val sorted = MarketSim.finiteSorted(p.market.toArray)
      val p10 = linearPctile(sorted, 0.10); val p45 = linearPctile(sorted, 0.45); val p55 = linearPctile(sorted, 0.55)
      val all    = (0 until months).toVector
      val worst  = all.filter(t => p.market(t) <= p10)
      val middle = all.filter(t => p.market(t) > p45 && p.market(t) < p55)
      def corrOver(ts: Vector[Int]): Double =
        val c = Vector.newBuilder[Double]
        for a <- p.returns.indices; b <- a + 1 until p.returns.length do
          val both = ts.flatMap(t => for (u <- p.returns(a)(t); v <- p.returns(b)(t)) yield (u, v))
          if both.length >= MarketSim.SectorCorrMinMonths then c += pearsonOf(both.map(_._1), both.map(_._2))
        linearPctile(MarketSim.finiteSorted(c.result().toArray), 0.5)
      (Vector(meanOf(cs), corrOver(all), corrOver(worst), corrOver(middle)), Vector(cs.length, worst.length, middle.length))

  test("the sector rows read on columns are the panel's sequences', blank months included"):
    for seed <- 1L to 6L do
      val rng = new NumPyRNG(seed)
      val months = 900
      // scattered blank months, and one industry blank for a stretch, which restarts its index
      val returns = Vector.tabulate(7): j =>
        Vector.tabulate(months): t =>
          val blank = rng.nextBoundedInt(30) == 0 || (j == 0 && t >= 300 && t < 340)
          if blank then None else Some(0.05 * rng.randn())
      val p = MarketSim.SectorPanel(returns, Vector.fill(months)(0.04 * rng.randn()),
                                    Vector.fill(months)(0.003 * rng.nextDouble()))
      val m = MarketSim.sectorMomentum(p, 11, 2, 0)
      val mRef = Ref.momentum(p, 11, 2, 0)
      assert(m.spreads.length > 100, s"momentum months ${m.spreads.length}")
      (Vector(m.mean, m.t, m.sharePositive) ++ m.spreads).zip(mRef).foreach((a, b) => assertSame(a, b, s"momentum $seed"))
      assertEquals(m.spreads.length, mRef.length - 3)
      for (mode, sign12) <- Vector((MarketSim.SectorTrend.Sign12, true), (MarketSim.SectorTrend.Sma10, false)) do
        val (v, n)       = MarketSim.sectorTrend(p, mode, 0)
        val (vRef, nRef) = Ref.trend(p, sign12, 0)
        assertSame(v, vRef, s"trend $mode $seed")
        assertEquals(n, nRef)
        assert(v.isFinite && n > 100, s"trend $mode $seed: $v over $n")
      val (sh, ns)       = MarketSim.sectorShape(p)
      val (shRef, nsRef) = Ref.shape(p)
      assert(sh.forall(_.isFinite), s"shape $seed: $sh")
      sh.zip(shRef).foreach((a, b) => assertSame(a, b, s"shape $seed"))
      assertEquals(ns, nsRef)

  test("a path's sector readings are its panel's"):
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe")).copy(sectors = 10)
    for seed <- Vector(3L, 11L) do
      val s = MarketSim.simulate(w, 30, seed)
      val p = MarketSim.sectorPanelOf(s)
      val top = math.max((s.sectors.length * 3 + 5) / 10, 1)
      val m = MarketSim.sectorMomentum(p, 11, top, 0)
      val (sh, _) = MarketSim.sectorShape(p)
      val want = Vector(m.mean, m.t, m.sharePositive, MarketSim.sectorTrend(p, MarketSim.SectorTrend.Sign12, 0)._1,
                        MarketSim.sectorTrend(p, MarketSim.SectorTrend.Sma10, 0)._1, sh(0),
                        MarketSim.sectorMarketSd(p), sh(1), sh(2), sh(3))
      MarketSim.sectorPathStats(s).zip(want).foreach((a, b) => assertSame(a, b, s"seed $seed"))
