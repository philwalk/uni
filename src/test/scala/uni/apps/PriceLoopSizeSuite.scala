package uni.apps

import java.io.DataInputStream

/** C2 could not compile the price loop as one method: it ran out of nodes after a minute, and every
  * session meanwhile ran profiled tier-3 code, an order of magnitude slower on every core.  The loop
  * and the derived channels therefore run each session as phase methods (`PriceRun`, `ChannelRun`)
  * that C2 compiles within the first paths.  Nothing else notices a phase growing back: every test
  * still passes, the read just slows.  Past `PhaseLimit`, split the phase. */
class PriceLoopSizeSuite extends munit.FunSuite:
  private val HugeMethodLimit = 8000
  private val Headroom        = 1000
  // twice the largest phase when the loop was split
  private val PhaseLimit      = 2500

  /** Every method's name and `Code` length, read from the class file itself. */
  private def codeLengths(in: DataInputStream): Vector[(String, Int)] =
    in.readNBytes(8)                                   // magic, minor, major
    val cpCount = in.readUnsignedShort()
    val utf = new Array[String](cpCount)
    var k = 1
    while k < cpCount do
      in.readUnsignedByte() match
        case 1                    => utf(k) = in.readUTF()
        case 3 | 4                => in.readNBytes(4)
        case 5 | 6                => in.readNBytes(8); k += 1   // long and double take two slots
        case 7 | 8 | 16 | 19 | 20 => in.readNBytes(2)
        case 15                   => in.readNBytes(3)
        case _                    => in.readNBytes(4)           // refs, name-and-type, dynamics
      k += 1
    in.readNBytes(6)                                   // access, this, super
    in.readNBytes(2 * in.readUnsignedShort())          // interfaces
    def member(): (String, Int) =
      in.readNBytes(2)
      val name = utf(in.readUnsignedShort())
      in.readNBytes(2)
      val lens = Vector.fill(in.readUnsignedShort()):
        val attr = utf(in.readUnsignedShort())
        val len  = in.readInt()
        if attr == "Code" then
          in.readNBytes(4)                             // max stack, max locals
          val code = in.readInt()
          in.readNBytes(len - 8)
          code
        else
          in.readNBytes(len)
          0
      (name, lens.sum)
    Vector.fill(in.readUnsignedShort())(member())     // fields
    Vector.fill(in.readUnsignedShort())(member())

  private def sizesOf(cls: String): Vector[(String, Int)] =
    val stream = getClass.getResourceAsStream(s"/uni/apps/$cls.class")
    assert(stream != null, s"$cls.class is not on the test classpath")
    val in = new DataInputStream(stream)
    try codeLengths(in) finally in.close()

  test("every session phase of the price loop and the channels stays small enough for C2"):
    for cls <- Vector("MarketSim$PriceRun", "MarketSim$ChannelRun") do
      val sizes = sizesOf(cls)
      assert(sizes.exists(_._1 == "run"), s"$cls has no run")
      for (name, len) <- sizes do
        val limit = if name == "<init>" then HugeMethodLimit - Headroom else PhaseLimit
        assert(len <= limit, s"$cls.$name is $len bytes of bytecode, over $limit")
