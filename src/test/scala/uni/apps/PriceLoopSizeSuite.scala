package uni.apps

import java.io.DataInputStream

/** HotSpot never compiles a method of more than 8000 bytes of bytecode (`DontCompileHugeMethods`),
  * and the price loop is the method that would cross it silently: every test still passes, an
  * order of magnitude slower.  Past the headroom, move a block out of `priceLoop` the way
  * `BustSwing`, `jumpDraw` and the inputs' `record` methods were. */
class PriceLoopSizeSuite extends munit.FunSuite:
  private val HugeMethodLimit = 8000
  private val Headroom        = 1000

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

  test("priceLoop stays under the JIT's huge-method limit, with headroom"):
    val stream = getClass.getResourceAsStream("/uni/apps/MarketSim$.class")
    assert(stream != null, "MarketSim$.class is not on the test classpath")
    val in = new DataInputStream(stream)
    val sizes = try codeLengths(in) finally in.close()
    val loop = sizes.filter(_._1 == "priceLoop").map(_._2)
    assertEquals(loop.size, 1, s"expected one priceLoop, found ${loop.size}")
    assert(loop.head <= HugeMethodLimit - Headroom,
      s"priceLoop is ${loop.head} bytes of bytecode; HotSpot stops compiling at $HugeMethodLimit")
