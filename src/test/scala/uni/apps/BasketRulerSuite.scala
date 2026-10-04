package uni.apps

import munit.FunSuite
import uni.*

/** Rulers the basket tests read the model with. */
object BasketRulers:
  /** A ruler of `n` names each covering its whole window: the basket read with no name absent. */
  def fullCoverage(n: Int): MarketSim.BasketRuler =
    MarketSim.BasketRuler("full.tsv", "", "IDX", "adjusted-dividends-reinvested", "2010-01-04", "2000-01-04",
      "2009-12-31", 2520, 252,
      Vector.tabulate(n)(k => MarketSim.RulerName(s"N${k + 1}", "2000-01-04", "2009-12-31", 0, 2520, 2520, 2.0, 1.0)),
      20.0, 30.0, 0.8, 1.4, 1.6, (0.5, 0.4, 0.8), (0.4, 0.3, 0.6), 0.45, (0.6, 0.3, 0.4), None)

/**
 * THE BASKET RULER: the twins write the pinned synthetic ruler from its closes to the printed
 * decimals (blanks before a listing, a delisting, a name under the session rule); a ruler reads
 * back what it states and its bands follow the documented rules; a ruler that cannot grade is
 * refused; the coverage mask reads the record's composition; the basket rows grade only against a
 * named ruler, whose dials resolve into the world and the sidecar; the example ruler's fitted
 * basket sits inside its bands; off is bit-identical and the names observational.  The Rust twin
 * carries the same checks in `basket_ruler_tests`, against the same files.
 */
class BasketRulerSuite extends FunSuite:

  val Closes    = "test-data/equity-anchors/basket-closes-synthetic-2026-10-04.csv"
  val Synthetic = "test-data/equity-anchors/basket-ruler-synthetic-2026-10-04.tsv"
  val Example   = "test-data/equity-anchors/basket-ruler-smh8-qqq-2026-10-04.tsv"

  def read(f: String): Option[String] =
    val p = f.asPath
    Option.when(p.isFile)(p.contentAsString)

  def dataRows(t: String): Vector[String] =
    t.split("\n", -1).toVector.filterNot(l => l.startsWith("#") || l.startsWith("group\t") || l.trim.isEmpty)

  def example: Option[(String, MarketSim.BasketRuler)] =
    read(Example).map(t => (t, MarketSim.parseBasketRuler(t, "basket-ruler-smh8-qqq-2026-10-04.tsv")
                                 .fold(m => fail(m), identity)))

  def withExample(body: (String, MarketSim.BasketRuler) => Unit): Unit =
    example match
      case Some((t, r)) => body(t, r)
      case None         => assume(false, s"$Example absent")

  def fitted(r: MarketSim.BasketRuler): Vector[Double] =
    val d = r.dials.getOrElse(fail("the example ruler carries its dials"))
    Vector(d.basketBeta, d.basketSector, d.basketIdio, d.basketGaps)

  test("the synthetic closes write the pinned ruler row for row") {
    (read(Closes), read(Synthetic)) match
      case (Some(c), Some(want)) =>
        val got = MarketSim.parseBasketCloses(c)
          .flatMap(MarketSim.basketRulerTsv(_, "", "", "2026-10-04", 252)).fold(m => fail(m), identity)
        assertEquals(dataRows(got), dataRows(want))
        assert(MarketSim.parseBasketRuler(got, "written").isRight, "what it writes reads")
      case _ => assume(false, "fixtures absent")
  }

  test("a listing, a delisting and a short name are read on the names present") {
    val t = read(Synthetic).getOrElse { assume(false, s"$Synthetic absent"); "" }
    val r = MarketSim.parseBasketRuler(t, "synthetic").fold(m => fail(m), identity)
    def at(n: String): Int = r.names.indexWhere(_.name == n)
    assertEquals(r.sessions, 1799)
    val (ddd, fff, ggg) = (at("DDD"), at("FFF"), at("GGG"))
    assertEquals((r.names(ddd).start, r.names(ddd).end), (400, 1799))
    assertEquals((r.names(ggg).start, r.names(ggg).end), (0, 1299))
    assertEquals(r.names(fff).sessions, 199)
    assert(!r.graded(fff) && r.graded(ddd) && r.graded(ggg))
    val pairs = r.gradedPairs
    assertEquals(pairs.length, 15, "the six graded names' pairs")
    assert(pairs.forall((a, b) => a != fff && b != fff))
  }

  test("the bands follow the documented rules") {
    withExample { (t, r) =>
      val rows = dataRows(t).map(_.split("\t", -1).toVector)
      def value(g: String, n: String, s: String): Double =
        rows.find(x => x(0) == g && x(1) == n && x(2) == s).getOrElse(fail(s"no row [$g $n $s]"))(3).toDouble
      def per(s: String): Vector[Double] = r.names.map(m => value("names", m.name, s))
      def out1(lo: Double, hi: Double) = (math.floor(lo * 10.0) / 10.0, math.ceil(hi * 10.0) / 10.0)
      def out2(lo: Double, hi: Double) = (math.floor(lo * 100.0) / 100.0, math.ceil(hi * 100.0) / 100.0)
      def at2(x: Double) = math.round(x * 100.0) / 100.0
      val (vr, gp) = (per("volRatio"), per("gaps10"))
      val (corr, beta, volr, tail) = (value("basket", "basket", "corr"), value("basket", "basket", "betaOn"),
                                      value("basket", "basket", "volRatio"), value("cross", "tailCoincidence", "value"))
      val want = Vector(out1(vr.min, vr.max), out1(gp.min, gp.max), (at2(corr - 0.10), at2(corr + 0.10)),
                        out1(beta - 0.25, beta + 0.25), out1(volr - 0.30, volr + 0.30),
                        out2(value("cross", "pairCorr", "min"), value("cross", "pairCorr", "max")),
                        out2(value("cross", "idioShare", "min"), value("cross", "idioShare", "max")),
                        (at2(tail - 0.13), at2(tail + 0.12)))
      r.bands.zip(want).foreach { (b, w) =>
        assert(math.abs(b.lo - w._1) < 1e-12 && math.abs(b.hi - w._2) < 1e-12, s"${b.row}: ${b.lo}-${b.hi} against $w")
      }
      // the numbers the docs quote for the example
      assertEquals(r.bands.map(b => f"${b.lo}%.2f-${b.hi}%.2f"),
                   Vector("1.50-2.80", "0.40-5.10", "0.74-0.94", "1.00-1.60", "1.30-2.00", "0.39-0.85", "0.25-0.53",
                          "0.32-0.57"))
    }
  }

  test("a ruler that cannot grade is refused, and one without dials waits for a solve") {
    withExample { (t, r) =>
      val flipped = t.replace("worstDecile\t0.511", "worstDecile\t0.200")
      assert(MarketSim.parseBasketRuler(flipped, "flipped").left.exists(_.contains("the mechanism row cannot be read")))
      val short = t.replace("cross\tidioShare\tmedian", "cross\tidioShare\tmid")
      assert(MarketSim.parseBasketRuler(short, "short").left.exists(_.contains("no row [cross idioShare median]")))
      val bare = t.split("\n", -1).toVector.filterNot(_.startsWith("dials\t")).mkString("\n")
      val b = MarketSim.parseBasketRuler(bare, "bare").fold(m => fail(m), identity)
      assert(b.dials.isEmpty)
      val none = Vector.fill(4)(false)
      assert(MarketSim.ruledBasket(b, None, Vector.fill(4)(0.0), none, false).left.exists(_.contains("run -solvebasket first")))
      assertEquals(MarketSim.ruledBasket(b, None, Vector.fill(4)(0.0), none, true), Right((8, MarketSim.SolveBasketStart)))
      assertEquals(MarketSim.ruledBasket(b, None, Vector(1.2, 0.7, 0.9, 4.0), none, true),
                   Right((8, Vector(1.2, 0.7, 0.9, 4.0))), "a solve starts from the world's dials")
      for n <- Seq(0, 45) do assert(MarketSim.ruledBasket(r, Some(n), Vector.fill(4)(0.0), none, false).isLeft)
      val d = fitted(r)
      assertEquals(MarketSim.ruledBasket(r, Some(8), Vector.fill(4)(9.0), Vector(false, true, false, false), false),
                   Right((8, Vector(d(0), 9.0, d(2), d(3)))), "the ruler's dials apply where no flag gave one")
    }
  }

  // THE COVERAGE MASK: names listing late leave the early aggregate to fewer names, and the model's
  // reading holds the same composition -- the aggregate's vol rises toward the names' own as names
  // drop out of it -- while the emitted names keep every session.
  test("the coverage mask reads the record's composition") {
    val w = MarketSim.Defaults.copy(basket = 8, basketBeta = 1.35, basketSector = 0.8, basketIdio = 1.0, basketGaps = 7.0)
    val sims = MarketSim.simPaths(w, 4, 40, MarketSim.DefaultSeed)
    val full = BasketRulers.fullCoverage(8)
    val late = full.copy(names = full.names.zipWithIndex.map((m, k) => if k < 2 then m else m.copy(start = 2016, sessions = 504)))
    val a = MarketSim.basketReading(full, sims).getOrElse(fail("a reading"))
    val b = MarketSim.basketReading(late, sims).getOrElse(fail("a reading"))
    assert(b.aggVolRatio > a.aggVolRatio + 0.02,
           s"two names alone for four fifths of the window: ${b.aggVolRatio} against ${a.aggVolRatio}")
    assert(sims.head.names(2).forall(_.isFinite))
    assert(MarketSim.basketReading(full.copy(names = full.names.take(7)), sims).isEmpty,
           "a basket of another size reads nothing")
  }

  // The example ruler's own dials on the world they were solved on: every basket row and the
  // mechanism inside the ruler's bands at 8 x 100, and nothing graded without the ruler.
  test("the basket rows grade only against a named ruler") {
    withExample { (_, r) =>
      val nq = MarketSim.namedWorld("0.24.6-nasdaq-basket").getOrElse(fail("the recipe"))._1
      val w = MarketSim.withBasketDials(nq, r, fitted(r))
      val sims = MarketSim.simPaths(w, 8, 100, MarketSim.DefaultSeed)
      val ruled = MarketSim.NasdaqAnchors.copy(basketRuler = Some(r))
      val st = MarketSim.measureFor(ruled, sims, 100)
      val b = st.basket.getOrElse(fail("a reading against the ruler"))
      val rows = MarketSim.gateChecks(ruled, st).filter(_._1.startsWith("basket"))
      assertEquals(rows.length, 9)
      rows.foreach((nm, ok, _) => assert(ok, s"$nm failed: $b"))
      val bare = MarketSim.measureFor(MarketSim.NasdaqAnchors, sims, 100)
      assert(bare.basket.isEmpty && !MarketSim.gateChecks(MarketSim.NasdaqAnchors, bare).exists(_._1.startsWith("basket")))
    }
  }

  // A ruler's dials resolve into the sidecar's `world` block and the world digest, `channels.basket`
  // carries the ruler and the rows graded, the names are graded series and `logBasket` leaves the
  // file; without a ruler the same basket is emitted whole and ungraded.
  test("the sidecar names the ruler and what it graded") {
    withExample { (_, r) =>
      val (years, seed) = (2, 20261004L)
      val w = MarketSim.withBasketDials(MarketSim.Defaults, r, fitted(r))
      val p = MarketSim.simulate(w, years, seed)
      val dir = java.nio.file.Files.createTempDirectory("basketRuler")
      def emit(a: MarketSim.Anchors, tag: String): (String, String) =
        val st = MarketSim.measureFor(a, Vector(p), years)
        val file = s"${dir.posx}/$tag.tsv"
        MarketSim.writeEmitted(a, file, p, 0, MarketSim.EmitSpec(w, years, seed, ""),
                               MarketSim.Verdict(1, years, w, st, Vector.empty, Vector.empty, p))
        val head = file.asPath.lines.toVector.head
        val side = MarketSim.sidecarName(file).asPath.contentAsString
        file.asPath.delete()
        MarketSim.sidecarName(file).asPath.delete()
        (head, side)
      val ruled = MarketSim.NasdaqAnchors.copy(basketRuler = Some(r))
      val (head, side) = emit(ruled, "ruled")
      val (head0, side0) = emit(MarketSim.NasdaqAnchors, "bare")
      dir.delete()
      val d = fitted(r)
      val lines = side.split("\n", -1).toVector
      for line <- Vector(s"""    "basketBeta": ${MarketSim.ef(d(0))},""", s"""    "basketIdio": ${MarketSim.ef(d(2))},""",
                         s"""  "worldDigest": ${MarketSim.jsonStr(MarketSim.worldDigest(w))},""",
                         """        "readUnder": "coverage",""", """        "index": "QQQ",""") do
        assert(lines.contains(line), s"sidecar lacks [$line]")
      assert(side.contains("\"graded\": true,\n      \"ruler\": {"))
      assert(side.contains("\"gradedSeries\": [\"price\", \"bond\", \"logName1\""))
      assert(!head.split("\t").contains("logBasket") && head.contains("\tlogName8"))
      assert(side0.contains("\"basket\": { \"ruler\": null, \"graded\": false }"))
      assert(head0.split("\t").contains("logBasket"))
      assert(side0.contains("\"ungradedChannelSeries\": [\"logBasket\", \"logName1\""))
      assert(side0.contains("\"source\": \"emitted\", \"graded\": false }"))
    }
  }

  test("a pinned ensemble's basket reading is the Rust twin's") {
    withExample { (_, r) =>
      val w = MarketSim.withBasketDials(MarketSim.Defaults, r, fitted(r))
      val b = MarketSim.basketReading(r, MarketSim.simPaths(w, 2, 10, MarketSim.DefaultSeed)).getOrElse(fail("a reading"))
      val got = Vector(b.nameVolRatio, b.nameGaps, b.nameD20, b.aggCorr, b.aggBeta, b.aggVolRatio, b.pairCorr,
                       b.idioShare, b.tailCoincidence, b.pairCorrWorst, b.pairCorrMid, b.nameD20Spread)
      val pin = Vector(2.952982519731829, 2.438467645891227, 0.684920634920635, 0.882278025939869, 1.415325860661501,
                       1.801703026235104, 0.590152284566778, 0.605510294761377, 0.543269230769231, 0.738192358420497,
                       0.194299133765872, 0.436904761904762)
      got.zip(pin).foreach((g, p) => assert(math.abs(g - p) < 1e-9, s"$got"))
    }
  }

  test("off is bit-identical, carries no names, and every frozen world keeps the basket off") {
    val anchored = MarketSim.Defaults.copy(basket = 8, basketBeta = 1.56, basketSector = 1.1, basketIdio = 0.9,
                                           basketGaps = 6.0)
    val off = MarketSim.simulate(MarketSim.Defaults, 3, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(anchored, 3, MarketSim.DefaultSeed)
    assert(off.names.isEmpty)
    assertEquals(on.names.length, 8)
    assert(on.price.sameElements(off.price) && on.fundamental.sameElements(off.fundamental), "the names are observational")
    for (v, w) <- MarketSim.Releases do assertEquals(w.basket, 0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes
        if !n.endsWith("basket") && !n.endsWith("channels") && !Set("0.24.5-sp500", "0.24.6-sp500").contains(n) do
      assertEquals(w.basket, 0, s"recipe $n")
    assertEquals(MarketSim.Defaults.basket, 0)
    val chans = MarketSim.Defaults.copy(satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63, rangeDown = 0.09)
    val a = MarketSim.simulate(chans, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(chans.copy(basket = 8, basketBeta = 1.56, basketSector = 1.1, basketIdio = 0.9,
                                          basketGaps = 6.0), 3, MarketSim.DefaultSeed)
    assert(a.sat.sameElements(b.sat) && a.logHi.sameElements(b.logHi), "the basket reads its own stream only")
  }
