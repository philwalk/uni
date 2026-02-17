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
object Numpy2Mat {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "--json <input.json>",
      "<input.py>"
    )
  }

  def main(args: Array[String]): Unit = {
    var (jsonFile, pyFile) = ("", "")

    // ── main ──────────────────────────────────────────────────────────────────────
    eachArg(args.toSeq, usage) {
      case "--json" =>
        jsonFile = consumeNext
        if !jsonFile.path.isFile then
          usage(s"not found [$jsonFile]")
      case fname if Paths.get(fname).isFile =>
        pyFile = fname
        if !pyFile.path.isFile then
          usage(s"not found [$pyFile]")
      case arg =>
        usage(s"unrecognized arg [$arg]")
    }
    (jsonFile.isEmpty, pyFile.isEmpty) match
      case (false, false) =>
        usage(s"must specify either '--json <jsonFile>' OR <pyFile>")
      case (true, true) =>
        usage(s"either --json <jsonFile> OR <pyFile> but not both:  json[$jsonFile], py[$pyFile]")
      case (false, true) =>
        val output = Np2Mat.translateFile(pyFile)
        print(output+"\n")
      case (true, false) =>
        val fname = Paths.get(jsonFile).posx
        val json   = scala.io.Source.fromFile(fname).mkString
        val output = Np2Mat.translateJson(json)
        print(output+"\n")
      case _ =>
        usage()
  }

  // ── public API ──────────────────────────────────────────────────────────

  def translateFile(pythonPath: String): String =
    val json = generateAst(pythonPath)
    translateJson(json)

  def translateJson(json: String): String =
    val tree = ujson.read(json)
    val ctx  = TranslateContext()
    ctx.indent = 2
    val body = translateModule(tree, ctx)
    renderOutput(body.toSeq, ctx)

  // ── AST generation ──────────────────────────────────────────────────────

  private def generateAst(pythonPath: String): String =
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
    val result = sys.process.Process(Seq("python", scriptFile.getAbsolutePath, pythonPath)).!!
    scriptFile.delete()
    result

  // ── context ─────────────────────────────────────────────────────────────

  case class VarInfo(
    name:      String,
    scalaType: String = "Double",
    shape:     Option[(Int, Int)] = None,
    isMatrix:  Boolean = true
  )

  class TranslateContext:
    val imports = mutable.LinkedHashSet[String]()
    val vars    = mutable.Map[String, VarInfo]()
    var npAlias = "np"
    var indent  = 0
    val todos   = mutable.ArrayBuffer[String]()

    def addImport(s: String): Unit = imports += s
    def indentStr: String = "  " * indent
    def todo(msg: String): String =
      todos += msg
      s"/* TODO: $msg */"

  // ── module ──────────────────────────────────────────────────────────────
  private def translateModule(tree: Value, ctx: TranslateContext): ArrayBuffer[String] =
    tree("body").arr.flatMap(stmt => translateStmt(stmt, ctx))

  private def renderOutput(lines: Seq[String], ctx: TranslateContext): String =
    val scriptHeader = """#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
      |
      |//> using dep org.vastblue:uni_3:0.8.2""".trim.stripMargin

    val sb = new StringBuilder
    sb.append(scriptHeader+"\n")
    sb.append("import uni.data.Mat\n")
    sb.append("import uni.data.Mat.*\n")
    if ctx.imports.nonEmpty then
      ctx.imports.foreach(i => sb.append(s"import $i\n"))
    sb.append("\nobject Main:\n")
    sb.append("  def main(args: Array[String]): Unit =\n")
    lines.foreach(l => sb.append(l).append("\n"))
    if ctx.todos.nonEmpty then
      sb.append("\n    // ── Translation TODOs ──────────────────────────────\n")
      ctx.todos.foreach(t => sb.append(s"    // $t\n"))
    sb.toString

  // ── statements ──────────────────────────────────────────────────────────

  private def translateStmt(node: Value, ctx: TranslateContext): Seq[String] =
    node("_type").str match

      case "Import" =>
        node("names").arr.foreach { alias =>
          if alias("name").str == "numpy" then
            ctx.npAlias = alias.obj.get("asname")
              .flatMap(v => if v.isNull then None else Some(v.str))
              .getOrElse("np")
        }
        Seq.empty

      case "ImportFrom" =>
        Seq.empty

      case "Assign" =>
        translateAssign(node, ctx)

      case "AugAssign" =>
        translateAugAssign(node, ctx)

      case "Expr" =>
        val expr = translateExpr(node("value"), ctx)
        Seq(s"${ctx.indentStr}$expr")

      case "For" =>
        translateFor(node, ctx)

      case "While" =>
        translateWhile(node, ctx)

      case "If" =>
        translateIf(node, ctx)

      case "FunctionDef" =>
        translateFunctionDef(node, ctx)

      case "Return" =>
        val value = node.obj.get("value")
          .filter(!_.isNull)
          .map(v => translateExpr(v, ctx))
          .getOrElse("()")
        Seq(s"${ctx.indentStr}$value")

      case "Pass" =>
        Seq(s"${ctx.indentStr}()")

      case other =>
        Seq(ctx.todo(s"unsupported statement: $other"))

  // ── assign ──────────────────────────────────────────────────────────────
  private val scalaKeywords = Set(
    "type", "val", "var", "def", "class", "object", "trait", "extends",
    "with", "match", "case", "if", "else", "for", "while", "do", "yield",
    "return", "import", "package", "new", "this", "super", "null", "true",
    "false", "throw", "try", "catch", "finally", "sealed", "abstract",
    "override", "lazy", "implicit", "given", "using", "inline", "opaque",
    "row", "col"  // also Mat method names that would shadow
  )

  private def safeName(name: String): String =
    if scalaKeywords.contains(name) then s"${name}_" else name

  private def translateAssign(node: Value, ctx: TranslateContext): Seq[String] =
    val targets = node("targets").arr
    val value   = node("value")

    if targets.length == 1 then
      val target = targets(0)
      target("_type").str match

        case "Name" =>
          val name = safeName(target("id").str)
          val (expr, info) = translateExprWithInfo(value, ctx, Some(name))
          ctx.vars(name) = info.copy(name = name)
          Seq(s"${ctx.indentStr}var $name = $expr") // var because might be mutated later

        case "Tuple" =>
          val rawNames = target("elts").arr.map(e => e("id").str)
          val names = rawNames.map { n =>
            if ctx.vars.contains(n) then
              var candidate = s"${n}2"
              while ctx.vars.contains(candidate) do candidate = candidate + "2"
              candidate
            else n
          }
          names.foreach(n => ctx.vars(n) = VarInfo(n))
          val expr = translateExpr(value, ctx)
          if names.exists(n => n.head.isUpper) then
            val tmp = s"_${names.map(_.toLowerCase).mkString("")}"
            val decls = Seq(s"${ctx.indentStr}val $tmp = $expr") ++
              names.zipWithIndex.map { case (n, i) =>
                s"${ctx.indentStr}val $n = $tmp._${i + 1}"
              }
            decls
          else
            val pat = names.mkString("(", ", ", ")")
            Seq(s"${ctx.indentStr}val $pat = $expr")

        case "Subscript" =>
          translateSubscriptAssign(target, value, ctx)

        case "Attribute" =>
          val obj  = translateExpr(target("value"), ctx)
          val attr = target("attr").str
          val expr = translateExpr(value, ctx)
          Seq(s"${ctx.indentStr}$obj.$attr = $expr")

        case other =>
          Seq(ctx.todo(s"unsupported assignment target: $other"))
    else
      val expr = translateExpr(value, ctx)
      targets.map { t =>
        val name = t("id").str
        s"${ctx.indentStr}val $name = $expr"
      }.toSeq

  private def translateSubscriptAssign(target: Value, value: Value, ctx: TranslateContext): Seq[String] =
    val obj   = translateExpr(target("value"), ctx)
    val slice = translateSlice(target("slice"), ctx)
    val (expr, info) = translateExprWithInfo(value, ctx, None)
    val safeExpr = if !info.isMatrix && info.scalaType != "Double" then s"($expr).toDouble" else expr
    Seq(s"${ctx.indentStr}$obj($slice) = $safeExpr")

  // ── aug assign ──────────────────────────────────────────────────────────

  private def translateAugAssign(node: Value, ctx: TranslateContext): Seq[String] =
    val target = translateExpr(node("target"), ctx)
    val value  = translateExpr(node("value"), ctx)
    val op = node("op")("_type").str match
      case "Add"  => ":+="
      case "Sub"  => ":-="
      case "Mult" => ":*="
      case "Div"  => ":/="
      case other  => ctx.todo(s"unsupported aug op: $other"); "??="
    Seq(s"${ctx.indentStr}$target $op $value")

  // ── for ─────────────────────────────────────────────────────────────────

  private def translateFor(node: Value, ctx: TranslateContext): Seq[String] =
    val target = translateExpr(node("target"), ctx)
    val iter   = translateExpr(node("iter"), ctx)
    ctx.indent += 1
    val body = node("body").arr.flatMap(s => translateStmt(s, ctx))
    ctx.indent -= 1
    Seq(s"${ctx.indentStr}for $target <- $iter do") ++ body

  // ── while ────────────────────────────────────────────────────────────────

  private def translateWhile(node: Value, ctx: TranslateContext): Seq[String] =
    val test = translateExpr(node("test"), ctx)
    ctx.indent += 1
    val body = node("body").arr.flatMap(s => translateStmt(s, ctx))
    ctx.indent -= 1
    Seq(s"${ctx.indentStr}while $test do") ++ body

  // ── if ───────────────────────────────────────────────────────────────────

  private def translateIf(node: Value, ctx: TranslateContext): Seq[String] =
    val test = translateExpr(node("test"), ctx)
    ctx.indent += 1
    val body   = node("body").arr.flatMap(s => translateStmt(s, ctx))
    val orelse = node("orelse").arr.flatMap(s => translateStmt(s, ctx))
    ctx.indent -= 1
    val ifLines = Seq(s"${ctx.indentStr}if $test then") ++ body
    if orelse.nonEmpty then ifLines ++ Seq(s"${ctx.indentStr}else") ++ orelse
    else ifLines

  // ── function def ─────────────────────────────────────────────────────────
  private def translateFunctionDef(node: Value, ctx: TranslateContext): Seq[String] =
    val name   = node("name").str
    val args   = node("args")("args").arr.map { arg =>
      val argName = arg.obj.get("arg").map(_.str).getOrElse("x")
      s"$argName: Mat[Double]"
    }
    val argStr = args.mkString(", ")
    ctx.indent += 1
    val body = node("body").arr.flatMap(s => translateStmt(s, ctx))
    ctx.indent -= 1
    Seq(s"${ctx.indentStr}def $name($argStr): Mat[Double] = {") ++
      body ++
      Seq(s"${ctx.indentStr}}")

  // ── expressions ──────────────────────────────────────────────────────────

  private def translateExpr(node: Value, ctx: TranslateContext): String =
    translateExprWithInfo(node, ctx, None)._1

  private def translateExprWithInfo(
      node: Value, ctx: TranslateContext,
      targetName: Option[String]): (String, VarInfo) =

    val defaultInfo = VarInfo(targetName.getOrElse("_"))

    node("_type").str match
      case "Constant" =>
        val v = node("value")
        val s = if v.isNull then "null"
                else v match
                  case Num(n) =>
                    if n.isWhole then n.toLong.toString  // 3 not 3.0
                    else n.toString                       // 1.5 stays 1.5
                  case Str(s)  => s""""$s""""
                  case Bool(b) => b.toString
                  case _       => v.toString
        (s, defaultInfo.copy(isMatrix = false))

      case "Name" =>
        val id   = node("id").str
        val info = ctx.vars.getOrElse(id, defaultInfo.copy(name = id))
        (id, info)

      case "List" | "Tuple" =>
        val elts = node("elts").arr.map(e => translateExpr(e, ctx))
        (s"Seq(${elts.mkString(", ")})", defaultInfo)

      case "Call" =>
        translateCall(node, ctx, targetName)

      case "BinOp" =>
        translateBinOp(node, ctx)

      case "UnaryOp" =>
        translateUnaryOp(node, ctx)

      case "Compare" =>
        translateCompare(node, ctx)

      case "BoolOp" =>
        translateBoolOp(node, ctx)

      case "Attribute" =>
        val obj  = translateExpr(node("value"), ctx)
        val attr = node("attr").str
        translateAttrAccess(obj, attr, ctx)

      case "Subscript" =>
        val obj   = translateExpr(node("value"), ctx)
        val slice = translateSlice(node("slice"), ctx)
        (s"$obj($slice)", defaultInfo)

      case "IfExp" =>
        val test   = translateExpr(node("test"), ctx)
        val body   = translateExpr(node("body"), ctx)
        val orelse = translateExpr(node("orelse"), ctx)
        (s"if $test then $body else $orelse", defaultInfo)

      case "Lambda" =>
        val args = node("args")("args").arr
          .map(a => a.obj.get("arg").map(_.str).getOrElse("x"))
        val body   = translateExpr(node("body"), ctx)
        val argStr = if args.length == 1 then args.head else args.mkString("(", ", ", ")")
        (s"($argStr => $body)", defaultInfo)

      case "ListComp" | "GeneratorExp" =>
        translateListComp(node, ctx)

      case other =>
        (ctx.todo(s"unsupported expr: $other"), defaultInfo)

  // ── attribute access ─────────────────────────────────────────────────────

  private def translateAttrAccess(
      obj: String, attr: String, ctx: TranslateContext): (String, VarInfo) =
    val info = VarInfo(obj)
    attr match
      case "T"     => (s"$obj.T", info)
      case "shape" => (s"$obj.shape", info.copy(isMatrix = false))
      case "size"  => (s"$obj.size", info.copy(isMatrix = false))
      case "dtype" => (ctx.todo(s"$obj.dtype - no direct equivalent"), info)
      case "flat"  => (s"$obj.flatten", info)
      case other   => (s"$obj.$other", info)

  // ── calls ────────────────────────────────────────────────────────────────

  private def translateCall(
      node: Value, ctx: TranslateContext,
      targetName: Option[String]): (String, VarInfo) =

    val func       = node("func")
    val args       = node("args").arr
    val kwargs     = node("keywords").arr
    val defaultInfo = VarInfo(targetName.getOrElse("_"))

    def kwarg(name: String): Option[Value] =
      kwargs.find(k => k("arg").strOpt.contains(name)).map(_("value"))

    func("_type").str match

      case "Attribute" =>
        val objNode = func("value")
        val method  = func("attr").str
        val obj     = translateExpr(objNode, ctx)
        val np      = ctx.npAlias
        if obj == np then
          translateNpCall(method, args, kwargs, kwarg, ctx, defaultInfo)
        else if obj == s"$np.linalg" then
          translateNpLinalgCall(method, args, ctx, defaultInfo)
        else if obj == s"$np.random" then
          translateNpRandomCall(method, args, kwargs, ctx, defaultInfo)
        else
          translateMethodCall(obj, method, args, kwargs, kwarg, ctx, defaultInfo)

      case "Name" =>
        val name = func("id").str
        name match
          case "print" =>
            val argStr = args.map(a => translateExpr(a, ctx)).mkString(", ")
            (s"println($argStr)", defaultInfo.copy(isMatrix = false))
          case "len" =>
            val a = translateExpr(args(0), ctx)
            (s"$a.size", defaultInfo.copy(isMatrix = false))
          case "range" =>
            translateRange(args, ctx)
          case "int" | "float" =>
            val a = translateExpr(args(0), ctx)
            (s"$a.toDouble", defaultInfo.copy(isMatrix = false))
          case other =>
            val argStr = args.map(a => translateExpr(a, ctx)).mkString(", ")
            (s"$other($argStr)", defaultInfo)

      case other =>
        val f      = translateExpr(func, ctx)
        val argStr = args.map(a => translateExpr(a, ctx)).mkString(", ")
        (s"$f($argStr)", defaultInfo)

  // ── numpy calls ──────────────────────────────────────────────────────────

  private def translateNpCall(
      method: String, args: ArrayBuffer[Value], kwargs: ArrayBuffer[Value],
      kwarg: String => Option[Value],
      ctx: TranslateContext, info: VarInfo): (String, VarInfo) =

    def arg0 = translateExpr(args(0), ctx)
    def arg1 = translateExpr(args(1), ctx)

    def shapeArgs(v: Value): String =
      v("_type").str match
        case "Tuple" => v("elts").arr.map(e => translateExpr(e, ctx)).mkString(", ")
        case _       => s"${translateExpr(v, ctx)}, 1"

    def axisArg: Option[String] =
      kwarg("axis").map(v => translateExpr(v, ctx))
        .orElse(if args.length > 1 then Some(arg1) else None)

    method match
      case "zeros"      => (s"Mat.zeros[Double](${shapeArgs(args(0))})", info)
      case "ones"       => (s"Mat.ones[Double](${shapeArgs(args(0))})", info)
      case "eye"        =>
        val k = kwarg("k").map(v => s", ${translateExpr(v, ctx)}").getOrElse("")
        (s"Mat.eye[Double]($arg0$k)", info)
      case "full"       =>
        (s"Mat.full[Double](${shapeArgs(args(0))}, ${translateExpr(args(1), ctx)})", info)
      case "arange"     => translateArange(args, ctx, info)
      case "linspace"   =>
        val n = if args.length > 2 then s", ${translateExpr(args(2), ctx)}" else ""
        (s"Mat.linspace[Double]($arg0, $arg1$n)", info)
      case "array"      => translateNpArray(args(0), ctx, info)
      case "asarray"    => translateNpArray(args(0), ctx, info)
      case "empty"      => (s"Mat.zeros[Double](${shapeArgs(args(0))})", info)
      case "zeros_like" => (s"Mat.zerosLike($arg0)", info)
      case "ones_like"  => (s"Mat.onesLike($arg0)", info)
      case "full_like"  => (s"Mat.fullLike($arg0, ${translateExpr(args(1), ctx)})", info)
      case "copy"       => (s"$arg0.copy", info)

      case "sum"  => axisArg match
        case Some(ax) => (s"$arg0.sum($ax)", info)
        case None     => (s"$arg0.sum", info.copy(isMatrix = false))
      case "mean" => axisArg match
        case Some(ax) => (s"$arg0.mean($ax)", info)
        case None     => (s"$arg0.mean", info.copy(isMatrix = false))
      case "max"  => axisArg match
        case Some(ax) => (s"$arg0.max($ax)", info)
        case None     => (s"$arg0.max", info.copy(isMatrix = false))
      case "min"  => axisArg match
        case Some(ax) => (s"$arg0.min($ax)", info)
        case None     => (s"$arg0.min", info.copy(isMatrix = false))
      case "std"  => axisArg match
        case Some(ax) => (s"$arg0.std($ax)", info)
        case None     => (s"$arg0.std", info.copy(isMatrix = false))
      case "var"  => axisArg match
        case Some(ax) => (ctx.todo(s"var(axis=$ax) - use .std($ax).power(2)"), info)
        case None     => (s"$arg0.variance", info.copy(isMatrix = false))
      case "median" => axisArg match
        case Some(ax) => (s"$arg0.median($ax)", info)
        case None     => (s"$arg0.median", info.copy(isMatrix = false))

      case "percentile" =>
        val p  = translateExpr(args(1), ctx)
        val ax = kwarg("axis").map(v => translateExpr(v, ctx))
        ax match
          case Some(a) => (s"$arg0.percentile($p, $a)", info)
          case None    => (s"$arg0.percentile($p)", info.copy(isMatrix = false))

      case "sqrt"  => (s"$arg0.sqrt", info)
      case "abs"   => (s"$arg0.abs", info)
      case "exp"   => (s"$arg0.exp", info)
      case "log"   => (s"$arg0.log", info)
      case "sign"  => (s"$arg0.sign", info)
      case "round" =>
        val dec = if args.length > 1 then s"(${arg1})" else "()"
        (s"$arg0.round$dec", info)
      case "power"    => (s"$arg0.power($arg1)", info)
      case "clip"     =>
        (s"$arg0.clip(${translateExpr(args(1), ctx)}, ${translateExpr(args(2), ctx)})", info)
      case "isnan"    => (s"$arg0.isnan", info.copy(scalaType = "Boolean"))
      case "isinf"    => (s"$arg0.isinf", info.copy(scalaType = "Boolean"))
      case "isfinite" => (s"$arg0.isfinite", info.copy(scalaType = "Boolean"))
      case "nan_to_num" =>
        val nan    = kwarg("nan").map(v => s"nan=${translateExpr(v, ctx)}").getOrElse("")
        val posinf = kwarg("posinf").map(v => s"posinf=${translateExpr(v, ctx)}").getOrElse("")
        val neginf = kwarg("neginf").map(v => s"neginf=${translateExpr(v, ctx)}").getOrElse("")
        val params = Seq(nan, posinf, neginf).filter(_.nonEmpty).mkString(", ")
        (s"$arg0.nanToNum($params)", info)

      case "dot"      => (s"$arg0 * $arg1", info)
      case "matmul"   => (s"$arg0 * $arg1", info)
      case "outer"    => (s"$arg0.outer($arg1)", info)
      case "cross"    => (s"$arg0.cross($arg1)", info)
      case "kron"     => (s"$arg0.kron($arg1)", info)
      case "einsum"   => (ctx.todo("einsum - manual translation required"), info)
      case "tensordot" => (ctx.todo("tensordot - use * for standard matmul"), info)

      case "reshape"    =>
        (s"$arg0.reshape(${shapeArgs(args(1))})", info)
      case "flatten"    => (s"$arg0.flatten", info)
      case "ravel"      => (s"$arg0.flatten", info)
      case "squeeze"    => (ctx.todo("squeeze - check if reshape needed"), info)
      case "expand_dims" =>
        translateExpr(args(1), ctx) match
          case "0" => (s"$arg0.toRowVec", info)
          case "1" => (s"$arg0.toColVec", info)
          case ax  => (ctx.todo(s"expand_dims axis=$ax"), info)
      case "transpose"  => (s"$arg0.T", info)

      case "concatenate" =>
        val ax   = kwarg("axis").map(v => translateExpr(v, ctx)).getOrElse("0")
        val mats = args(0)("elts").arr.map(e => translateExpr(e, ctx))
        (s"Mat.concatenate(Seq(${mats.mkString(", ")}), axis = $ax)", info)
      case "vstack" =>
        val mats = args(0)("elts").arr.map(e => translateExpr(e, ctx))
        (s"Mat.vstack(${mats.mkString(", ")})", info)
      case "hstack" =>
        val mats = args(0)("elts").arr.map(e => translateExpr(e, ctx))
        (s"Mat.hstack(${mats.mkString(", ")})", info)
      case "stack" =>
        val ax   = kwarg("axis").map(v => translateExpr(v, ctx)).getOrElse("0")
        val mats = args(0)("elts").arr.map(e => translateExpr(e, ctx))
        (s"Mat.concatenate(Seq(${mats.mkString(", ")}), axis = $ax)", info)

      case "where" =>
        val cond = translateExpr(args(0), ctx)
        val x    = translateExpr(args(1), ctx)
        val y    = translateExpr(args(2), ctx)
        (s"Mat.where($cond, $x, $y)", info)

      case "diag" =>
        if args.length > 1 then
          (ctx.todo("diag with k offset - use Mat.diag(v) and check offset"), info)
        else
          (s"Mat.diag($arg0)", info)

      case "tril" =>
        val k = if args.length > 1 then s"($arg1)" else "()"
        (s"$arg0.tril$k", info)
      case "triu" =>
        val k = if args.length > 1 then s"($arg1)" else "()"
        (s"$arg0.triu$k", info)

      case "trace"  => (s"$arg0.trace", info.copy(isMatrix = false))
      case "diff"   => axisArg match
        case Some(ax) => (s"$arg0.diff($ax)", info)
        case None     => (s"$arg0.diff", info)
      case "cumsum" => axisArg match
        case Some(ax) => (s"$arg0.cumsum($ax)", info)
        case None     => (s"$arg0.cumsum", info)
      case "sort"   => axisArg match
        case Some(ax) => (s"$arg0.sort($ax)", info)
        case None     => (s"$arg0.sort()", info)
      case "argsort" => axisArg match
        case Some(ax) => (s"$arg0.argsort($ax)", info)
        case None     => (s"$arg0.argsort()", info)
      case "unique" => (s"$arg0.unique", info)

      case "allclose" =>
        val rtol = kwarg("rtol").map(v => s", rtol=${translateExpr(v, ctx)}").getOrElse("")
        val atol = kwarg("atol").map(v => s", atol=${translateExpr(v, ctx)}").getOrElse("")
        (s"$arg0.allclose($arg1$rtol$atol)", info.copy(isMatrix = false))

      case "norm" => (s"$arg0.norm", info.copy(isMatrix = false))

      case "repeat" => axisArg match
        case Some(ax) => (s"$arg0.repeat($arg1, $ax)", info)
        case None     => (s"$arg0.repeat($arg1)", info)
      case "tile" =>
        val reps = args(1)
        reps("_type").str match
          case "Tuple" =>
            val elts = reps("elts").arr.map(e => translateExpr(e, ctx))
            if elts.length == 2 then (s"$arg0.tile(${elts(0)}, ${elts(1)})", info)
            else (ctx.todo(s"tile with ${elts.length} reps"), info)
          case _ =>
            (s"$arg0.tile(${translateExpr(reps, ctx)}, 1)", info)

      case "meshgrid" =>
        val mats = args.map(a => translateExpr(a, ctx))
        if mats.length == 2 then (s"Mat.meshgrid(${mats(0)}, ${mats(1)})", info)
        else (ctx.todo(s"meshgrid with ${mats.length} args - only 2D supported"), info)

      case "convolve" =>
        val mode = kwarg("mode").map(v => s""", "${v.str}"""").getOrElse("")
        (s"Mat.convolve($arg0, $arg1$mode)", info)
      case "correlate" =>
        val mode = kwarg("mode").map(v => s""", "${v.str}"""").getOrElse("")
        (s"Mat.correlate($arg0, $arg1$mode)", info)

      case "polyfit" => (s"Mat.polyfit($arg0, $arg1, ${translateExpr(args(2), ctx)})", info)
      case "polyval" => (s"Mat.polyval($arg0, $arg1)", info)

      case "apply_along_axis" =>
        val fn  = translateExpr(args(0), ctx)
        val ax  = translateExpr(args(1), ctx)
        val arr = translateExpr(args(2), ctx)
        (s"$arr.applyAlongAxis($fn, $ax)", info)

      case "cov"     => (s"$arg0.cov", info)
      case "corrcoef" => (s"$arg0.corrcoef", info)

      case other =>
        val argStr = args.map(a => translateExpr(a, ctx)).mkString(", ")
        (ctx.todo(s"np.$other($argStr) - not yet translated"), info)

  // ── numpy linalg calls ───────────────────────────────────────────────────

  private def translateNpLinalgCall(
      method: String, args: ArrayBuffer[Value],
      ctx: TranslateContext, info: VarInfo): (String, VarInfo) =
    def arg0 = translateExpr(args(0), ctx)
    def arg1 = translateExpr(args(1), ctx)
    method match
      case "inv"         => (s"$arg0.inverse", info)
      case "det"         => (s"$arg0.determinant", info.copy(isMatrix = false))
      case "solve"       => (s"$arg0.solve($arg1)", info)
      case "norm"        =>
        val ord = if args.length > 1 then
          translateExpr(args(1), ctx) match
            case "\"fro\"" | "'fro'" => """("fro")"""
            case "\"inf\"" | "'inf'" => """("inf")"""
            case "\"1\""  | "'1'"   => """("1")"""
            case _                   => ""
        else ""
        if ord.isEmpty then (s"$arg0.norm", info.copy(isMatrix = false))
        else (s"$arg0.norm$ord", info.copy(isMatrix = false))
      case "svd"         => (s"$arg0.svd", info)
      case "eig"         => (s"$arg0.eig", info)
      case "eigh"        => (s"$arg0.eigenvalues()", info)
      case "eigvals"     => (s"$arg0.eigenvalues()", info)
      case "qr"          => (s"$arg0.qrDecomposition", info)
      case "cholesky"    => (s"$arg0.cholesky", info)
      case "pinv"        => (s"$arg0.pinv()", info)
      case "matrix_rank" => (s"$arg0.matrixRank()", info.copy(isMatrix = false))
      case "lstsq"       => (s"$arg0.lstsq($arg1)", info)
      case other         => (ctx.todo(s"np.linalg.$other - not yet translated"), info)

  // ── numpy random calls ───────────────────────────────────────────────────

  private def translateNpRandomCall(
      method: String, args: ArrayBuffer[Value], kwargs: ArrayBuffer[Value],
      ctx: TranslateContext, info: VarInfo): (String, VarInfo) =
    def shapeArgs(v: Value): String =
      v("_type").str match
        case "Tuple" => v("elts").arr.map(e => translateExpr(e, ctx)).mkString(", ")
        case _       => s"${translateExpr(v, ctx)}, 1"
    method match
      case "rand"    =>
        val shape = args.map(a => translateExpr(a, ctx)).mkString(", ")
        (s"Mat.rand($shape)", info)
      case "randn"   =>
        val shape = args.map(a => translateExpr(a, ctx)).mkString(", ")
        (s"Mat.randn($shape)", info)
      case "random"  =>
        if args.isEmpty then (s"Mat.rand(1, 1)", info)
        else (s"Mat.rand(${shapeArgs(args(0))})", info)
      case "randint" =>
        val lo = translateExpr(args(0), ctx)
        val hi = translateExpr(args(1), ctx)
        (ctx.todo(s"random.randint($lo, $hi) - use Mat.rand then scale"), info)
      case "seed"    =>
        val s = translateExpr(args(0), ctx)
        (ctx.todo(s"random.seed($s) - use seed parameter on Mat.rand/randn"), info)
      case "shuffle" => (ctx.todo("random.shuffle - no direct equivalent"), info)
      case "choice"  => (ctx.todo("random.choice - no direct equivalent"), info)
      case other     => (ctx.todo(s"np.random.$other - not yet translated"), info)

  // ── method calls on Mat instances ────────────────────────────────────────

  private def translateMethodCall(
      obj: String, method: String, args: ArrayBuffer[Value], kwargs: ArrayBuffer[Value],
      kwarg: String => Option[Value],
      ctx: TranslateContext, info: VarInfo): (String, VarInfo) =

    def arg0 = if args.nonEmpty then translateExpr(args(0), ctx) else ""
    def axisArg: Option[String] =
      kwarg("axis").map(v => translateExpr(v, ctx))
        .orElse(if args.nonEmpty then Some(arg0) else None)

    method match
      case "reshape"   =>
        val r = translateExpr(args(0), ctx)
        val c = translateExpr(args(1), ctx)
        (s"$obj.reshape($r, $c)", info)
      case "flatten"   => (s"$obj.flatten", info)
      case "ravel"     => (s"$obj.flatten", info)
      case "transpose" => (s"$obj.T", info)
      case "copy"      => (s"$obj.copy", info)
      case "tolist"    => (s"$obj.flatten.toList", info.copy(isMatrix = false))
      case "astype"    => (ctx.todo(s"$obj.astype($arg0) - check type parameter"), info)
      case "sum"       => axisArg match
        case Some(ax) => (s"$obj.sum($ax)", info)
        case None     => (s"$obj.sum", info.copy(isMatrix = false))
      case "mean"      => axisArg match
        case Some(ax) => (s"$obj.mean($ax)", info)
        case None     => (s"$obj.mean", info.copy(isMatrix = false))
      case "max"       => axisArg match
        case Some(ax) => (s"$obj.max($ax)", info)
        case None     => (s"$obj.max", info.copy(isMatrix = false))
      case "min"       => axisArg match
        case Some(ax) => (s"$obj.min($ax)", info)
        case None     => (s"$obj.min", info.copy(isMatrix = false))
      case "std"       => axisArg match
        case Some(ax) => (s"$obj.std($ax)", info)
        case None     => (s"$obj.std", info.copy(isMatrix = false))
      case "dot"       => (s"$obj * $arg0", info)
      case "clip"      =>
        (s"$obj.clip(${translateExpr(args(0), ctx)}, ${translateExpr(args(1), ctx)})", info)
      case "sort"      => axisArg match
        case Some(ax) => (s"$obj.sort($ax)", info)
        case None     => (s"$obj.sort()", info)
      case "argsort"   => axisArg match
        case Some(ax) => (s"$obj.argsort($ax)", info)
        case None     => (s"$obj.argsort()", info)
      case "cumsum"    => axisArg match
        case Some(ax) => (s"$obj.cumsum($ax)", info)
        case None     => (s"$obj.cumsum", info)
      case other       =>
        val argStr = args.map(a => translateExpr(a, ctx)).mkString(", ")
        (s"$obj.$other($argStr)", info)

  // ── numpy array literal ──────────────────────────────────────────────────

  private def translateNpArray(
      node: Value, ctx: TranslateContext, info: VarInfo): (String, VarInfo) =
    node("_type").str match
      case "List" =>
        val elts = node("elts").arr
        if elts.isEmpty then
          (s"Mat[Double](0, 0, Array.empty[Double])", info)
        else elts(0)("_type").str match
          case "List" =>
            val rows = elts.map { row =>
              val vals = row("elts").arr.map(e => translateExpr(e, ctx))
              s"(${vals.mkString(", ")})"
            }
            (s"Mat[Double](${rows.mkString(", ")})", info)
          case _ =>
            val vals = elts.map(e => translateExpr(e, ctx))
            (s"Mat.row[Double](${vals.mkString(", ")})", info)
      case "Tuple" =>
        val vals = node("elts").arr.map(e => translateExpr(e, ctx))
        (s"Mat.row[Double](${vals.mkString(", ")})", info)
      case _ =>
        (translateExpr(node, ctx), info)

  // ── arange ───────────────────────────────────────────────────────────────

  private def translateArange(
      args: ArrayBuffer[Value], ctx: TranslateContext, info: VarInfo): (String, VarInfo) =
    args.length match
      case 1 => (s"Mat.arange[Double](0.0, ${translateExpr(args(0), ctx)})", info)
      case 2 => (s"Mat.arange[Double](${translateExpr(args(0), ctx)}, ${translateExpr(args(1), ctx)})", info)
      case 3 =>
        val start = translateExpr(args(0), ctx)
        val stop  = translateExpr(args(1), ctx)
        val step  = translateExpr(args(2), ctx)
        (s"Mat.arange[Double]($start, $stop, $step)", info)
      case _ => (ctx.todo("arange with unexpected args"), info)

  // ── range ────────────────────────────────────────────────────────────────

  private def translateRange(
      args: ArrayBuffer[Value], ctx: TranslateContext): (String, VarInfo) =
    val info = VarInfo("_", isMatrix = false)
    args.length match
      case 1 => (s"0 until ${translateExpr(args(0), ctx)}", info)
      case 2 =>
        val start = translateExpr(args(0), ctx)
        val stop  = translateExpr(args(1), ctx)
        (s"$start until $stop", info)
      case 3 =>
        val start = translateExpr(args(0), ctx)
        val stop  = translateExpr(args(1), ctx)
        val step  = translateExpr(args(2), ctx)
        (s"$start until $stop by $step", info)
      case _ => (ctx.todo("range with unexpected args"), VarInfo("_"))

  // ── binary ops ───────────────────────────────────────────────────────────

  private def translateBinOp(
      node: Value, ctx: TranslateContext): (String, VarInfo) =
    val left  = translateExpr(node("left"), ctx)
    val right = translateExpr(node("right"), ctx)
    val op = node("op")("_type").str match
      case "Add"      => "+"
      case "Sub"      => "-"
      case "Mult"     => "*"
      case "Div"      => "/"
      case "MatMult"  => "*"
      case "Pow"      => ctx.todo("use .power(n) for element-wise power"); ".power"
      case "Mod"      => "%"
      case "FloorDiv" => "/"
      case other      => ctx.todo(s"unsupported binop: $other"); "??"
    if op == ".power" then (s"$left.power($right)", VarInfo("_"))
    else (s"$left $op $right", VarInfo("_"))

  // ── unary ops ────────────────────────────────────────────────────────────

  private def translateUnaryOp(
      node: Value, ctx: TranslateContext): (String, VarInfo) =
    val operand = translateExpr(node("operand"), ctx)
    val op = node("op")("_type").str match
      case "USub"   => "-"
      case "UAdd"   => "+"
      case "Not"    => "!"
      case "Invert" => "~"
      case other    => ctx.todo(s"unsupported unary op: $other"); "??"
    (s"$op$operand", VarInfo("_", isMatrix = false))

  // ── compare ──────────────────────────────────────────────────────────────

  private def translateCompare(
      node: Value, ctx: TranslateContext): (String, VarInfo) =
    val left  = translateExpr(node("left"), ctx)
    val ops   = node("ops").arr
    val comps = node("comparators").arr
    val leftIsMatrix = ctx.vars.get(left).exists(_.isMatrix)

    if ops.length == 1 then
      val op   = ops(0)("_type").str
      val comp = translateExpr(comps(0), ctx)
      if leftIsMatrix then
        val matOp = op match
          case "Gt"    => s"$left.gt($comp)"
          case "Lt"    => s"$left.lt($comp)"
          case "GtE"   => s"$left.gte($comp)"
          case "LtE"   => s"$left.lte($comp)"
          case "Eq"    => s"$left.:==($comp)"
          case "NotEq" => s"$left.:!=($comp)"
          case other   => ctx.todo(s"unsupported matrix compare: $other")
        (matOp, VarInfo("_", scalaType = "Boolean"))
      else
        val scalaOp = op match
          case "Gt"    => ">"
          case "Lt"    => "<"
          case "GtE"   => ">="
          case "LtE"   => "<="
          case "Eq"    => "=="
          case "NotEq" => "!="
          case "Is"    => "eq"
          case "IsNot" => "ne"
          case "In"    => ctx.todo("in operator"); "contains"
          case other   => ctx.todo(s"unsupported compare: $other"); "??"
        (s"$left $scalaOp $comp", VarInfo("_", isMatrix = false))
    else
      val parts = ops.zip(comps).map { case (op, comp) =>
        val c = translateExpr(comp, ctx)
        op("_type").str match
          case "Gt"    => s"$left > $c"
          case "Lt"    => s"$left < $c"
          case "GtE"   => s"$left >= $c"
          case "LtE"   => s"$left <= $c"
          case "Eq"    => s"$left == $c"
          case "NotEq" => s"$left != $c"
          case other   => ctx.todo(s"chained compare: $other")
      }
      (parts.mkString(" && "), VarInfo("_", isMatrix = false))

  // ── bool op ──────────────────────────────────────────────────────────────

  private def translateBoolOp(
      node: Value, ctx: TranslateContext): (String, VarInfo) =
    val op = node("op")("_type").str match
      case "And" => "&&"
      case "Or"  => "||"
      case other => ctx.todo(s"unsupported bool op: $other"); "??"
    val values = node("values").arr.map(v => translateExpr(v, ctx))
    (values.mkString(s" $op "), VarInfo("_", isMatrix = false))

  // ── slice ────────────────────────────────────────────────────────────────

  private def translateSlice(node: Value, ctx: TranslateContext): String =
    node("_type").str match
      case "Slice" =>
        val lower = node.obj.get("lower").filter(!_.isNull).map(v => translateExpr(v, ctx))
        val upper = node.obj.get("upper").filter(!_.isNull).map(v => translateExpr(v, ctx))
        val step  = node.obj.get("step").filter(!_.isNull).map(v => translateExpr(v, ctx))
        (lower, upper, step) match
          case (None,    None,    None)    => "::"
          case (None,    None,    Some(s)) => s"0 until Int.MaxValue by $s"
          case (Some(l), None,    None)    => s"$l until Int.MaxValue"
          case (None,    Some(u), None)    => s"0 until $u"
          case (Some(l), Some(u), None)    => s"$l until $u"
          case (Some(l), Some(u), Some(s)) => s"$l until $u by $s"
          case (None,    Some(u), Some(s)) => s"0 until $u by $s"
          case (Some(l), None,    Some(s)) => s"$l until Int.MaxValue by $s"
      case "Tuple" =>
        node("elts").arr.map(e => translateSlice(e, ctx)).mkString(", ")
      case "Constant" =>
        translateExpr(node, ctx)
      case "Name" =>
        val id = node("id").str
        if id == "None" then "::" else translateExpr(node, ctx)
      case _ =>
        translateExpr(node, ctx)

  // ── list comp ────────────────────────────────────────────────────────────

  private def translateListComp(
      node: Value, ctx: TranslateContext): (String, VarInfo) =
    val elt  = translateExpr(node("elt"), ctx)
    val gens = node("generators").arr
    val parts = gens.map { gen =>
      val target = translateExpr(gen("target"), ctx)
      val iter   = translateExpr(gen("iter"), ctx)
      val ifs    = gen("ifs").arr.map(i => s"if ${translateExpr(i, ctx)}").mkString(" ")
      s"$target <- $iter $ifs".trim
    }
    (s"for ${parts.mkString("; ")} yield $elt", VarInfo("_", isMatrix = false))
}
