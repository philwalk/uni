#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep com.lihaoyi::ujson::4.0.2
//> using dep org.vastblue:uni_3:0.8.2

import uni.*
import scala.collection.mutable
import mutable.ArrayBuffer
import ujson.*

/** Translates Python/NumPy source to Scala/Mat source.
 *
 *  Usage (from command line):
 *    ./np2mat.sc input.py > output.scala
 *    ./np2mat.sc input.py output.scala
 *
 *  Or via pre-generated JSON AST:
 *    python ast_dump.py input.py > input.json
 *    ./np2mat.sc --json input.json > output.scala
 */
object Ujtest {
  def main(args: Array[String]): Unit = {
    val pythonPath = "C:/opt/ue/py/test.py"
    val json = generateAst(pythonPath)
  }

  def generateAst(pythonPath: String): String = {
    val script = """
import ast, json, sys

def node_to_dict(node):
    if isinstance(node, ast.AST):
        d = {'_type': type(node).__name__}
        for field, value in ast.iter_fields(node):
            d[field] = node_to_dict(value)
        return d
    elif isinstance(node, list):
        return [node_to_dict(x) for x in node]
    else:
        return node

with open(sys.argv[1]) as f:
    code = f.read()

tree = ast.parse(code)
print(json.dumps(node_to_dict(tree), indent=2))
""".trim
    val scriptFile = java.io.File.createTempFile("ast_dump", ".py")
    scala.util.Using(java.io.PrintWriter(scriptFile))(_.write(script))
    val ProcStatus(exitCode, stdout, stderr, exOpt) = shellExecProc(s"python compile ${scriptFile.toString.posx}")
    scriptFile.delete()
    stdout.mkString("\n")
  }
}
