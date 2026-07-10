#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.14.1

// Compile-checks the example code in docs/ReferenceGuide.md and docs/QuickStartGuide.md.
// Each block below mirrors a documented snippet; if this script compiles and runs,
// the documented API calls are real.

import uni.data.*

object docCheck {
  def println(s: String = ""): Unit = print(s"$s\n")

  def creation(): Unit =
    val v  = Mat[Double](1.0, 2.0, 3.0)
    val v2 = MatD(1.0, 2.0, 3.0)
    val r  = MatD.row(1, 2, 3)
    val z  = MatD(3, 4)
    val p  = MatD(3.0, 4.0)
    assert(v.shape == (3, 1) && v2.shape == (3, 1) && r.shape == (1, 3))
    assert(z.shape == (3, 4) && z.sum == 0.0 && p.shape == (2, 1))
    val a = MatD.arange(0, 10)
    val b = MatD.arange(0.0, 1.0, 0.25)
    val c = MatD.linspace(0.0, 1.0, 5)
    val d = MatD.fromSeq(Seq(1.0, 2.0))
    val e = MatD(2, 3, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    assert(a.shape == (10, 1) && b.shape == (4, 1) && c.shape == (5, 1))
    assert(d.shape == (2, 1) && e.shape == (2, 3))

  def indexing(): Unit =
    val m = MatD((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val x = m(0, 1)
    val y = m(-1, -1)
    assert(x == 2.0 && y == 9.0)
    m(0, 0) = 10.0
    val row1: RVecD = m(1, ::)
    val col2: CVecD = m(::, 2)
    val sub  = m(0 until 2, 1 until 3)
    val all  = m(::)
    assert(row1.shape == (1, 3) && col2.shape == (3, 1) && sub.shape == (2, 2) && all.shape == (3, 3))
    val picked = m(Array(2, 0), ::)
    assert(picked(0, 0) == 7.0)
    val mask = m > 5.0
    val hits = m(mask)
    m(mask) = 0.0
    assert(hits.size > 0)
    m(0 until 2, ::) = 1.0
    assert(m(1, 2) == 1.0)
    // m(...) slicing copies; m.slice is a zero-copy view sharing parent storage
    val m2 = MatD((1, 2), (3, 4))
    val cp = m2(0 until 2, 0 until 2)
    cp(0, 0) = 99.0
    assert(m2(0, 0) == 1.0)
    val vw = m2.slice(0 until 2, 0 until 2)
    vw(0, 0) = 99.0
    assert(m2(0, 0) == 99.0)

  def arithmetic(): Unit =
    val A = MatD((1, 2), (3, 4))
    val B = MatD((5, 6), (7, 8))
    val sum  = A + B
    val diff = A - B
    val had  = A * B
    val quot = A / B
    val neg  = -A
    val s1 = A * 2.0
    val s2 = 2.0 * A
    val s3 = A + 10.0
    val P  = A *@ B
    val P2 = A.dot(B)
    assert(P.allclose(P2))
    val sq    = A ~^ 2
    val roots = A ~^ 0.5
    val ones  = A ~^ 0
    assert(ones.sum == 4.0 && sq(1, 1) == 16.0)
    assert(sum.size + diff.size + had.size + quot.size + neg.size > 0)
    assert(s1.size + s2.size + s3.size > 0)
    val C = MatD((1, 2), (3, 4))
    C :+= 1.0
    C :-= B
    C :*= 2.0
    C :/= 4.0

  def broadcasting(): Unit =
    val m = MatD((1, 2, 3), (4, 5, 6))
    val rowV = MatD.row(10, 20, 30)
    val colV = MatD(100.0, 200.0)
    val a = m + rowV
    val b = m + colV
    assert(a(0, 0) == 11.0 && b(1, 0) == 204.0)
    val centered = m - m.mean(axis = 0)
    assert(math.abs(centered.mean) < 1e-12)
    val big = rowV.broadcastTo(4, 3)
    assert(big.shape == (4, 3))

  def linearAlgebra(): Unit =
    val A = MatD((4, 2), (2, 3))
    val At   = A.T
    val inv  = A.inverse
    val det  = A.determinant
    val tr   = A.trace
    val dia  = A.diagonal
    val nrm  = MatD(3.0, 4.0).norm
    val frob = A.norm("fro")
    assert(det == 8.0 && tr == 7.0 && dia.length == 2 && nrm == 5.0 && frob > 0)
    assert(At.shape == (2, 2) && inv.shape == (2, 2))
    val b = MatD(1.0, 2.0)
    val x = A.solve(b)
    val (w, residuals, rank, sv) = A.lstsq(b)
    assert(x.shape == (2, 1) && w.shape == (2, 1) && rank == 2 && sv.length == 2 && residuals.size >= 0)
    val (q, r2)      = A.qrDecomposition
    val (u, s, vt)   = A.svd
    val l            = A.cholesky
    val (re, im, v)  = A.eig
    val ev           = A.eigenvalues()
    assert(q.shape == (2, 2) && r2.shape == (2, 2) && s.length == 2)
    assert(u.size + vt.size + l.size + v.size > 0 && re.length == 2 && im.length == 2 && ev.length == 2)

  def statistics(): Unit =
    val m = MatD((1, 2), (3, 4), (5, 6))
    val s   = m.sum
    val mu  = m.mean
    val sd  = m.std
    val va  = m.variance
    val med = m.median
    val p90 = m.percentile(90)
    val lo  = m.min
    val hi  = m.max
    assert(s == 21.0 && mu == 3.5 && lo == 1.0 && hi == 6.0)
    assert(sd > 0 && va > 0 && med > 0 && p90 > 0)
    val colSums  = m.sum(axis = 0)
    val rowMeans = m.mean(axis = 1)
    val colStd   = m.std(axis = 0)
    assert(colSums.shape == (1, 2) && rowMeans.shape == (3, 1) && colStd.shape == (1, 2))
    val (minR, minC) = m.argmin
    val (maxR, maxC) = m.argmax
    assert((minR, minC) == (0, 0) && (maxR, maxC) == (2, 1))
    val c   = m.cov
    val cs  = m.cumsum
    val srt = m.sort()
    val ord = m.argsort()
    val (vals, counts) = m.unique
    assert(c.shape == (3, 3) && cs.size == 6 && srt.size == 6 && ord.size == 6)
    assert(vals.length == 6 && counts.forall(_ == 1))

  def elementWise(): Unit =
    val m = MatD.randn(5, 4)
    val a  = m.abs
    val sq = m.abs.sqrt
    val ex = m.exp
    val ln = m.abs.log
    val l1 = m.abs.log10
    val si = m.sin
    val co = m.cos
    val th = m.tanh
    val r0 = m.round()
    val r2 = m.round(decimals = 2)
    val fl = m.floor
    val ce = m.ceil
    val tc = m.trunc
    val cl = m.clip(-1.0, 1.0)
    val mx = m.maximum(0.0)
    val mn = m.minimum(MatD.zeros(5, 4))
    val f1 = m.map(x => x * x + 1)
    val f2 = m.mapParallel(x => math.expm1(x))
    assert(Seq(a, sq, ex, ln, l1, si, co, th, r0, r2, fl, ce, tc, cl, mx, mn, f1, f2).forall(_.size == 20))

  def ml(): Unit =
    val m = MatD.randn(5, 4)
    val sg = m.sigmoid
    val re = m.relu
    val lk = m.leakyRelu(alpha = 0.01)
    val sm = m.softmax(axis = 1)
    val ge = m.gelu
    val dr = m.dropout(p = 0.5, training = true)
    assert(Seq(sg, re, lk, sm, ge, dr).forall(_.size == 20))

  def rng(): Unit =
    MatD.setSeed(42)
    val u  = MatD.rand(3, 4)
    val n  = MatD.randn(3, 4)
    val nv = MatD.randn(5)
    val ab = MatD.uniform(-1.0, 1.0, 3, 4)
    val g  = MatD.normal(5.0, 2.0, 3, 4)
    val k  = MatD.randint(0, 10, 3, 4)
    assert(u.size == 12 && n.size == 12 && nv.shape == (5, 1) && ab.size == 12 && g.size == 12 && k.size == 12)

  def dataManipulation(): Unit =
    val m  = MatD.randn(4, 6)
    val m1 = MatD.randn(2, 6)
    val rs = m.reshape(6, 4)
    val fl: Array[Double] = m.flatten
    val rv = m.ravel
    val cv = m.toColVec
    assert(rs.shape == (6, 4) && fl.length == 24 && rv.shape == (1, 24) && cv.shape == (24, 1))
    val snapshot = m.copy
    val snap2    = m.matCopy
    assert(snapshot.allclose(snap2))
    val tall  = MatD.vstack(m, m1)
    val wide  = MatD.hstack(m, m)
    val rowsP = tall.vsplit(3)
    val colsP = m.hsplit(Array(2, 4))
    val byAx  = m.split(2, axis = 1)
    assert(tall.shape == (6, 6) && wide.shape == (4, 12))
    assert(rowsP.length == 3 && colsP.length == 3 && byAx.length == 2)
    val rep = m.repeat(3, axis = 0)
    val til = m.tile(2, 3)
    assert(rep.shape == (12, 6) && til.shape == (8, 18))
    val pos = m.filterRows(row => row(0, 0) > 0)
    val top = m.head(2)
    val bot = m.tail(2)
    assert(top.shape == (2, 6) && bot.shape == (2, 6) && pos.cols == 6)

  def booleanOps(): Unit =
    val m = MatD((1, 2), (3, 4))
    val gt = m > 2.0
    val ge = m >= 2
    val le = m <= 3.0
    val lt = m < 2
    val eq = m :== 4.0
    val ne = m :!= 4.0
    val in = m.between(2.0, 3.0)
    assert(Seq(gt, ge, le, lt, eq, ne, in).forall(_.size == 4))
    assert(gt.toArray.toSeq == m.gt(2.0).toArray.toSeq)
    assert(le.toArray.toSeq == m.lte(3.0).toArray.toSeq)
    val both   = m > 1.0 && m < 4.0      // && binds looser than comparisons — no parens
    val either = m < 2.0 || m > 3.0
    val nope   = !both
    assert(both.sum == 2 && either.sum == 2 && nope.sum == 2)
    val anyBig  = (m > 3.0).any
    val allPos  = (m > 0.0).all
    val colsAll = (m > 0.0).all(axis = 0)
    val nTrue   = (m > 1.0).sum
    assert(anyBig && allPos && colsAll.size == 2 && nTrue == 3)
    val r = Mat.where(m > 2.0, 1.0, 0.0)
    assert(r.sum == 2.0)
    val nan  = m.isnan
    val inf  = m.isinf
    val fin  = m.isfinite
    val has  = m.containsNaN
    val safe = m.nanToNum(nan = 0.0)
    val near = m.allclose(m, rtol = 1e-5, atol = 1e-8)
    assert(!nan.any && !inf.any && fin.all && !has && safe.size == 4 && near)

  def display(): Unit =
    val m = MatD.randn(5, 7)
    val s1 = m.show
    val s2 = m.show("%.2f")
    assert(s1.nonEmpty && s2.nonEmpty)
    Mat.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)

  def quickStartExtras(): Unit =
    val A = Mat[Double]((1, 2), (3, 4))
    val bb = Mat[Double](1.0, 2.0)
    val xx = A.solve(bb)
    assert(xx.shape == (2, 1))
    val mr = Mat.randn(5, 4)
    assert(mr.size == 20)
    val band = mr >= -1.0 && mr <= 1.0
    val outside = !band
    assert(band.sum + outside.sum == 20)
    val squared = A ~^ 2
    assert(squared(1, 1) == 16.0)
    // tuple-factory element conversion (v0.14.0): mixed numeric types convert, not cast
    val tf1 = MatD((1.0, 2.5f), (3L, 4))
    val tf2 = MatB((1, 2.5))
    val tf3 = MatF((1.5, 2))
    assert(tf1(0, 1) == 2.5 && tf1(1, 0) == 3.0)
    assert(tf2(0, 1) == Big(2.5) && tf3(0, 0) == 1.5f)

  def main(args: Array[String]): Unit =
    creation(); indexing(); arithmetic(); broadcasting(); linearAlgebra()
    statistics(); elementWise(); ml(); rng(); dataManipulation()
    booleanOps(); display(); quickStartExtras()
    println("all doc examples OK")
}