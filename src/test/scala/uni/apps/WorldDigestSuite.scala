package uni.apps

import munit.FunSuite

/**
 * The world digest (`worldDigest`, `-digest`) and the build identity (`-buildid`): a consumer keys a
 * cache on them, so the digest must not move with the language that wrote the file, and it must see
 * what the sidecar's six significant digits cannot. The Rust twin pins the same literals
 * (`the_world_digest_is_pinned_across_the_twins`).
 */
class WorldDigestSuite extends FunSuite:

  test("the world digest is pinned across the twins") {
    assertEquals(MarketSim.worldDigest(MarketSim.Defaults), "517685b907e693d8")
    val nq = MarketSim.namedWorld("0.24.5-nasdaq").map(_._1).getOrElse(fail("recipe"))
    assertEquals(MarketSim.worldDigest(nq), "5186010bf57e2f5a")
  }

  test("one ulp in one dial moves the digest, where the sidecar's text cannot") {
    val w = MarketSim.Defaults
    val v = w.copy(drift = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(w.drift) + 1))
    assertEquals(MarketSim.worldJsonBody(w), MarketSim.worldJsonBody(v))
    assertNotEquals(MarketSim.worldDigest(w), MarketSim.worldDigest(v))
  }

  test("every recipe digests apart, and the build id reads VERSION+ sixteen hex digits") {
    val digests = MarketSim.Recipes.map((n, w, _) => (MarketSim.worldDigest(w), n))
    val shared  = digests.groupBy(_._1).values.filter(_.size > 1).map(_.map(_._2)).toVector
    assertEquals(shared, Vector.empty, s"recipes sharing a digest: $shared")
    val tail = MarketSim.buildId.stripPrefix(s"${MarketSim.Version}+")
    assert(tail.length == 16 && tail.forall(c => "0123456789abcdef".contains(c)), MarketSim.buildId)
  }
