package uni.io

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

class TestDelimiterSpec extends FunSuite {
  private val tempFiles = scala.collection.mutable.ArrayBuffer.empty[java.nio.file.Path]

  override def beforeAll(): Unit = uni.resetConfig()

  //
  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------
  //

  /** Writes a temporary CSV file under target/ and returns its Path.
    * The file is automatically deleted after the test.
    */
  private def writeTempFile(name: String, lines: Seq[String])(using loc: munit.Location): java.nio.file.Path = {
    val path = Paths.get("target", s"$name.csv")
    Files.createDirectories(path.getParent)
    Files.write(path, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))

    // Register cleanup
    tempFiles += path

    path
  }

  override def afterEach(context: AfterEach): Unit = {
    tempFiles.foreach { p =>
      try Files.deleteIfExists(p)
      catch case _: Throwable => ()
    }
    tempFiles.clear()
  }

  //
  // ------------------------------------------------------------
  // Tests
  // ------------------------------------------------------------
  //

  test("detects comma delimiter") {
    val path = writeTempFile("comma", Seq(
      "a,b,c",
      "1,2,3",
      "x,y,z"
    ))
    val res = Delimiter.detect(path, maxRows = 20)
    assertEquals(res.delimiterChar, ',', clues(res.toString))
  }

  test("detects comma delimiter when debris present") {
    val path = writeTempFile("commaDebris", LotsOfDebris)
    val res = Delimiter.detect(path, maxRows = 100)
    assertEquals(res.delimiterChar, ',', clues(res.toString))
  }

  test("detects tab delimiter") {
    val path = writeTempFile("tab", Seq(
      "a\tb\tc\t",
      "1\t2\t3\t",
      "x\t y\t z\t"
    ))
    val res = Delimiter.detect(path, maxRows = 20)
    assertEquals(res.delimiterChar, '\t', clues(res.toString))
  }

  test("detects semicolon delimiter with quotes") {
    val path = writeTempFile("semicolon", Seq(
      "\"a;1\";b;c",
      "d;e;f",
      "g;h;i"
    ))
    val res = Delimiter.detect(path, maxRows = 10)
    assertEquals(res.delimiterChar, ';', clues(res.toString))
  }

  test("ambiguous but decidable: comma vs semicolon") {
    val path = writeTempFile("ambiguousCommaVsSemicolon", Seq(
      "a,b;c",
      "1,2;3;4",
      "x,y;z"
    ))
    val res = Delimiter.detect(path, maxRows = 10)

    // Expect comma to win: consistent 2 columns across all rows
    assertEquals(res.delimiterChar, ',', clues(res.toString))
    assertEquals(res.modeColumns, 2, clues(res.toString))
  }

  test("ambiguous but decidable: tab vs comma with quoted commas") {
    val path = writeTempFile("ambiguousTabVsComma", Seq(
      "\"foo,bar\"\t123\tabc",
      "\"baz,qux\"\t456\tdef",
      "\"quux,corge\"\t789\tghi"
    ))
    val res = Delimiter.detect(path, maxRows = 3)

    // Expect tab to win: consistent 3 columns across all rows
    assertEquals(res.delimiterChar, '\t', clues(res.toString))
    assertEquals(res.modeColumns, 3, clues(res.toString))
  }

  //
  // ------------------------------------------------------------
  // Large debris dataset
  // ------------------------------------------------------------
  //

  lazy val LotsOfDebris: Seq[String] =
    """
      |Account Number,Account Name,Symbol,Description,Quantity,Last Price,Last Price Change,Current Value,Today's Gain/Loss Dollar,Today's Gain/Loss Percent,Total Gain/Loss Dollar,Total Gain/Loss Percent,Percent Of Account,Cost Basis Total,Average Cost Basis,Type
      |Q11111111,proj,SPAXX**,HELD IN MONEY MARKET,,,,1.11,,,,,1.11%,,,Stuff
      |Q11111111,proj,XYZ,ISHARES TR 1-11 YR TRSY BD,11,1.11,-1.11,1.11,-1.11,-1.11%,-1.11,-1.11%,1.11%,1.11,1.11,Stuff
      |111111111,blue,LMNOP**,HELD ,,,,1.11,,,,,1.11%,,,Stuff
      |111111111,blue,JJJ,COM ISIN #CA1111111111 #B11NF11,111,1.11,-1.11,1.11,-1.11,-1.11%,+1.11,+1.11%,1.11%,1.11,1.11,Stuff
      |111111111,opel,LMNOP**,HELD 22 ,,,,1.11,,,,,1.11%,,,Stuff
      |111111111,jant,LMNOP**,HELD 22 ,,,,1.11,,,,,1.11%,,,Stuff
      |111111111,jant,XLV,TRUST,1.111,1.11,-1.11,1.11,-1.11,-1.11%,-1.11,-1.11%,1.11%,1.11,1.11,Stuff
      |
      |Qfe juho uct uhtukjakaav av mced scyueqpyuad il gcejukib ma lou vuxuxy bad nuus ela ojd ov buy hep fackwuqojoah. Wre xstiowyyeem ad lpavisuz fus eyqugcadaetip vadnakac ofkx, ovw ib kuw evxazdip hu gremoja ofyuro, rux vfaaqn ip xo faqyzjuah ex ad oxqit no gonn, o xidiqojaduof if em efqit mi pid id e rokupnivyokiix wan ijx cagawavv ys Vupuxiql oc exs wvelw yimjm. Foga aqd ugmeqyemoov fdugs am hizew ay iqmokcetiiq vmifn mi Vazurafr ib ey byu hidi od vej ixgijhud inq ul vulyonv to bwamgo. Il dpuoxk ned za esar al gwigo am haes adveevq phewenijzg ek ttore xurhopquwooxy apf an dac osvobhik yas boj jiparcits kapcumox. Yoq zazi odfowwehouy aq ppo jeja efpbukun at ggir pdroahxtuit, ennwowutq ivp suquhaniamj dmisiex, za ve Mulozepr.niz.
      |
      |Mjadafuki senwahoz axo wmopusaz dt Carulirp Rmiyoyati Daqwerop DKK (YTY), 111 Vatup Vyziij, Hrohmfoifj, KO 11111. Wabzozw ebr edqog romdiqah wjuberiw sj Banuobaf Wemaxbeon Xobxafig ZKT (NSF). Wajc afe Cujuqumr Ifdavdyajw joxmuxaeq ujl pozquxn DETD, GMTO. Fuowmoh JPK kuz TJH oclir gstfhu op e poguyk oqmaxnmanj num wjanodi ldabetp am ragzujh qoxsatif duj xozj ubciwx.
      |
    """.stripMargin.trim.split("[\r\n]+").toSeq
}
