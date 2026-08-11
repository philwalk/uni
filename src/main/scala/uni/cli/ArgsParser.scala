package uni.cli

import uni.*
import scala.util.DynamicVariable
import java.nio.file.Files

// public API:
// showUsage, eachArg, thisArg, consumeNext, peekNext, nextInt, nextLong, nextDouble

object ArgsParser {
  private[uni] var exitFn: Int => Nothing = (code: Int) => sys.exit(code)

  // inline causes `currentCaller` macro to expand callers source path
  inline def showUsage(m: String = "", list: String*): Nothing = {
    if m.nonEmpty then System.err.print(s"$m\n")
    val prog = progName // FromClassName(currentCaller.replace('\\', '/').replaceAll("^.*/", ""))
    System.err.print(s"usage: $prog <options>\n")
    list.filter(_.nonEmpty).foreach(s => System.err.print(s"$s\n"))
    exitFn(1)
  }

  def eachArg(args: Seq[String], usage: String => Nothing)
             (pf: PartialFunction[String, Unit]): Unit = {
    ArgsParser.withArgs(args, usage)(pf)
  }

  private final class Ctx(args: Seq[String], usage: String => Nothing) {
    private var i: Int = 0

    def thisArg: String = args(i)

    private inline def withNext[A](onOk: => A): A = {
      if i + 1 < args.length then {
        onOk
      } else {
        usage(s"missing argument after [$thisArg]")
      }
    }

    def consumeNext: String = {
      withNext {
        i += 1
        args(i)
      }
    }

    // "" for "no next argument" -- indistinguishable from a genuine empty-string
    // argument, by design: peeking is for lookahead decisions, and a caller that
    // needs the distinction uses consumeNext, which errors instead of answering.
    def peekNext: String =
      if i + 1 < args.length then args(i + 1) else ""

    def nextInt: Int = {
      consumeNext.toIntOption.getOrElse {
        usage(s"expected Int after [$thisArg]")
      }
    }

    def nextLong: Long = {
      consumeNext.toLongOption.getOrElse {
        usage(s"expected Long after [$thisArg]")
      }
    }

    def nextDouble: Double = {
      consumeNext.toDoubleOption.getOrElse {
        usage(s"expected Double after [$thisArg]")
      }
    }

    def run(pf: PartialFunction[String, Unit]): Unit = {
      while i < args.length do {
        // applyOrElse, not isDefinedAt-then-apply: the latter evaluates every
        // case's pattern twice per argument.
        pf.applyOrElse(thisArg, (a: String) => usage(s"unknown argument [$a]"))
        i += 1
      }
    }
  }

  private val current = new DynamicVariable[Ctx | Null](null)

  private def withArgs(args: Seq[String], usage: String => Nothing)
                 (pf: PartialFunction[String, Unit]): Unit = {
    val ctx = new Ctx(args, usage)
    current.withValue(ctx) {
      ctx.run(pf)
    }
  }

  private def ctx: Ctx = {
    current.value match {
      case c: Ctx => c
      case null =>
        throw new IllegalStateException("argument helpers used outside eachArg")
    }
  }

  def thisArg: String = ctx.thisArg

  def consumeNext: String = ctx.consumeNext

  def peekNext: String = ctx.peekNext

  def nextInt: Int = ctx.nextInt

  def nextLong: Long = ctx.nextLong

  def nextDouble: Double = ctx.nextDouble

  /* returns base filename */
  inline def progName: String = {
    // First try scala-cli's source.names property
    val directPath = progPath
    if Files.isRegularFile(Paths.get(directPath)) then
      // expected if launched by scala-cli 
      directPath.replaceAll(".*/", "") // bare filename
    else
      // Try to find the source file
      val srcfileOpt = findSourceFile(directPath)
      srcfileOpt.getOrElse(directPath)
  }

  /* returns absolute path */
  inline def progPath: String = {
    // First try scala-cli's source.names property
    val sourceNameOpt = Option(sys.props("scala.sources"))
    sourceNameOpt.getOrElse {
      currentCaller.replace('\\', '/')
    }
  }

  /* `progNameFromClassname(this)` is the expected use-case */
  def progNameFromClassname(mainObject: AnyRef) = Option(sys.props("scala.source.names")).getOrElse {
    // usage: progName(this) from the main object.
    val str = mainObject match {
    case name: String =>
      name
    case obj: AnyRef =>
      obj.getClass.getName
    }
    str.replaceAll(".*[.]", "")   // drop package
      .replaceAll("[$].*", "")   // drop Scala object suffix
  }

  private def findSourceFile(fileName: String): Option[String] = {

    // Possible source file names for the class
    val candidates = Seq(fileName)
    
    // Search in common source directories
    val searchRoots = Seq(
      Paths.get("."),
      Paths.get("src/main/scala"),
      Paths.get("src/test/scala")
    )
    
    searchRoots.iterator.flatMap { root =>
      if (Files.exists(root)) {
        candidates.flatMap { fileName =>
          findFileRecursive(root, fileName)
        }
      } else {
        Nil
      }
    }.nextOption().map(_.toString)
  }

  private def findFileRecursive(root: Path, fileName: String): Option[Path] = {
    import java.nio.file.Files
    import scala.jdk.StreamConverters.*
    
    try {
      // No FOLLOW_LINKS: this is a display-only last resort for a usage message,
      // and following symlinks buys cycle risk and slow scans for no benefit.
      Files.walk(root, 10)
        .toScala(Iterator)
        .find(p => p.getFileName.toString == fileName)
    } catch {
      case _: Exception => None
    }
  }
}
