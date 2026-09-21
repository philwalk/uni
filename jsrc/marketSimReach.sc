#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.0
//> using dep org.vastblue:uni_3:0.24.4

// THE REACHABILITY CHECK for the market simulator: can one small step of the searched dials move
// the rows a world misses toward their bands without pushing any other row out of its band?  The
// Scala twin of `rust/src/bin/market_sim_reach.rs`: same flags, same report, byte for byte.  It
// reads the PUBLISHED model, like the search harness: publish before checking parity.
//
//   jsrc/marketSimReach.sc -at 0.24.4-nasdaq -gap "kurtosis,up-day share %"
//
// THE DIAL RESPONSE is read on SHARED SEEDS.  Each dial is stepped one search sigma (`-sigma` of
// its width, in the search's coordinates: ln(1 + x) for the rate dials) and read on the same seeds
// as the base, so the difference cancels the paths' own noise.  Measured on 0.24.4-nasdaq at
// 60 x 80, a one-dial step's same-seed variance is 0.05-0.23 of the cross-seed one; newsRate's is
// 0.97, because moving it moves the paths themselves.  Each response carries its standard error.
//
// THE LINEAR PROGRAM maximises the share t of every gap row's distance to its band that one step
// closes: s sigmas per dial, |s| <= -trust, every other row held inside its band, or no further out
// if it already misses.  `-robust k` takes each response at its k-standard-error worst case.  t = 1
// closes every gap in the linear reading; below 1 the binding constraints are printed with their
// dual weights -- a row band that binds names the rows no dial moves together with the gap rows,
// the specification a new mechanism must meet.  A step with t > 0 is read on fresh seeds.
//
// WHAT A STEP MUST ALSO MEET is what adopting it would: every gate that is a band on one reading
// (`gateBandsAt`) is held like a row's band, and with `-noregress R` every row is held no further
// from its record than recipe R, read on the same seeds, plus the release judge's tolerance.
// Without them a step can close every gap by leaving a level gate or giving back what the
// outgoing recipe had.  The gates no band describes are read in the check.
//
// The simplex uses Bland's rule and the Rust twin's operation order, so the twins agree to the bit.

import uni.*
import uni.apps.MarketSim
import uni.apps.MarketSim.World

object MarketSimReach:
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-at NAME      ; the release or recipe to read the dials' responses at",
    "-anchors A    ; sp500 or nasdaq (default: the recipe's own set, else sp500)",
    "-paths N      ; ensemble paths per read (default 60)",
    "-years Y      ; years per path (default 80)",
    "-seeds K      ; shared seeds per read (default 8)",
    "-base S       ; the seeds are S + k * 1000003; the check's are S + 991 + k * 1000003",
    "-sigma S      ; one step, as a fraction of each dial's width (default 0.07, the search's)",
    "-trust T      ; the largest step per dial, in steps (default 3)",
    "-robust K     ; responses at their K-standard-error worst case (default 2)",
    "-gap ROWS     ; comma-separated rows to close (default: every row the base misses)",
    "-dials LIST   ; comma-separated dials to step (default: every searched dial)",
    "-out FILE     ; write every response and its standard error as TSV",
    "-noverify     ; skip reading the proposed step",
    "-probe P      ; a dial at a range bound (0 is usually off) is read P steps away and its",
    "              ;   response taken per step over them (default 3, the trust): an effect that",
    "              ;   grows faster than linearly from 0 reads as nothing one step away",
    "-iterate N    ; take N steps: after each, read the responses again at the stepped world and",
    "              ;   solve again (default 1)",
    "-nogates      ; do not hold the gates: by default every gate that is a band on one reading is",
    "              ;   held like a row's band, and the check names the other gates the step breaks",
    "-noregress R  ; hold every row no further from its record than recipe R, read on the same seeds,",
    "              ;   plus -tol: what the release judge compares a candidate with its outgoing recipe on",
    "-tol P,L      ; that tolerance: P percentile points on a banded row, L log units on any other",
    "              ;   (default 5,0.02, the judge's)",
    "-flags FILE   ; set searched dials on the -at world from FILE's `-name value` pairs, in order,",
    "              ;   the last of a name winning, as the search exports a candidate",
    "-margin M     ; a gap row is closed only M inside its band's edge, in the row's own units",
    "              ;   (default 0): the room a reading needs to clear the band on other seeds",
  )

  type Range = MarketSim.DialRange

  /** One graded row as the verdict reads it: name, reading, and the interval `miss` admits. */
  type Row = (String, Double, (Double, Double))

  /** A dial's value in the search's coordinates (`MarketSim.SearchLogDials`), so a response is
    * read on the step a search child would take. */
  def coord(r: Range, x: Double): Double = MarketSim.searchCoord(r._1, x)

  def fromCoord(r: Range, y0: Double): Double =
    val y = math.min(coord(r, r._3), math.max(coord(r, r._2), y0))
    math.min(r._3, math.max(r._2, MarketSim.fromSearchCoord(r._1, y)))

  /** One step of a dial, in its search coordinates. */
  def stepOf(r: Range, sigma: Double): Double = sigma * (coord(r, r._3) - coord(r, r._2))

  /** A world with its searched dials set, the news size pulled inside the search's share of the
    * diffusion budget as the search's `admissible` pulls it. */
  def withDials(base: World, rs: Vector[Range], d: Vector[Double]): World =
    val rate = rs.zip(d).find(_._1._1 == "newsRate").fold(0.0)(_._2)
    rs.zip(d).foldLeft(base): (w, rx) =>
      val (r, x) = rx
      r._4(w, if r._1 == "newsSize" then MarketSim.newsSizeWithinBudget(rate, x) else x)

  // ---- the readings -----------------------------------------------------------------------

  /** A row's no-regression limit (`-noregress`): the readings in which it stays no further from its
    * record than the outgoing recipe, read on the same seeds, plus the judge's tolerance; `dist`
    * is the recipe's mean distance from the record. */
  final case class Limit(row: String, label: String, dist: Double, iv: (Double, Double))

  /** One read, before it is aligned to the layout: every named reading with its interval, how
    * many of them lead as fidelity rows, and the gates the world fails, in every class. */
  final case class Reading(rows: Vector[Row], nFid: Int, failed: Vector[String])

  /** Every graded row with an interval, as the verdict reads it; then each `-noregress` limit on its
    * row's reading; then, unless `-nogates`, every gate that is a band on one reading. */
  def read(w: World, a: MarketSim.Anchors, o: Opts, limits: Vector[Limit], seed: Long): Reading =
    val sims = MarketSim.simPaths(w, o.paths, o.years, seed)
    val st = MarketSim.measure(sims, o.years)
    val fid = MarketSim.fidelityRows(a, st, Some(sims), o.years, o.paths, seed, w)
    val banded = MarketSim.bandedOf(fid)
    val rows = fid.flatMap(r => r.interval.map(iv => (r.name, r.model, iv)))
    val lims = limits.map(l => (l.label, fid.find(_.name == l.row).fold(Double.NaN)(_.model), l.iv))
    val gates =
      if o.gates then MarketSim.gateBandsAt(a, st, banded).map(g => (s"gate ${g.name}", g.reading, g.band))
      else Vector.empty
    Reading(rows ++ lims ++ gates, rows.length,
            MarketSim.gateChecksAt(a, st, banded).collect { case (n, false, _) => n })

  /** The reading at percentile `p` of a record band's resamples: `percentileExact` inverted,
    * linear between the carried percentiles and continued past either end along the same segment
    * it is. */
  def readingAt(b: MarketSim.RecordBand, p: Double): Double =
    val q = b.q
    def pct(k: Int): Double = MarketSim.RecordBandPcts(k).toDouble
    val last = q.length - 1
    if p >= 100.0 then
      (0 until last).reverse.find(j => q(j) < q(last)) match
        case Some(j) => q(last) + (p - 100.0) * (q(last) - q(j)) / (100.0 - pct(j))
        case None    => q(last)
    else if p <= 0.0 then
      (1 to last).find(j => q(j) > q(0)) match
        case Some(j) => q(0) + p * (q(j) - q(0)) / pct(j)
        case None    => q(0)
    else
      val i = (0 until last).find(i => !(pct(i + 1) < p)).getOrElse(last - 1)
      q(i) + (q(i + 1) - q(i)) * (p - pct(i)) / (pct(i + 1) - pct(i))

  /** THE NO-REGRESSION LIMITS (`-noregress R`): recipe R read on the shared seeds, each row's
    * distance from its record as the release judge measures it -- |percentile - 50| among the
    * record's resamples on a banded row (`percentileExact`, the judge's printed percentile
    * unrounded), and |ln(model / record)| on any other -- averaged over the seeds, plus the judge's
    * tolerance (`-tol`), turned into the readings that keep a step no further from the record.
    * The limit holds the step's MEAN reading, where the judge averages each seed's distance, so it
    * is the slightly looser of the two.  A row the recipe reads with the record's opposite sign,
    * or cannot read, gets none: nothing is further than that. */
  def limitsOf(o: Opts, a: MarketSim.Anchors, seeds: Vector[Long]): Vector[Limit] =
    val (wr, _) = MarketSim.namedWorld(o.noregress)
      .getOrElse(usage(s"-noregress names [${o.noregress}], which is not a release or recipe"))
    val reads = seeds.map: s =>
      val sims = MarketSim.simPaths(wr, o.paths, o.years, s)
      MarketSim.fidelityRows(a, MarketSim.measure(sims, o.years), Some(sims), o.years, o.paths, s, wr)
    reads(0).zipWithIndex.filter(_._1.interval.isDefined).flatMap: (row, r) =>
      val band = a.recordBands.find(_.name == row.name)
      val d = mean(reads.map: rd =>
        val x = rd(r).model
        band match
          case Some(b) => math.abs(b.percentileExact(x) - 50.0)
          case None =>
            val q = x / row.real
            if q > 0.0 && q.isFinite then math.abs(MarketSim.lnDet(q)) else Double.PositiveInfinity)
      if !d.isFinite then None
      else
        val iv = band match
          case Some(b) =>
            val dd = d + o.tol._1
            (readingAt(b, 50.0 - dd), readingAt(b, 50.0 + dd))
          case None =>
            val dd = d + o.tol._2
            val (x1, x2) = (row.real * MarketSim.expDet(-dd), row.real * MarketSim.expDet(dd))
            (math.min(x1, x2), math.max(x1, x2))
        Some(Limit(row.name, s"${row.name} vs ${o.noregress}", d, iv))

  /** A left-to-right sum from +0.0, as the Rust twin writes it out. */
  def total(xs: Iterable[Double]): Double = xs.foldLeft(0.0)(_ + _)

  def mean(xs: Vector[Double]): Double = total(xs) / xs.length

  /** The standard error of the mean, from the sample sd; 0 for one reading. */
  def stdErr(xs: Vector[Double]): Double =
    if xs.length < 2 then 0.0
    else
      val m = mean(xs)
      val ss = total(xs.map(x => (x - m) * (x - m)))
      math.sqrt(ss / (xs.length - 1)) / math.sqrt(xs.length.toDouble)

  /** A gap row's target: its band with `-margin` taken off each side, the room a noisy reading
    * needs. */
  def inset(iv: (Double, Double), m: Double): (Double, Double) = (iv._1 + m, iv._2 - m)

  /** One gap row's need: the direction it must move (+1 up, -1 down) and how far to its band. */
  def need(x: Double, iv: (Double, Double)): Option[(Double, Double)] =
    val (lo, hi) = iv
    if x > hi then Some((-1.0, x - hi))
    else if x < lo then Some((1.0, lo - x))
    else None

  // ---- the linear program -----------------------------------------------------------------

  val Eps = 1e-12

  /** The objective's price of one step on one dial: a tie-break toward the smallest step, without
    * which the program steps dials that move nothing (a zero response costs nothing to take).
    * Small enough that the share closed moves by at most 1e-4 of a gap. */
  val StepCost = 1e-6

  /** Maximise c.z subject to a z <= b, z >= 0, with every b >= 0, so the slack basis is feasible.
    * Bland's rule: the lowest-index improving column enters, and the lowest-index basis variable
    * leaves among tied ratios, so the method cannot cycle and the twins pivot alike.  The tableau
    * is local mutable state, as the Rust twin's is.  None when unbounded. */
  def simplex(a: Vector[Vector[Double]], b: Vector[Double],
              c: Vector[Double]): Option[(Double, Vector[Double], Vector[Double])] =
    val (m, n) = (a.length, c.length)
    val w = n + m + 1
    val t: Array[Array[Double]] = Array.tabulate(m): i =>
      val row = new Array[Double](w)
      Array.copy(a(i).toArray, 0, row, 0, n)
      row(n + i) = 1.0
      row(w - 1) = b(i)
      row
    val obj = new Array[Double](w)
    for j <- 0 until n do obj(j) = -c(j)
    val basis = Array.tabulate(m)(i => n + i)
    var result: Option[Option[(Double, Vector[Double], Vector[Double])]] = None
    var iter = 0
    while result.isEmpty && iter < 10000 do
      iter += 1
      (0 until n + m).find(j => obj(j) < -Eps) match
        case None =>
          val z = new Array[Double](n)
          for i <- 0 until m do if basis(i) < n then z(basis(i)) = t(i)(w - 1)
          result = Some(Some((obj(w - 1), z.toVector, Vector.tabulate(m)(i => obj(n + i)))))
        case Some(e) =>
          var leave = -1
          for i <- 0 until m do
            if t(i)(e) > Eps then
              val r = t(i)(w - 1) / t(i)(e)
              if leave < 0 then leave = i
              else
                val rl = t(leave)(w - 1) / t(leave)(e)
                if r < rl || (r == rl && basis(i) < basis(leave)) then leave = i
          if leave < 0 then result = Some(None)
          else
            val p = leave
            val pv = t(p)(e)
            for j <- 0 until w do t(p)(j) /= pv
            val prow = t(p).clone()
            for i <- 0 until m do
              if i != p then
                val f = t(i)(e)
                if f != 0.0 then for j <- 0 until w do t(i)(j) -= f * prow(j)
            val f = obj(e)
            for j <- 0 until w do obj(j) -= f * prow(j)
            basis(p) = e
    result.flatten

  /** What one step is solved over: the base's rows, the gap rows among them, each row's response
    * to one step of each dial with its standard error, and how far each dial may step each way. */
  final case class Problem(rows: Vector[Row], gap: Vector[Int], dials: Vector[String],
                           jac: Vector[Vector[Double]], se: Vector[Vector[Double]],
                           up: Vector[Double], down: Vector[Double],
                           // the dials read `-probe` steps away, from a range bound
                           probed: Vector[String],
                           // how far inside its band a gap row must reach (`-margin`)
                           margin: Double):
    /** A row's move J s at its upper (sign +1) or lower (sign -1) k-SE bound, as coefficients on
      * z = [s+, s-, t]: a step up moves the row by J, a step down by -J, each at its worst case. */
    def bound(r: Int, sign: Double, k: Double): Vector[Double] =
      val nd = dials.length
      Vector.tabulate(2 * nd + 1): i =>
        if i < nd then jac(r)(i) + sign * k * se(r)(i)
        else if i < 2 * nd then -(jac(r)(i - nd) - sign * k * se(r)(i - nd))
        else 0.0

    /** The constraint a row puts on the step: its label, coefficients and right-hand side. */
    def rowConstraints(r: Int, k: Double): Vector[(String, Vector[Double], Double)] =
      val (name, x, iv) = rows(r)
      val n = 2 * dials.length + 1
      // the worst case of dir * J s, negated: `<= 0` says it moves the right way
      def toward(dir: Double): Vector[Double] =
        bound(r, if dir > 0.0 then -1.0 else 1.0, k).map(c => -dir * c)
      // a row the base could not measure constrains nothing
      // a gap row already where it must reach has nothing to close, and is held like any other row:
      // a later step of `-iterate` must not give back what an earlier one closed
      val open = if gap.contains(r) then need(x, inset(iv, margin)) else None
      if !x.isFinite then Vector.empty
      else open match
        // t dist <= the worst case of dir * J s
        case Some((dir, dist)) => Vector((s"gap        $name", toward(dir).updated(n - 1, dist), 0.0))
        case None => need(x, iv) match
          // already out: no further out, at the worst case
          case Some((dir, _)) => Vector((s"no further $name", toward(dir), 0.0))
          // an open side holds nothing
          case None =>
            (if iv._2.isFinite then Vector((s"band top   $name", bound(r, 1.0, k), iv._2 - x)) else Vector.empty) ++
              (if iv._1.isFinite then Vector((s"band floor $name", bound(r, -1.0, k).map(c => -c), x - iv._1))
               else Vector.empty)

    def solve(k: Double): Option[Step] =
      val nd = dials.length
      // z = [s+ (nd), s- (nd), t]
      val n = 2 * nd + 1
      def unit(j: Int): Vector[Double] = Vector.tabulate(n)(i => if i == j then 1.0 else 0.0)
      val trust = dials.indices.toVector.flatMap(i =>
        Vector((s"trust up   ${dials(i)}", unit(i), up(i)),
               (s"trust down ${dials(i)}", unit(nd + i), down(i))))
      val cons = (rows.indices.toVector.flatMap(r => rowConstraints(r, k)) ++ trust) :+
        ("t <= 1", unit(n - 1), 1.0)
      val c = Vector.tabulate(n)(i => if i == n - 1 then 1.0 else -StepCost)
      simplex(cons.map(_._2), cons.map(_._3), c).map: (_, z, duals) =>
        Step(z(n - 1), Vector.tabulate(nd)(i => z(i) - z(nd + i)),
             cons.zip(duals).collect { case (con, y) if y > 1e-9 => (con._1, y) })

  /** The optimum at `k` standard errors: the share of every gap closed, the step in sigmas per
    * dial, and the constraints that bind, with their dual weights. */
  final case class Step(t: Double, s: Vector[Double], binding: Vector[(String, Double)])

  // ---- the stages -------------------------------------------------------------------------

  final case class Opts(at: String = "", anchors: String = "", paths: Int = 60, years: Int = 80,
                        seeds: Int = 8, base: Long = 20260813L, sigma: Double = 0.07,
                        trust: Double = 3.0, robust: Double = 2.0,
                        gap: Vector[String] = Vector.empty, dials: Vector[String] = Vector.empty,
                        out: String = "", verify: Boolean = true, probe: Double = 3.0,
                        iterate: Int = 1, gates: Boolean = true, noregress: String = "",
                        // the judge's tolerance: percentile points on a banded row, log units on any other
                        tol: (Double, Double) = (5.0, 0.02),
                        // a file of searched-dial flags applied to the `-at` world
                        flags: String = "",
                        // how far inside its band a gap row must reach
                        margin: Double = 0.0)

  /** THE LAYOUT: the rows one read of `w` carries, which every later read is aligned to by name --
    * a gate graded only inside its anchors' range can come and go with a step, and a row a read
    * lacks reads as unmeasured -- and how many of them are fidelity rows. */
  def layoutOf(w: World, a: MarketSim.Anchors, o: Opts, limits: Vector[Limit], seed: Long): (Vector[Row], Int) =
    val r = read(w, a, o, limits, seed)
    (r.rows.map((n, _, iv) => (n, Double.NaN, iv)), r.nFid)

  /** What every stage reads: the options, the world, its anchor set, every searched dial with the
    * world's values, the dials being stepped, the shared seeds, the no-regression limits, and the
    * rows every read is aligned to, of which the first `nFid` are fidelity rows. */
  final case class Setup(o: Opts, w0: World, a: MarketSim.Anchors, all: Vector[Range],
                         dAll: Vector[Double], rs: Vector[Range], seeds: Vector[Long],
                         limits: Vector[Limit], layout: Vector[Row], nFid: Int):
    /** Each seed's read aligned to the layout, and the gates it fails. */
    def readGated(w: World, ss: Vector[Long]): (Vector[Vector[Row]], Vector[Vector[String]]) =
      val both = ss.map: s =>
        val r = read(w, a, o, limits, s)
        (layout.map((n, _, iv) => r.rows.find(_._1 == n).fold((n, Double.NaN, iv))(x => (n, x._2, x._3))),
         r.failed)
      both.unzip

    def readOn(w: World, ss: Vector[Long]): Vector[Vector[Row]] = readGated(w, ss)._1

    /** The world with each stepped dial moved `s(i)` steps. */
    def stepped(s: Vector[Double]): World =
      val d = rs.zip(s).foldLeft(dAll): (d, rsi) =>
        val (r, si) = rsi
        val k = all.indexWhere(_._1 == r._1)
        d.updated(k, fromCoord(r, coord(r, dAll(k)) + si * stepOf(r, o.sigma)))
      withDials(w0, all, d)

    /** The setup at the world one step `s` away, its gap rows named as they were here, so every
      * step closes the same rows. */
    def moved(s: Vector[Double], gap: Vector[String]): Setup =
      val w1 = stepped(s)
      val o1 = o.copy(gap = gap)
      val (lay, n) = layoutOf(w1, a, o1, limits, seeds(0))
      copy(o = o1, w0 = w1, dAll = all.map(_._5(w1)), layout = lay, nFid = n)

  /** `base` with the searched dials `file` sets (`-flags`): `-name value` pairs as the search
    * exports a candidate, a name matching a searched dial without regard to case, applied in order
    * so the last of a name wins, and set through `withDials` as a step's are. */
  def withFlags(base: World, all: Vector[Range], file: String): World =
    val words = file.asPath.contentAsString.split("\\s+").toVector.filter(_.nonEmpty)
    if words.length % 2 != 0 then usage(s"$file holds an odd number of words; it wants -name value pairs")
    val d = words.grouped(2).foldLeft(all.map(_._5(base))): (d, pair) =>
      val name = pair(0).dropWhile(_ == '-').toLowerCase(java.util.Locale.ROOT)
      val k = all.indexWhere(_._1.toLowerCase(java.util.Locale.ROOT) == name)
      if k < 0 then usage(s"$file: [${pair(0)}] is not a searched dial")
      d.updated(k, pair(1).toDoubleOption.getOrElse(usage(s"$file: ${pair(0)} wants a number, got [${pair(1)}]")))
    withDials(base, all, d)

  def setupOf(o: Opts): Setup =
    val (named, spec) = MarketSim.namedWorld(o.at)
      .getOrElse(usage(s"-at names [${o.at}], which is not a release or recipe"))
    val a = MarketSim.anchorsNamed(if o.anchors.isEmpty then spec.getOrElse("sp500") else o.anchors)
    val all = MarketSim.CalibrateRanges
    val w0 = if o.flags.isEmpty then named else withFlags(named, all, o.flags)
    val rs =
      if o.dials.isEmpty then all
      else o.dials.map(d => all.find(_._1 == d)
                              .getOrElse(usage(s"-dials names [$d], which is not searched")))
    val seeds = Vector.tabulate(o.seeds)(k => o.base + k * 1000003L)
    val limits = if o.noregress.isEmpty then Vector.empty else limitsOf(o, a, seeds)
    val (layout, nFid) = layoutOf(w0, a, o, limits, seeds(0))
    Setup(o, w0, a, all, all.map(_._5(w0)), rs, seeds, limits, layout, nFid)

  /** THE BASE on every shared seed, its mean rows, and the gap rows among them. */
  def baseRows(su: Setup, base: Vector[Vector[Row]]): (Vector[Row], Vector[Int]) =
    val rows = base(0).indices.toVector.map(r => (base(0)(r)._1, mean(base.map(_(r)._2)), base(0)(r)._3))
    // by default the fidelity rows the base misses; a gate or a limit it is already outside is held
    // no further out, and closed only when `-gap` names it
    val gap =
      if su.o.gap.isEmpty then (0 until su.nFid).toVector.filter(r => need(rows(r)._2, rows(r)._3).isDefined)
      else su.o.gap.map(g => rows.indexWhere(_._1 == g) match
        case -1 => usage(s"-gap names [$g], which is not a graded row")
        case i  => i)
    println(s"\nthe base, mean of ${su.seeds.length} seeds:")
    for (row, r) <- rows.zipWithIndex do
      val (name, x, (lo, hi)) = row
      val tag =
        if gap.contains(r) then "  <-- GAP"
        else if need(x, (lo, hi)).isDefined then "  (misses, held)"
        else ""
      println(f"  $name%-24s $x%10.4f   band $lo%9.4f .. $hi%-9.4f$tag")
    (rows, gap)

  /** THE RESPONSES: one step of each dial, read on the base's seeds, each row's mean difference
    * per step up and its standard error; and how many steps each dial may take each way. */
  def respond(su: Setup, base: Vector[Vector[Row]], rows: Vector[Row], gap: Vector[Int]): Problem =
    val nd = su.rs.length
    val perDial = su.rs.zipWithIndex.map: (r, i) =>
      val k = su.all.indexWhere(_._1 == r._1)
      val c0 = coord(r, su.dAll(k))
      val h = stepOf(r, su.o.sigma)
      // a dial at a range bound is read `-probe` steps away: switched off at 0, a mechanism's
      // effect can grow faster than linearly, and one step would read it as nothing
      val m = if c0 <= coord(r, r._2) || c0 >= coord(r, r._3) then su.o.probe else 1.0
      // toward the interior when a step up would leave the range
      val dir = if c0 + m * h <= coord(r, r._3) then 1.0 else -1.0
      val up = math.max(math.min(su.o.trust, (coord(r, r._3) - c0) / h), 0.0)
      val down = math.max(math.min(su.o.trust, (c0 - coord(r, r._2)) / h), 0.0)
      val reads = su.readOn(withDials(su.w0, su.all, su.dAll.updated(k, fromCoord(r, c0 + dir * m * h))), su.seeds)
      val resp = rows.indices.toVector.map: rr =>
        if !rows(rr)._2.isFinite then (0.0, 0.0)
        else
          val diffs = reads.zip(base).map((x1, x0) => dir * (x1(rr)._2 - x0(rr)._2) / m)
          // a reading the stepped world could not take says nothing about the dial
          if diffs.forall(_.isFinite) then (mean(diffs), stdErr(diffs))
          else
            eprintln(s"  ${r._1} leaves ${rows(rr)._1} unmeasured; its response is read as 0")
            (0.0, 0.0)
      eprintln(s"  responded: ${r._1} (${i + 1}/$nd)")
      (resp, up, down, m > 1.0)
    val probed = su.rs.zip(perDial).collect { case (r, pd) if pd._4 => r._1 }
    if probed.nonEmpty then
      println(f"\nat a range bound, read ${su.o.probe}%.1f steps away: ${probed.mkString(", ")}")
    Problem(rows, gap, su.rs.map(_._1),
            rows.indices.toVector.map(rr => perDial.map(_._1(rr)._1)),
            rows.indices.toVector.map(rr => perDial.map(_._1(rr)._2)),
            perDial.map(_._2), perDial.map(_._3), probed, su.o.margin)

  /** Every response and its standard error, as TSV. */
  def writeResponses(p: Problem, file: String): Unit =
    val lines = "row\tdial\tresponse\tse" +: (for
      rr <- p.rows.indices.toVector
      i  <- p.dials.indices.toVector
    yield f"${p.rows(rr)._1}\t${p.dials(i)}\t${p.jac(rr)(i)}%.6f\t${p.se(rr)(i)}%.6f")
    file.asPath.writeLines(lines)
    println(s"\nwrote $file")

  /** A descending order with the lower index first among ties: `total_cmp` in the Rust twin,
    * `Double.compare` here, which order -0.0 and NaN alike. */
  def descending(xs: Vector[(Int, Double)]): Vector[(Int, Double)] =
    xs.sortWith: (x, y) =>
      val c = java.lang.Double.compare(y._2, x._2)
      if c != 0 then c < 0 else x._1 < y._1

  /** WHICH DIALS MOVE EACH GAP ROW the way it must go, by how many standard errors. */
  def reportGapDials(p: Problem): Unit =
    for g <- p.gap do
      val (dir, dist) = need(p.rows(g)._2, inset(p.rows(g)._3, p.margin)).getOrElse((0.0, 0.0))
      val target = if p.margin > 0.0 then f"${p.margin}%.4f inside its band edge" else "its band edge"
      println(f"\n${p.rows(g)._1}%s needs ${dir * dist}%+.4f (to $target%s); per step up, the dials that move it that way:")
      val by = p.dials.indices.toVector.map(i =>
        (i, if p.se(g)(i) > 0.0 then dir * p.jac(g)(i) / p.se(g)(i) else 0.0))
      for (i, z) <- descending(by).take(6) do
        println(f"  ${p.dials(i)}%-18s ${p.jac(g)(i)}%+.4f +- ${p.se(g)(i)}%.4f   ($z%+.1f se)")

  def reportStep(p: Problem, label: String, k: Double, st: Step): Unit =
    println(f"\n$label%s (responses at $k%.1f se): one step closes ${100.0 * st.t}%.1f%% of every gap")
    val moved = descending(st.s.zipWithIndex.collect { case (v, i) if math.abs(v) > 1e-9 => (i, math.abs(v)) })
    for (i, _) <- moved do println(f"  step ${p.dials(i)}%-18s ${st.s(i)}%+.3f sigma")
    println("  bound by (dual weight):")
    for (l, y) <- st.binding do println(f"    $l%-40s $y%.4f")

  /** THE CHECK: the step read on fresh seeds, against the base on the same fresh seeds and the
    * linear prediction.  Returns each row's mean reading before and after the step. */
  def check(su: Setup, p: Problem, st: Step): Vector[(Double, Double)] =
    val fresh = Vector.tabulate(su.o.seeds)(k => su.o.base + 991L + k * 1000003L)
    val (at0, f0) = su.readGated(su.w0, fresh)
    val (at1, f1) = su.readGated(su.stepped(st.s), fresh)
    println(s"\nthe robust step read on ${fresh.length} fresh seeds (base -> stepped, the linear prediction):")
    val read = p.rows.zipWithIndex.map: (row, r) =>
      val x0 = mean(at0.map(_(r)._2))
      val xs = at1.map(_(r)._2)
      val x1 = mean(xs)
      val pred = x0 + total(p.jac(r).zip(st.s).map((j, s) => j * s))
      val tag = (need(x0, row._3).isDefined, need(x1, row._3).isDefined) match
        case (false, true)  => "  <-- LEAVES its band"
        case (true, false)  => "  <-- ENTERS its band"
        case (true, true)   => "  (misses)"
        case (false, false) => ""
      val g = if p.gap.contains(r) then "*" else " "
      println(f" $g%s${row._1}%-24s $x0%10.4f -> $x1%10.4f +- ${stdErr(xs)}%.4f   predicted $pred%10.4f$tag%s")
      (x0, x1)
    // every gate, in every class, by the fresh seeds' majority: the ones the program held are
    // above, and the rest -- combined and mechanism rows -- are only read here
    def often(fs: Vector[Vector[String]]): Vector[String] =
      fs.flatten.distinct.filter(g => 2 * fs.count(_.contains(g)) >= fs.length)
    val (was, now) = (often(f0), often(f1))
    println(s"  gates failed on at least half the fresh seeds: ${was.length} -> ${now.length}")
    for g <- now.filterNot(was.contains) do println(s"    FAILS  $g")
    for g <- was.filterNot(now.contains) do println(s"    passes $g")
    read

  def list(s: String): Vector[String] = s.split(",").toVector.map(_.trim).filter(_.nonEmpty)

  def main(args: Array[String]): Unit =
    var o = Opts()
    def int(flag: String, v: String): Int = v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))
    def real(flag: String, v: String): Double = v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))
    eachArg(args.toSeq, usage) {
      case "-at"       => o = o.copy(at = consumeNext)
      case "-anchors"  => o = o.copy(anchors = consumeNext)
      case "-paths"    => o = o.copy(paths = int("-paths", consumeNext))
      case "-years"    => o = o.copy(years = int("-years", consumeNext))
      case "-seeds"    => o = o.copy(seeds = int("-seeds", consumeNext))
      case "-base"     => o = o.copy(base = consumeNext.toLongOption.getOrElse(usage("-base wants an integer")))
      case "-sigma"    => o = o.copy(sigma = real("-sigma", consumeNext))
      case "-trust"    => o = o.copy(trust = real("-trust", consumeNext))
      case "-robust"   => o = o.copy(robust = real("-robust", consumeNext))
      case "-gap"      => o = o.copy(gap = list(consumeNext))
      case "-dials"    => o = o.copy(dials = list(consumeNext))
      case "-out"      => o = o.copy(out = consumeNext)
      case "-noverify" => o = o.copy(verify = false)
      case "-probe"    => o = o.copy(probe = real("-probe", consumeNext))
      case "-iterate"  => o = o.copy(iterate = int("-iterate", consumeNext))
      case "-nogates"  => o = o.copy(gates = false)
      case "-flags"    => o = o.copy(flags = consumeNext)
      case "-margin"   => o = o.copy(margin = real("-margin", consumeNext))
      case "-noregress" => o = o.copy(noregress = consumeNext)
      case "-tol"      =>
        list(consumeNext) match
          case Vector(p, l) => o = o.copy(tol = (real("-tol", p), real("-tol", l)))
          case _            => usage("-tol wants P,L")
      case a           => usage(s"unknown flag [$a]")
    }
    if o.at.isEmpty then usage("-at names the world to read")
    if o.seeds < 2 then usage("-seeds wants at least 2: a response's standard error needs two readings")
    if o.sigma <= 0.0 || o.trust <= 0.0 || o.robust < 0.0 || o.probe < 1.0 then
      usage("-sigma and -trust want positive numbers, -robust a non-negative one, -probe 1 or more")
    if o.iterate < 1 then usage("-iterate wants at least 1")
    if o.tol._1 < 0.0 || o.tol._2 < 0.0 || o.margin < 0.0 then
      usage("-tol and -margin want non-negative numbers")
    val su = setupOf(o)
    println(f"reach: ${o.at}%s on ${su.a.name}%s, ${o.paths}%d x ${o.years}%dy on ${su.seeds.length}%d shared seeds; " +
            f"step ${o.sigma}%.3f of each dial's width, trust ${o.trust}%.1f steps, robust ${o.robust}%.1f se")
    if o.flags.nonEmpty then println(s"its searched dials set from ${o.flags}")
    if o.margin > 0.0 then println(f"a gap row is closed ${o.margin}%.4f inside its band edge")
    println(if o.gates then "every gate that is a band on one reading is held as a row's band"
            else "gates not held (-nogates)")
    if su.limits.nonEmpty then
      println(f"\nno further from the record than ${o.noregress}%s (mean of ${su.seeds.length}%d seeds; " +
              f"-tol ${o.tol._1}%.1f,${o.tol._2}%.3f), each row's readings:")
      for l <- su.limits do
        println(f"  ${l.row}%-24s distance ${l.dist}%8.4f   ${l.iv._1}%10.4f .. ${l.iv._2}%10.4f")
    // each gap row's fresh-seed reading at the start and after every step taken; the setup is
    // local mutable state, moved to each stepped world
    var at = su
    var path = Vector.empty[(String, Vector[Double])]
    var stopped = Option.empty[(Int, String)]
    var step = 1
    while stopped.isEmpty && step <= o.iterate do
      if o.iterate > 1 then println(s"\n==== step $step of ${o.iterate} ====")
      oneStep(at) match
        case Left(why) => stopped = Some((step, why))
        case Right((st, trail)) =>
          if path.isEmpty then path = trail.map((n, x0, _) => (n, Vector(x0)))
          path = path.zip(trail).map { case ((n, xs), (_, _, x1)) => (n, xs :+ x1) }
          if step < o.iterate then at = at.moved(st.s, trail.map(_._1))
      step += 1
    for (k, why) <- stopped do println(s"\nstopped at step $k: $why")
    // only steps taken and read on fresh seeds have a reading to show
    if o.iterate > 1 && o.verify && path.nonEmpty then
      println("\nthe gap rows on fresh seeds, at the start and after each step:")
      for (name, xs) <- path do println(f"  $name%-24s ${xs.map(x => f"$x%.4f").mkString(" -> ")}")

  /** One step from `su`: the base, the responses, both solves and the check.  Returns the robust
    * step and the gap rows' names with their fresh-seed readings before and after it, or why no
    * step was taken. */
  def oneStep(su: Setup): Either[String, (Step, Vector[(String, Double, Double)])] =
    val base = su.readOn(su.w0, su.seeds)
    val (rows, gap) = baseRows(su, base)
    // nothing left to close, and the responses cost a read per dial
    if gap.forall(g => need(rows(g)._2, inset(rows(g)._3, su.o.margin)).isEmpty) then Left("every gap row is inside its band")
    else
      val p = respond(su, base, rows, gap)
      if su.o.out.nonEmpty then writeResponses(p, su.o.out)
      reportGapDials(p)
      val nominal = p.solve(0.0)
      val robust = p.solve(su.o.robust)
      for (label, k, st) <- Vector(("nominal", 0.0, nominal), ("robust", su.o.robust, robust)) do
        st match
          case None     => println(s"\n$label: the linear program is unbounded")
          case Some(st) => reportStep(p, label, k, st)
      robust.filter(_.t > 0.0) match
        case None => Left("no robust step closes any share of the gap")
        case Some(st) =>
          val trail =
            if su.o.verify then
              val read = check(su, p, st)
              p.gap.map(g => (p.rows(g)._1, read(g)._1, read(g)._2))
            else p.gap.map(g => (p.rows(g)._1, Double.NaN, Double.NaN))
          Right((st, trail))
