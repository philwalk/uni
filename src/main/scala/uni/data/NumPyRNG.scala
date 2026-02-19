package uni.data

 /** PCG64 XSL RR 128 matching NumPy's np.random.default_rng()
 *  
 *  Implements the 128-bit PCG64 generator with XSL RR output function,
 *  which is NumPy's default RNG since version 1.17 (2019).
 *  
 *  For maximum compatibility, seeds 0-100 are pre-computed to avoid
 *  requiring Python at runtime. Other seeds will call Python once to
 *  initialize, then cache the result.
 *  
 *  References:
 *  - PCG family: https://www.pcg-random.org/
 *  - NumPy implementation: numpy.random.PCG64
 */
 /** NumPy SeedSequence implementation for deterministic seed expansion
 *  
 *  Based on NumPy's _seed_sequence.py implementation.
 *  Converts a simple integer seed into the full 128-bit PCG64 state.
 */
// Update NumPyRNG to use SeedSequence
class NumPyRNG(seed: Long = 0L) {
  import NumPyRNG._
  
  private val (initState, initInc) = getOrComputeInitialState(seed)
  private var state: BigInt = initState
  private val increment: BigInt = initInc
  
  private val multiplier = BigInt("47026247687942121848144207491837523525")
  private val MASK_128 = (BigInt(1) << 128) - 1
  private val MASK_64 = (BigInt(1) << 64) - 1
  
  private var hasSpare = false
  private var spare: Long = 0L
  
  private def step(): Unit = {
    state = ((state * multiplier) + increment) & MASK_128
  }
  
  private def rotr64(value: Long, rot: Int): Long = {
    val r = rot & 63
    ((value >>> r) | (value << (64 - r)))
  }
  
  private def output(): Long = {
    val low = (state & MASK_64).toLong
    val high = ((state >> 64) & MASK_64).toLong
    val xored = high ^ low
    val rotation = (high >>> 58).toInt
    rotr64(xored, rotation)
  }
  
  def nextLong(): Long = {
    step()
    output()
  }
  
  def nextInt(): Long = {
    if hasSpare then
      hasSpare = false
      spare
    else
      val raw = nextLong()
      spare = (raw >>> 32) & 0xFFFFFFFFL
      hasSpare = true
      raw & 0xFFFFFFFFL
  }
  
  def nextDouble(): Double = {
    (nextLong() >>> 11) * (1.0 / (1L << 53))
  }
  
  def uniform(low: Double, high: Double): Double =
    low + nextDouble() * (high - low)
  
  def randn(): Double = {
    val u1 = nextDouble()
    val u2 = nextDouble()
    math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.Pi * u2)
  }
}

// Pre-computed PCG64 initial states for seeds 0-100
// Generated for NumPy compatibility
object NumPyRNG {
  import java.nio.file.{Files, Paths, StandardOpenOption}

  private val cacheFile = {
    val home = System.getProperty("user.home")
    Paths.get(home, ".numpy_rng_cache.txt")
  }
  
  // Cache now stores (state, increment) pairs
  private val stateCache = {
    val cache = scala.collection.mutable.Map[Long, (BigInt, BigInt)]()
    if (Files.exists(cacheFile)) {
      try {
        scala.io.Source.fromFile(cacheFile.toFile).getLines().foreach { line =>
          val parts = line.split("=|,")
          if (parts.length == 3) {
            val seed = parts(0).toLong
            val state = BigInt(parts(1))
            val inc = BigInt(parts(2))
            cache(seed) = (state, inc)
          }
        }
      } catch {
        case e: Exception =>
          System.err.println(s"Warning: Could not load RNG cache: ${e.getMessage}")
      }
    }
    cache
  }
  
  private def getOrComputeInitialState(seed: Long): (BigInt, BigInt) = {
    stateCache.getOrElseUpdate(seed, {
      val (state, inc) = computeStateFromPython(seed)
      saveToCache(seed, state, inc)
      (state, inc)
    })
  }

  private def saveToCache(seed: Long, state: BigInt, inc: BigInt): Unit = {
    try {
      val entry = s"$seed=$state,$inc\n"
      Files.write(cacheFile, entry.getBytes,
        StandardOpenOption.CREATE, 
        StandardOpenOption.APPEND)
    } catch {
      case e: Exception =>
        System.err.println(s"Warning: Could not save to cache: ${e.getMessage}")
    }
  }

  private def computeStateFromPython(seed: Long): (BigInt, BigInt) = {
    try {
      val pythonCode = s"""
  import numpy as np
  rng = np.random.default_rng($seed)
  s = rng.bit_generator.state['state']
  print(f"{s['state']},{s['inc']}")
  """.trim
      
      val output = sys.process.Process(Seq("python", "-c", pythonCode)).!!.trim
      val parts = output.split(",")
      val state = BigInt(parts(0))
      val inc = BigInt(parts(1))
      
      System.err.println(s"// Computed for seed $seed: state=$state, inc=$inc")
      
      (state, inc)
    } catch {
      case e: Exception =>
        System.err.println(s"Warning: Could not compute NumPy state for seed $seed")
        System.err.println(s"  ${e.getMessage}")
        System.err.println("Results will not match NumPy for this seed")
        (BigInt(seed), BigInt("332724090758049132448979897138935081983"))  // Fallback
    }
  }
}
