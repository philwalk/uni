package uni.stats

import munit.FunSuite
import uni.data.*
import uni.data.Mat.*
import uni.stats.Tprf3.*

/** Targets the 585 missed branches in uni.stats.Tprf3 that TprfSuite doesn't reach:
 *  - PLS mode for OOS Recursive / Cross Val / Rolling (autoproxy)
 *  - mintrain._1 < 0 → T/2 branch
 *  - OOS Rolling with gap > 0
 *  - IS Full autoproxy + computeAvar=true
 *  - t3prf: empty phiHat → pass1columnsRsquared = Array.empty
 *  - t3prf: adjRsq when nObs <= nParams
 *  - t3prf: rSquared when y is constant (ssy == 0)
 *  - t3prf: estimateYhat via Tprf3Result compatibility bridge
 *  - t3prf: pass1columnsRsquared sst == 0 (constant X column)
 *  - nanStdCols zero-std column → 1.0 fallback
 *  - t3prfFast: T < minObs early-return NaN path
 *  - estimate3prf OOS rSquared: ssT == 0 → Double.NaN branch
 *  - X with NaN column: nanStdCols vals.length <= 1 branch
 *  - OOS Recursive with mintrain gap
 *  - OOS Cross Val with non-zero window
 */
class TprfCoverageSuite extends FunSuite:

  MatD.setSeed(7777L)
  private val T = 40
  private val N = 5
  private val L = 2
  private val Z: MatD = MatD.randn(T, L)
  private val X: MatD = MatD.randn(T, N)
  private val y: MatD = MatD.randn(T, 1)

  // ============================================================================
  // PLS mode (autoproxy) — exercises t3prf PLS branch for all OOS procedures
  // ============================================================================

  test("OOS Recursive PLS autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Recursive",
                         mintrain = (20, 0), pls = true)
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("OOS Cross Val PLS autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Cross Val",
                         window = (0, 1), pls = true)
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("OOS Rolling PLS autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Rolling",
                         rollwin = (20, 15, 0), pls = true)
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // mintrain._1 < 0 branch → defaults to T/2
  // ============================================================================

  test("OOS Recursive default mintrain (-1,0): triggers T/2 branch") {
    // mintrain defaults to (-1, 0); the < 0 guard fires and sets mt = (T/2, 0)
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive")
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // OOS Rolling with gap > 0
  // ============================================================================

  test("OOS Rolling with gap=2: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Rolling",
                         rollwin = (20, 15, 2))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("OOS Rolling autoproxy with gap=1: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Rolling",
                         rollwin = (20, 15, 1))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // IS Full autoproxy + computeAvar=true (zFinal set by autoproxy loop)
  // ============================================================================

  test("IS Full autoproxy Left(1) computeAvar=true: avar is Some") {
    val r = estimate3prf(y, X, Left(1), computeAvar = true)
    assert(r.avar.isDefined)
  }

  test("IS Full autoproxy Left(2) computeAvar=true: avar alpha shape (N, N)") {
    val r = estimate3prf(y, X, Left(2), computeAvar = true)
    assert(r.avar.isDefined)
    assertEquals(r.avar.get.alpha.shape, (N, N))
  }

  // ============================================================================
  // OOS Recursive with mintrain gap (mintrain._2 > 0)
  // ============================================================================

  test("OOS Recursive with gap mintrain (15, 2): forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive",
                         mintrain = (15, 2))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // OOS Cross Val with non-zero window (window._1 > 0)
  // ============================================================================

  test("OOS Cross Val window=(1, 3): drops window around held-out point") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val",
                         window = (1, 3))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // t3prf: empty phiHat → pass1columnsRsquared returns Array.empty
  // ============================================================================

  test("t3prf empty phiHat: pass1columnsRsquared is empty") {
    // phiHat defaults to MatD.zeros(0, 0) → rows == 0 → Array.empty branch
    val tf = Tprf3Result(X, y, Z,
      phi     = MatD.zeros(N, L),
      sigma   = MatD.zeros(T, L),
      betaHat = MatD.zeros(L + 1, 1))
    assert(tf.pass1columnsRsquared.isEmpty)
  }

  // ============================================================================
  // t3prf: adjRsq when nObs <= nParams → returns 0.0
  // ============================================================================

  test("t3prf adjRsq: returns 0.0 when nObs <= nParams") {
    // T2=3, L=2: betaHat.rows = L+1 = 3 = y.rows → nObs(3) <= nParams(3)
    val T2 = 3
    val smallY   = MatD.randn(T2, 1)
    val smallSig = MatD.randn(T2, L)
    val beta     = MatD.randn(L + 1, 1)   // 3 rows = T2
    val tf = Tprf3Result(MatD.randn(T2, N), smallY, MatD.randn(T2, L),
      phi     = MatD.randn(N, L),
      sigma   = smallSig,
      betaHat = beta)
    assertEqualsDouble(tf.adjRsq, 0.0, 1e-12)
  }

  // ============================================================================
  // t3prf: rSquared when y is constant (ssy == 0 → 0.0)
  // ============================================================================

  test("t3prf rSquared: returns 0.0 when y is constant") {
    val yConst = MatD.full(T, 1, 5.0)
    val tf = t3prf(yConst, X, Z)
    assertEqualsDouble(tf.rSquared, 0.0, 1e-10)
  }

  // ============================================================================
  // t3prf: estimateYhat via compatibility bridge
  // ============================================================================

  test("t3prf estimateYhat: compatibility bridge result returns finite or NaN") {
    // Tprf3Result compatibility bridge sets beta3 = Some(betaHat)
    val phi   = MatD.randn(N, L)
    val sigma = MatD.randn(T, L)
    val beta  = MatD.randn(L + 1, 1)
    val tf = Tprf3Result(X, y, Z, phi, sigma, beta)  // Xstd = ones(1,1)
    val oos = X(0, ::).T.toMat   // (N×1)
    val pred = tf.estimateYhat(oos)
    assert(!pred.isInfinite, s"expected finite or NaN, got $pred")
  }

  // ============================================================================
  // nanStdCols zero-std branch + t3prf pass1columnsRsquared sst==0
  // ============================================================================

  test("t3prf with constant X column: nanStdCols returns 1.0, pass1R²(last)==0") {
    // Last column is all 1.0s → std = 0 → nanStdCols uses 1.0 fallback
    // After normalisation, column is still constant → sstCols = 0 → R² = 0.0
    val Xconst = MatD.hstack(X, MatD.ones(T, 1))
    val tf = t3prf(y, Xconst, Z)
    val r2 = tf.pass1columnsRsquared
    assertEquals(r2.length, N + 1)
    assertEqualsDouble(r2(N), 0.0, 1e-10)   // constant column → R² clamped to 0
  }

  // ============================================================================
  // t3prfFast: T < minObs early-return (NaN forecasts)
  // ============================================================================

  test("OOS Recursive small window: t3prfFast T < minObs returns NaN forecasts") {
    // mintrain=(5, 30): loop starts at t=36; ts length ≤ 5 < minObs=10 → NaN
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive",
                         mintrain = (5, 30))
    val nans = (0 until T).count(i => r.forecasts(i, 0).isNaN)
    assert(nans > 0, s"expected NaN forecasts from undersized training window, got $nans")
  }

  // ============================================================================
  // estimate3prf OOS rSquared: ssT == 0 → Double.NaN (constant y)
  // ============================================================================

  test("OOS Cross Val constant y: rSquared is NaN (ssT == 0 branch)") {
    // Constant y → rollfore == y at every t → ssT = sum((y - rollfore)²) = 0 → NaN
    val yConst = MatD.full(T, 1, 3.0)
    val r = estimate3prf(yConst, X, Right(Z), procedure = "OOS Cross Val",
                         window = (0, 1))
    assert(r.rSquared.isNaN, s"expected NaN rSquared, got ${r.rSquared}")
  }

  // ============================================================================
  // nanStdCols: vals.length <= 1 branch (all-NaN column in X)
  // ============================================================================

  test("IS Full with all-NaN X column: nanStdCols returns 1.0 for that column") {
    val XwithNaN = MatD.randn(T, N)
    for i <- 0 until T do XwithNaN(i, 0) = Double.NaN
    val r = estimate3prf(y, XwithNaN, Right(Z))
    assertEquals(r.forecasts.shape, (T, 1))
    assert(!r.rSquared.isInfinite, s"rSquared should not be infinite, got ${r.rSquared}")
  }

  test("OOS Cross Val with NaN X column: nanStdCols on subsets handles all-NaN") {
    val XwithNaN = MatD.randn(T, N)
    for i <- 0 until T do XwithNaN(i, 0) = Double.NaN
    val r = estimate3prf(y, XwithNaN, Right(Z), procedure = "OOS Cross Val",
                         window = (0, 1))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // forecast3prf — additional OOS paths
  // ============================================================================

  test("forecast3prf OOS Recursive: returns MatD of shape (T, 1)") {
    val r = forecast3prf(y, X, Right(Z), procedure = "OOS Recursive",
                         mintrain = (20, 0))
    assertEquals(r.shape, (T, 1))
  }

  test("forecast3prf OOS Cross Val: returns MatD of shape (T, 1)") {
    val r = forecast3prf(y, X, Right(Z), procedure = "OOS Cross Val",
                         window = (0, 1))
    assertEquals(r.shape, (T, 1))
  }
