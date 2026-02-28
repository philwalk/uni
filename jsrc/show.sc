#!/usr/bin/env -S scala-cli shebang -deprecation

//> using dep org.vastblue:uni_3:0.9.2
import uni.*
import uni.time.*
import uni.data.*
import uni.data.Mat
import uni.data.Mat.*

object Main:
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "[%<fmt>] :  specify numeric format",
      "[-s]     :  print matrix rows as legal scala literals",
    )
  }

  var verbose = false
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" => verbose = true
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }

    Mat.setSeed(0)
    var scflag = false
    var numfmt = "%1.12f"
    for arg <- args do
      if arg == "-s" then
        var scflag = true
      else if arg.startsWith("%") then
        numfmt = arg
      else
        usage(s"unrecognized arg [$arg]")

    var zmat = Mat.randn(5, 3)
    var xmat = Mat.randn(5, 4)
    var mmat = zmat.T ~@ xmat
    print(zmat.show+"\n")
    print(xmat.show+"\n")
    print(mmat.show+"\n")


    var m = Mat[Double]((1, 2), (3, 4))
    printf("%s\n", m.show)
    //assert(s.contains("Mat[Double]("))
    //assert(s.contains("[1, 2]"))
    //assert(s.contains("[3, 4]"))
    //assert(s.contains("shape=(2,2)"))

    m = Mat[Double]((1, -0.2), (0.0003, 4001.000004))
    val str = m.show("%1.2f")
    printf("%s\n", str)
//    val expect = """
//    |Mat[Double](
//    |  [   1.0,    0.2],
//    |  [   0.0, 4001.0]
//    |  shape=(2,2)
//    """.trim.stripMargin
//    assertEquals(str, expect)
  
    m = Mat.empty[Double]
    printf("%s\n", m.show)
    //assertEquals(m.show, "Mat[Double]([], shape=(0, 0))")

    var mf = Mat[Float]((1, 2), (3, 4))
    printf("%s\n", mf.show)
//    assert(s.contains("Mat[Float]("))
//    assert(s.contains("[1, 2]"))
//    assert(s.contains("[3, 4]"))
//    assert(s.contains("shape=(2,2)"))
  
    mf = Mat.empty[Float]
    printf("%s\n", mf.show)
//  assertEquals(m.show, "Mat[Float]([], shape=(0, 0))")

    var mb = Mat[Big]((1, 2), (3, 4))
    printf("%s\n", mb.show)
//    assert(s.contains("Mat[Big]("))
//    assert(s.contains("[1, 2]"))
//    assert(s.contains("[3, 4]"))
//    assert(s.contains("shape=(2,2)"))
  
    mb = Mat.empty[Big]
    printf("%s\n", m.show)
//  assertEquals(m.show, "Mat[Big]([], shape=(0, 0))")
  }
  // ── Translation TODOs ──────────────────────────────
  // unsupported expr: JoinedStr
  // unsupported statement: Global
  // unsupported expr: JoinedStr
  // np.random.normal - not yet translated
  // np.random.normal - not yet translated
