package uni.stats

import munit.FunSuite
import uni.data.*
import uni.stats.Tprf3.*

/** Coverage suite for uni.stats.Tprf3: Lm, tprfClosedForm, t3prf, estimate3prf,
 *  forecast3prf, and all four OOS procedures. */
class TprfSuite extends FunSuite:

  // Fixed seed + shared dataset — created once at class init.
  MatD.setSeed(1234L)
  private val T = 50
  private val N = 6
  private val L = 2
  private val Z: MatD = MatD.randn(T, L)
  private val X: MatD = MatD.randn(T, N)
  private val y: MatD = MatD.randn(T, 1)

  // All three accept raw (unnormalized) X and normalize internally (nanStdCols / nanstd).
  // Given the same X, y, Z they produce numerically identical y_hat / residuals / rSquared.
  //
  // td  — tprfClosedForm: closed-form projection via alpha_hat_factor (K&P matrix formula).
  //       K&P variables: Phi (N×L pass-1), Sigma (T×L pass-2), beta ([iota Sigma]\y),
  //       yhat = [iota Sigma]*beta. Also retains Szz, beta_hat as K&P closed-form intermediates.
  //
  // tf  — t3prf: K&P's t3prf vectorized (batch matrix solves replace N+T OLS loops).
  //       Same Phi, Sigma, beta, yhat. beta3 stored in result.
  //
  // rIS — estimate3prf IS Full: K&P estimate3PRF(y,X,Z,'type','IS Full').
  //       Computes forecasts via runT3prf path; additionally computes pointests.alpha (N×1).
  private lazy val td:  Tprf3Result = tprfClosedForm(y, X, Z)
  private lazy val tf:  Tprf3Result = t3prf(y, X, Z)
  private lazy val rIS: Tprf3Result = estimate3prf(y, X, Right(Z))

  // ============================================================================
  // Lm — NaN-aware OLS
  // ============================================================================

  test("Lm: exact OLS recovers slope and intercept") {
    val xv = MatD.col(1.0, 2.0, 3.0, 4.0, 5.0)
    val yv = MatD.col(3.0, 5.0, 7.0, 9.0, 11.0)  // 2x + 1
    val lm = Lm(yv, xv)
    assertEqualsDouble(lm.intercept_, 1.0, 1e-8)
    assertEqualsDouble(lm.coef_(0, 0), 2.0, 1e-8)
    assertEqualsDouble(lm.rSquared, 1.0, 1e-8)
  }

  test("Lm: addIntercept=false recovers slope-only") {
    val xv = MatD.col(1.0, 2.0, 3.0, 4.0, 5.0)
    val yv = MatD.col(2.0, 4.0, 6.0, 8.0, 10.0)  // 2x, no intercept
    val lm = Lm(yv, xv, addIntercept = false)
    assertEqualsDouble(lm.coef_(0, 0), 2.0, 1e-8)
    assertEquals(lm.intercept_, 0.0)
    assertEquals(lm.nCols, 1)
  }

  test("Lm: NaN rows are silently dropped") {
    val xv = MatD.col(1.0, 2.0, 3.0, 4.0, 5.0)
    val yv = MatD.col(3.0, Double.NaN, 7.0, 9.0, 11.0)  // row 1 is NaN
    val lm = Lm(yv, xv)
    assertEquals(lm.nObs, 4)
    assertEqualsDouble(lm.rSquared, 1.0, 1e-6)
  }

  test("Lm: adjRs is 0.0 when dfResid <= 0") {
    // 2 obs, 2 params (intercept + 1 predictor) → dfResid = 0
    val xv = MatD.col(1.0, 2.0)
    val yv = MatD.col(3.0, 5.0)
    val lm = Lm(yv, xv)
    assertEquals(lm.dfResid, 0)
    assertEqualsDouble(lm.adjRs, 0.0, 1e-12)
  }

  test("Lm: rSquared is 0.0 when y is constant (ssy == 0 branch)") {
    val xv = MatD.col(1.0, 2.0, 3.0)
    val yv = MatD.col(5.0, 5.0, 5.0)
    assertEqualsDouble(Lm(yv, xv).rSquared, 0.0, 1e-10)
  }

  test("Lm: nParams includes intercept column") {
    val xv = MatD.col(1.0, 2.0, 3.0, 4.0)
    val yv = MatD.col(3.0, 5.0, 7.0, 9.0)
    val lm = Lm(yv, xv)
    assertEquals(lm.nParams, 2)  // intercept + 1 predictor
  }

  // ============================================================================
  // tprfClosedForm — shapes and basic properties
  // ============================================================================

  test("tprfClosedForm: y_hat shape is (T, 1)") {
    assertEquals(td.y_hat.shape, (T, 1))
  }

  test("tprfClosedForm: residuals shape is (T, 1)") {
    assertEquals(td.residuals.shape, (T, 1))
  }

  test("tprfClosedForm: coefficients shape is (L, 1)") {
    assertEquals(td.coefficients.shape, (L, 1))
  }

  test("tprfClosedForm: phi shape is (N, L)") {
    assertEquals(td.phi.shape, (N, L))
  }

  test("tprfClosedForm: sigma shape is (T, L)") {
    assertEquals(td.sigma.shape, (T, L))
  }

  test("tprfClosedForm: n == T and df == L") {
    assertEquals(td.n, T)
    assertEqualsDouble(td.df, L.toDouble, 1e-12)
  }

  test("tprfClosedForm: pass1columnsRsquared has N entries, each in [0, 1]") {
    val r2 = td.pass1columnsRsquared
    assertEquals(r2.length, N)
    assert(r2.forall(v => v >= 0.0 && v <= 1.0 + 1e-10),
      s"out-of-range R²: ${r2.mkString(", ")}")
  }

  test("tprfClosedForm: rSquared is at most 1.0") {
    assert(td.rSquared <= 1.0 + 1e-10)
  }

  test("tprfClosedForm: rSquared is 0.0 when y is constant (ssy == 0 branch)") {
    val yConst = MatD.full(T, 1, 7.0)
    assertEqualsDouble(tprfClosedForm(yConst, X, Z).rSquared, 0.0, 1e-10)
  }

  test("tprfClosedForm: degreesOfFreedom is positive") {
    val df = td.degreesOfFreedom
    assert(df > 0.0, s"expected positive df, got $df")
  }

  test("tprfClosedForm: toString contains 'residuals' and 'y_hat'") {
    val s = td.toString
    assert(s.contains("residuals"), s)
    assert(s.contains("y_hat"), s)
  }

  // =================================
  // t3prf — shapes and properties
  // =================================

  test("t3prf: phi shape is (N, L)") {
    assertEquals(tf.phi.shape, (N, L))
  }

  test("t3prf: sigma shape is (T, L)") {
    assertEquals(tf.sigma.shape, (T, L))
  }

  test("t3prf: y_hat shape is (T, 1)") {
    assertEquals(tf.y_hat.shape, (T, 1))
  }

  test("t3prf: coefficients shape is (L, 1)") {
    assertEquals(tf.coefficients.shape, (L, 1))
  }

  test("t3prf: rSquared <= 1.0") {
    assert(tf.rSquared <= 1.0 + 1e-10)
  }

  test("t3prf: adjRsq <= rSquared") {
    assert(tf.adjRsq <= tf.rSquared + 1e-10)
  }

  test("t3prf: intercept is finite") {
    assert(tf.intercept.isFinite, s"intercept was ${tf.intercept}")
  }

  test("t3prf: pass1columnsRsquared has N entries in [0, 1]") {
    val r2 = tf.pass1columnsRsquared
    assertEquals(r2.length, N)
    assert(r2.forall(v => v >= 0.0 && v <= 1.0 + 1e-10),
      s"out-of-range R²: ${r2.mkString(", ")}")
  }

  test("t3prf: estimateYhat(oos) returns a finite Double") {
    // Use tf.X (which is Xn) instead of the raw X
    val oos = tf.X(0, ::).T
    assert(tf.estimateYhat(oos).isFinite)
  }

  test("t3prf: residuals shape is (T, 1)") {
    assertEquals(tf.residuals.shape, (T, 1))
  }

  test("t3prf: toString contains 'residuals' and 'y_hat'") {
    val s = tf.toString
    assert(s.contains("residuals"), s)
    assert(s.contains("y_hat"), s)
  }

  // ============================================================================
  // estimate3prf — IS Full (explicit Z)
  // ============================================================================

  test("estimate3prf IS Full: forecasts shape is (T, 1)") {
    assertEquals(rIS.forecasts.shape, (T, 1))
  }

  test("estimate3prf IS Full: residuals shape is (T, 1)") {
    assertEquals(rIS.residuals.shape, (T, 1))
  }

  test("estimate3prf IS Full: no NaN in forecasts") {
    val nans = (0 until T).count(i => rIS.forecasts(i, 0).isNaN)
    assertEquals(nans, 0)
  }

  test("estimate3prf IS Full: rSquared <= 1.0") {
    assert(rIS.rSquared <= 1.0 + 1e-10)
  }

  test("estimate3prf IS Full: alpha is Some with shape (N, 1)") {
    assert(rIS.alpha.isDefined)
    assertEquals(rIS.alpha.get.shape, (N, 1))
  }

  test("estimate3prf IS Full: encnew is NaN (set only for OOS Recursive)") {
    assert(rIS.encnew.isNaN)
  }

  test("estimate3prf IS Full computeAvar=true: avar shapes correct") {
    val r = estimate3prf(y, X, Right(Z), computeAvar = true)
    assert(r.avar.isDefined)
    assertEquals(r.avar.get.alpha.shape, (N, N))
    assertEquals(r.avar.get.forecasts.shape, (T, T))
  }

  // ============================================================================
  // estimate3prf — IS Full (autoproxy)
  // ============================================================================

  test("estimate3prf IS Full autoproxy Left(1): forecasts shape (T, 1)") {
    assertEquals(estimate3prf(y, X, Left(1)).forecasts.shape, (T, 1))
  }

  test("estimate3prf IS Full autoproxy Left(2): forecasts shape (T, 1)") {
    assertEquals(estimate3prf(y, X, Left(2)).forecasts.shape, (T, 1))
  }

  test("estimate3prf IS Full PLS autoproxy: forecasts shape (T, 1)") {
    assertEquals(estimate3prf(y, X, Left(1), pls = true).forecasts.shape, (T, 1))
  }

  // ============================================================================
  // estimate3prf — OOS Recursive
  // ============================================================================

  test("estimate3prf OOS Recursive: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (30, 0))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("estimate3prf OOS Recursive: has non-NaN forecasts") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (30, 0))
    val nonNan = (0 until T).count(i => !r.forecasts(i, 0).isNaN)
    assert(nonNan > 0, s"expected some non-NaN forecasts, got 0")
  }

  test("estimate3prf OOS Recursive: encnew is finite or NaN (not infinite)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (30, 0))
    assert(!r.encnew.isInfinite, s"encnew was infinite: ${r.encnew}")
  }

  test("estimate3prf OOS Recursive autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Recursive", mintrain = (30, 0))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // estimate3prf — OOS Cross Val
  // ============================================================================

  test("estimate3prf OOS Cross Val: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (0, 1))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("estimate3prf OOS Cross Val: all T forecasts are non-NaN") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (0, 1))
    val nans = (0 until T).count(i => r.forecasts(i, 0).isNaN)
    assertEquals(nans, 0, s"expected 0 NaN forecasts, got $nans")
  }

  test("estimate3prf OOS Cross Val autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Cross Val", window = (0, 1))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // estimate3prf — OOS Rolling
  // ============================================================================

  test("estimate3prf OOS Rolling: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Rolling", rollwin = (30, 20, 0))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  test("estimate3prf OOS Rolling: has non-NaN forecasts") {
    val r = estimate3prf(y, X, Right(Z), procedure = "OOS Rolling", rollwin = (30, 20, 0))
    val nonNan = (0 until T).count(i => !r.forecasts(i, 0).isNaN)
    assert(nonNan > 0, s"expected some non-NaN forecasts, got 0")
  }

  test("estimate3prf OOS Rolling autoproxy: forecasts shape (T, 1)") {
    val r = estimate3prf(y, X, Left(1), procedure = "OOS Rolling", rollwin = (30, 20, 0))
    assertEquals(r.forecasts.shape, (T, 1))
  }

  // ============================================================================
  // estimate3prf — error handling
  // ============================================================================

  test("estimate3prf: unknown procedure throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      estimate3prf(y, X, Right(Z), procedure = "bad")
    }
  }

  // ============================================================================
  // forecast3prf — simplified wrapper
  // ============================================================================

  test("forecast3prf IS Full: matches estimate3prf forecasts exactly") {
    val r1 = estimate3prf(y, X, Right(Z)).forecasts
    val r2 = forecast3prf(y, X, Right(Z))
    assertEquals(r1.shape, r2.shape)
    for i <- 0 until T do
      assertEqualsDouble(r1(i, 0), r2(i, 0), 1e-10)
  }

  // ============================================================================
  // Case class construction and defaults
  // ============================================================================

  test("AvarEstimates: plain case class fields") {
    val a = AvarEstimates(MatD.eye(3), MatD.eye(2))
    assertEquals(a.alpha.shape, (3, 3))
    assertEquals(a.forecasts.shape, (2, 2))
  }

  test("Tprf3Result: default optional fields") {
    val r = Tprf3Result(MatD.zeros(5, 1), MatD.zeros(5, 1), rSquared = 0.42)
    assert(r.encnew.isNaN)
    assert(r.beta3.isEmpty)
    assert(r.alpha.isEmpty)
    assert(r.avar.isEmpty)
    assertEquals(r.rollfore.shape, (1, 1))
    assertEqualsDouble(r.rSquared, 0.42, 1e-12)
  }
