package uni.apps

import munit.FunSuite

/** NEWS THAT FOLLOWS LEVERAGE (`newsLev`, item 29): tied to the credit stock, frequent news lands
  * late in the cycle, so the declines it starts still follow leverage -- which independent news at
  * the same rate does not -- and the share of it that reverts (`newsRevert`) keeps the variance
  * ratio.  The Rust twin's `news_lev_tests` makes the same checks. */
class NewsLevSuite extends FunSuite:
  test("tied to the credit stock, the news keeps declines following leverage") {
    def read(lev: Double): (Double, Double) =
      val w  = MarketSim.namedWorld("0.24.4-nasdaq").get._1
        .copy(newsRate = 20.0, newsSize = 0.02, newsLev = lev)
      val st = MarketSim.measure(MarketSim.simPaths(w, 60, 100, 20260918L), 100)
      val m  = st.macroPanel.getOrElse(fail("the recipe runs the panel"))
      (m.members(2).prePeak, m.hazard20q)
    val (b0, h0)   = read(0.0)
    val (b35, h35) = read(35.0)
    assert(b35 > b0 + 0.05, f"build-up $b0%.3f -> $b35%.3f")
    assert(h35 > h0, f"hazard $h0%.2f -> $h35%.2f")
  }

  test("a news markdown value capital buys back keeps the variance ratio") {
    // permanent steps in place of the transient noise lift the 60-day variance ratio; the share
    // that reverts brings it back down
    def vr60(revert: Double): Double =
      val w = MarketSim.namedWorld("0.24.4-nasdaq").get._1
        .copy(newsRate = 20.0, newsSize = 0.02, newsLev = 35.0, newsRevert = revert)
      MarketSim.measure(MarketSim.simPaths(w, 40, 60, 20260918L), 60).vr60
    val (v0, v6) = (vr60(0.0), vr60(0.6))
    assert(v6 < v0 - 0.1, f"variance ratio 60d $v0%.3f -> $v6%.3f")
  }

  /** The coupled, partly reverting news of the Nasdaq recipe at 22/yr x 2%, the point both
    * couplings below were measured at. */
  private def coupledNews =
    MarketSim.namedWorld("0.24.4-nasdaq").get._1
      .copy(newsRate = 22.0, newsSize = 0.02, newsLev = 50.0, newsRevert = 0.5)

  test("news paid by the body buys more up days and keeps the mean") {
    // the lift and the flips pay the same compensator; the flips turn down days up
    def read(flip: Double): (Double, Double) =
      val w = MarketSim.namedWorld("0.24.3-nasdaq").get._1
        .copy(newsRate = 16.0, newsSize = 0.02, newsLev = 50.0, newsRevert = 0.6, newsFlip = flip)
      val st = MarketSim.measure(MarketSim.simPaths(w, 40, 60, 20260918L), 60)
      (st.upShare, st.annRet)
    val ((u0, r0), (u1, r1)) = (read(0.0), read(1.0))
    assert(u1 > u0 + 0.5, f"up-day share $u0%.2f -> $u1%.2f")
    assert(math.abs(r1 - r0) < 1.0, f"annual return $r0%.2f -> $r1%.2f")
  }

  test("news at the session's own volatility gives back the vol it displaced") {
    def vol(scale: Double): Double =
      MarketSim.measure(MarketSim.simPaths(coupledNews.copy(newsScale = scale), 40, 60, 20260918L), 60).vol
    val (v0, v1) = (vol(0.0), vol(1.0))
    assert(v1 > v0 + 0.01, f"equity vol $v0%.4f -> $v1%.4f")
  }

  test("the bond leg of news rallies the bond in growth crashes") {
    def read(bond: Double): Double =
      MarketSim.measure(MarketSim.simPaths(coupledNews.copy(newsBond = bond), 40, 60, 20260918L), 60).bondGrowth
    val (g0, g3) = (read(0.0), read(0.3))
    assert(g3 > g0 + 0.5, f"growth-crash rally $g0%.2f -> $g3%.2f")
  }

  test("a bond that skips news keeps its rally and loosens its tail tie") {
    def read(skip: Double): (Double, Double) =
      val w  = coupledNews.copy(newsBond = 0.4, newsBondSkip = skip)
      val st = MarketSim.measure(MarketSim.simPaths(w, 40, 60, 20260918L), 60)
      (st.bondGrowth, st.tailHedge)
    val ((g0, t0), (g5, t5)) = (read(0.0), read(0.5))
    assert(t5 > t0 + 0.01, f"tail hedge $t0%.3f -> $t5%.3f")
    assert(math.abs(g5 - g0) < 1.0, f"growth-crash rally $g0%.2f -> $g5%.2f")
  }
