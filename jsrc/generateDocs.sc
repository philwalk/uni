#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep com.lihaoyi::os-lib:0.11.3
//> using dep org.vastblue:uni_3:0.9.0

import uni.*
import scala.io.Source

object GenerateDocs {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<munit-test-file>    ; input",
    )
  }

  var verbose = false
  var testFiles = Vector.empty[String]

  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" => verbose = true
    case f if f.path.isFile =>
      testFiles :+= f
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if (testFiles.isEmpty) {
      usage()
    }
    for f <- testFiles do
      generateDocs(f)

  }
  def generateDocs(testFile: String) = {
    val tests = Source.fromFile(testFile)
      .getLines()
      .filter(_.trim.startsWith("test(\""))
      .map(_.split("test\\(\"")(1).split("\"")(0))
      .toSeq

    // Group by category
    val categories = Map(
      "Creation" -> Seq("zeros", "ones", "eye", "full", "arange", "linspace", "rand", "randn", "empty", "tabulate", "fromSeq", "of", "single", "diag"),
      "Indexing" -> Seq("apply", "negative indexing", "slice", "fancy", "boolean mask", "where"),
      "Shape" -> Seq("shape", "size", "ndim", "isEmpty", "reshape", "flatten", "ravel", "transpose", "T "),
      "Arithmetic" -> Seq("addition", "subtraction", "multiplication", "division", "negation", "scalar", "element-wise", "hadamard"),
      "Linear Algebra" -> Seq("matrix multiplication", "dot", "~@", "inverse", "determinant", "trace", "qr", "svd", "eig", "solve", "lstsq", "norm", "diagonal", "cholesky", "pinv", "cross", "kron"),
      "Statistics" -> Seq("min", "max", "sum", "mean", "std", "variance", "median", "percentile", "cov", "corrcoef", "argmin", "argmax"),
      "Element-wise Math" -> Seq("abs", "sqrt", "exp", "log", "log10", "log2", "sin", "cos", "tan", "arcsin", "arccos", "arctan", "arctan2", "sinh", "cosh", "tanh", "floor", "ceil", "trunc", "clip", "sign", "round", "power"),
      "Comparison" -> Seq("gt", "lt", "gte", "lte", ":==", ":!=", "allclose"),
      "Boolean" -> Seq("isnan", "isinf", "isfinite", "all", "any"),
      "ML Functions" -> Seq("sigmoid", "relu", "leakyRelu", "softmax", "logSoftmax", "elu", "gelu", "dropout"),
      "Random" -> Seq("rand", "randn", "uniform", "randint", "normal", "setSeed", "NumPyRNG"),
      "Manipulation" -> Seq("vstack", "hstack", "concatenate", "repeat", "tile", "diff", "vsplit", "hsplit", "split", "meshgrid"),
      "Advanced" -> Seq("outer", "cumsum", "sort", "argsort", "unique", "convolve", "correlate", "polyfit", "polyval", "matrixRank", "nanToNum"),
      "Display" -> Seq("show", "formatMatrix", "setPrintOptions"),
      "Broadcasting" -> Seq("broadcasting", "addToEachRow", "addToEachCol", "mulEachRow", "mulEachCol", "broadcastTo"),
      "Utilities" -> Seq("copy", "map", "zerosLike", "onesLike", "fullLike", "toRowVec", "toColVec", "applyAlongAxis")
    )

    print("# Mat API Reference\n\n")
    print("*Auto-generated from test suite*\n\n")

    categories.foreach { case (category, keywords) =>
      val matching = tests.filter(t => keywords.exists(k => t.toLowerCase.contains(k.toLowerCase)))
      if (matching.nonEmpty) {
        print(s"\n## $category\n\n")
        matching.sorted.foreach { test =>
          print(s"- `$test`\n")
        }
      }
    }

    // Generate NumPy translation table
    print("\n## NumPy Compatibility\n\n")
    print("Tests demonstrating NumPy compatibility:\n\n")
    tests.filter(_.contains("NumPy")).foreach { test =>
      print(s"- $test\n")
    }

    // Generate test statistics
    print(s"\n## Test Coverage\n\n")
    print(s"Total tests: ${tests.length}\n")
    categories.foreach { case (category, keywords) =>
      val count = tests.count(t => keywords.exists(k => t.toLowerCase.contains(k.toLowerCase)))
      if (count > 0) {
        print(s"- $category: $count tests\n")
      }
    }
  }
}