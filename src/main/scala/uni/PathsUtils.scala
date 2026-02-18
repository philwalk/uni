//#!/usr/bin/env -S scala-cli --cli-version nightly shebang -deprecation -q
package uni

import java.io.{File as JFile}
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.{StandardCharsets}
import java.nio.file.{Path, Files, Paths as JPaths}
import java.util.Locale
import scala.collection.immutable.SortedMap
import scala.sys.process.*
import scala.util.Try
import scala.util.Properties
import uni.ext.*

export scala.util.Properties.{isWin, isMac, isLinux}
export Proc.{ProcStatus, call, shellExec, shellExecProc, spawn, spawnStreaming, execLines}
export Proc.{lazyLines, bashExe, unameExe, uname, osType, where, isWsl, hostname}
export System.err.{println as eprintln, print as eprint} // these return Unit

val verboseUni: Boolean = Option(System.getenv("VERBOSE_UNI")).nonEmpty

// wrapper method better than `export System.err.printf as eprintf` due to `Unit` return.
def eprintf(format: String, args: Any*): Unit = 
  System.err.printf(format, args*)  // ✅ Returns Unit

/**
 * Print a filtered stack trace.
 */
private def withFilteredStack(e: Throwable)(p: StackTraceElement => Boolean): Unit = {
  val original = e.getStackTrace
  val filtered = original.filter(p)
  e.setStackTrace(filtered)
  e.printStackTrace()
  e.setStackTrace(original)
}

/*
 * Print a less verbose stack trace.
 */
def showLimitedStack(e: Throwable): Unit = {
  withFilteredStack(e){ elem =>
    val cls = elem.getClassName
    !cls.startsWith("java.") &&
    !cls.startsWith("javax.") &&
    !cls.startsWith("jdk.") &&
    !cls.startsWith("sun.") &&
    !cls.startsWith("oracle.") &&
    !cls.startsWith("scala.")
  }
}

/*
 * Only show stack trace elements of caller object.
 * Usage: showMinimalStack(e, this)
 */
inline def showMinimalStack(e: Exception = new RuntimeException("showMinimalStack")): Unit = {
  val lcMain = progName.toLowerCase
  withFilteredStack(e) { elem =>
    elem.toString.toLowerCase.contains(lcMain)
  }
}

lazy val userHome = sys.props("user.home").replace('\\', '/')

// Minimal process helpers for portability
object Proc {

  import scala.sys.process.*

  def execLines(cmd: String*): LazyList[String] = {
    Process(cmd.toSeq).lazyLines_!
  }
  def lazyLines(cmd: String): LazyList[String] =
    import java.lang.ProcessBuilder
    val pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(false)   // keep stderr separate

    val p = pb.start()

    val reader = new BufferedReader(new InputStreamReader(p.getInputStream))

    def loop(): LazyList[String] =
      val line = reader.readLine()
      if line == null then LazyList.empty
      else line #:: loop()

    loop()

  def where(prog: String): String =
    if isWin then
      val winprog = prog.stripSuffix(".exe")
      call("where.exe", s"$winprog.exe").getOrElse(prog)
    else
      call("which", prog).getOrElse(prog)

  def whereInPath(prog: String): Option[String] =
    val name  = if isWin && !prog.endsWith(".exe") then prog + ".exe" else prog
    val sep   = java.io.File.pathSeparator
    val paths = sys.env.get("PATH").iterator.flatMap(_.split(sep))
    paths
      .map(p => java.nio.file.Paths.get(p, name))
      .find(Files.isExecutable(_))
      .map(_.toString)

  // Returns first stdout line if command succeeds
  def call(cmd: String, arg: String): Option[String] = {
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    val status = try
      Seq(cmd, arg).!(ProcessLogger(line => buf += line, _ => ()))
    catch
      case e: java.io.IOException =>
        System.err.printf("%s\n", e.getMessage)
        -1
    if (status == 0 && buf.nonEmpty) Some(buf.head) else None
  }
  lazy val exe: String = if isWin then ".exe" else ""

  case class ProcStatus[Out, Err](status: Int, stdout: Out, stderr: Err, e: Option[Exception] = None)

  def spawn(cmd: String *): ProcStatus[Seq[String], Seq[String]] = {
    import scala.collection.mutable.ListBuffer
    val (stdout, stderr) = (ListBuffer.empty[String], ListBuffer.empty[String])
    val cmdArray = cmd.toArray.updated(0, cmd.head.stripSuffix(exe) + exe)
    try {
      val status = cmdArray.toSeq ! ProcessLogger(stdout append _, stderr append _)
      ProcStatus(status, stdout.toSeq, stderr.toSeq)
    } catch {
      case e: Exception =>
        ProcStatus(-1, stdout.toSeq, (stderr append e.getMessage).toSeq)
    }
  }

  def call(cmd: String *): Option[String] = {
    try {
      val ret = spawn(cmd *)
      if (ret.status != 0){
        None
      } else {
        ret.stdout.mkString("\n").trim match {
          case s if s.nonEmpty =>
            Some(s)
          case _ =>
            None
        }
      }
    } catch {
      case _: Throwable =>
        None
    }
  }

  def shellExecProc(bashCommand: String): ProcStatus[Seq[String], Seq[String]] = {
    try {
      val cmd = Seq(bashExe, "-c", bashCommand)
      spawn(cmd *)
    } catch {
      case e: Exception =>
        ProcStatus(-1, Nil, Nil, Some(e))
    }
  }

  // happy path wrapper
  def shellExec(bashCommand: String): Seq[String] = {
    val proc = shellExecProc(bashCommand)
    proc.stdout
  }

  def shellExec(str: String, env: Map[String, String] = Map.empty[String, String]): LazyList[String] = {
    val cmd      = Seq(bashExe, "-c", str)
    val envPairs = env.map { case (a, b) => (a, b) }.toList
    val proc     = Process(cmd, pwd.toFile, envPairs *)
    proc.lazyLines_!
  }

  import scala.collection.mutable.Queue
  import scala.concurrent.{ExecutionContext, Future}
  import scala.sys.process._
  import java.io.{BufferedReader, InputStream, InputStreamReader}

  case class StreamingProc(
    status: Int,
    stdout: LazyList[String],
    stderr: LazyList[String],
    cancel: () => Unit
  )
  def spawnStreaming(cmd: String*)(using ec: ExecutionContext): StreamingProc = {
    val stdoutQ = Queue.empty[String]
    val stderrQ = Queue.empty[String]

    @volatile var stdoutDone  = false
    @volatile var stderrDone  = false
    @volatile var cancelled   = false

    def readerThread(is: InputStream, q: Queue[String], markDone: => Unit): Unit =
      Future {
        val br = new BufferedReader(new InputStreamReader(is))
        try {
          var line: String | Null = null
          while (!cancelled && { line = br.readLine(); line != null }) {
            q.synchronized {
              q.enqueue(line)
              q.notifyAll()
            }
          }
        } finally {
          markDone
          q.synchronized { q.notifyAll() }
          br.close()
        }
      }

    def streamFrom(q: Queue[String], doneFlag: => Boolean): LazyList[String] = {
      def next: Option[String] =
        q.synchronized {
          while (q.isEmpty && !doneFlag && !cancelled)
            q.wait()
          if (q.nonEmpty) Some(q.dequeue())
          else None
        }

      LazyList
        .continually(next)
        .takeWhile(_.isDefined)
        .map(_.get)
    }

    val io = new ProcessIO(
      _   => (),
      out => readerThread(out, stdoutQ, { stdoutDone = true }),
      err => readerThread(err, stderrQ, { stderrDone = true })
    )

    val p = Process(cmd).run(io)

    val stdoutStream = streamFrom(stdoutQ, stdoutDone)
    val stderrStream = streamFrom(stderrQ, stderrDone)

    // Wait for both readers to finish before capturing final status
    while (!stdoutDone || !stderrDone) {
      Thread.sleep(1)
    }
    val exitStatus = p.exitValue()

    def cancelFn(): Unit = {
      cancelled = true
      stdoutQ.synchronized { stdoutQ.notifyAll() }
      stderrQ.synchronized { stderrQ.notifyAll() }
      p.destroy()
    }

    StreamingProc(exitStatus, stdoutStream, stderrStream, cancelFn)
  }

  lazy val bashExe = if isWin then
    call("where.exe", "bash.exe").getOrElse("bash.exe")
  else
    "/bin/bash"

  lazy val unameExe = where("uname")

  // uname wrapper preserved exactly
  def uname(arg: String = "-a"): String = {
    val exe = if isWin then ".exe" else ""
    call(s"uname$exe", arg).getOrElse("")
  }
  def isWsl: Boolean = uname("-r").contains("WSL")

  lazy val osType: String = sys.props("os.name").toLowerCase match {
  case s if s.contains("windows")  => "windows"
  case s if s.contains("linux")    => "linux"
  case s if s.contains("mac os x") => "darwin"
  case other =>
    sys.error(s"osType is [$other]")
  }

  def hostname = java.net.InetAddress.getLocalHost.getHostName
}

lazy val pwd: Path = JPaths.get(config.userdir)

def isWinshell: Boolean = isWin && Properties.propOrNone("MSYSTEM").nonEmpty

object Internals {
  import ext.*

  def realpathWindows(path: String): String = {
    if (!isWin) {
      path
    } else {
      def reparseTest(path: String): String = {
        Try {
          // this line throws an exception if path is not a Windows reparse point (symlink)
          val output = Seq("fsutil", "reparsepoint", "query", path).!!.linesIterator.toList

          // Collect hex dump lines
          val hexLines = output.filter(_.matches("""^\s*[0-9A-Fa-f]{4}:.*"""))
          val hexPairs = hexLines.flatMap(_.drop(6).trim.split("\\s+").filter(_.nonEmpty))
          val bytes    = hexPairs.map(Integer.parseInt(_, 16).toByte).toArray

          val decoded  = new String(bytes, StandardCharsets.UTF_16LE).trim
          val parts = decoded.split("\\?\\?\\\\")
          val printName: String = parts.lastOption.getOrElse(decoded) // user-friendly
          printName
        }.getOrElse(path)
      }

      def loop(p: Path): String = {
        if (p == null) {
          path
        } else {
          val resolved = reparseTest(p.toString)
          if resolved != p.toString then resolved
          else loop(p.getParent)
        }
      }
      loop(JPaths.get(path))
    }
  }

  def realWhere(jpath: Path): Path = {
    realWhere(jpath.toString.replace('\\', '/'))
  }

  def realWhere(mightBeSymlinkToExecutable: String): Path = {
    try {
      if (!isWin) {
        val cmd = Seq("bash", "-c", s"""realpath "`command -v ${mightBeSymlinkToExecutable}`" """.trim)
        val real: String = call(cmd *).getOrElse(mightBeSymlinkToExecutable)
        JPaths.get(real)
      } else {
        val real = realpathWindows(mightBeSymlinkToExecutable)
        JPaths.get(real)
      }
    } catch {
      case e: Exception =>
        JPaths.get(mightBeSymlinkToExecutable) 
    }
  }

  def relativePathToCwd(p: Path): Path = {
    val candidate =
      if !p.isAbsolute && p.getRoot != null then {
        val driveRoot = pwd.getRoot
        driveRoot.resolve(p.toString.substring(1))
      } else {
        p
      }

    if candidate.isAbsolute then {
      try {
        val rel = pwd.relativize(candidate)
        if !rel.toString.startsWith("..") then {
          rel
        } else {
          candidate
        }
      } catch {
        case _: IllegalArgumentException => candidate
      }
    } else {
      candidate
    }
  }

  lazy val defaultDrive: String = defaultDriveLetter+":"

  def defaultDriveLetter: String = {
    if (isWin) new JFile("/").getAbsolutePath.take(1) else ""
  }

  def showMountMaps(): Unit = {
    printf("%s\n", "Forward Map:")
    config.win2posix.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v.mkString(","))
      printf("%s\n", row)
    }

    printf("\n%s\n", "Reverse Map:")
    config.posix2win.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v)
      printf("%s\n", row)
    }
  }

  def samePathString(s1: String ,s2: String): Boolean = {
    if (isWin || isMac) {
      s1.equalsIgnoreCase(s2)
    } else {
      s1 == s2
    }
  }

  def sameFileTest(p1: Path, p2: Path): Boolean = {
    try {
      val (p1str, p2str) = (p1.toFile.getAbsolutePath, p2.toFile.getAbsolutePath)
      // even files that !canExist() can be the same file
      // if path strings are an exact path
      samePathString(p1str, p2str) || {
        canExist(p1) && canExist(p2) && {
          Files.isSameFile(p1, p2)
        }
      }
    } catch {
      case _: Exception =>
        false
    }
  }

  def exists(fname: String): Boolean = Files.exists(JPaths.get(fname))

  def standardizePath(p: Path): String = {
    val winPath: String = if canExist(p) then
      p.toAbsolutePath.normalize.toString
    else
      p.toFile.getAbsolutePath // is this adequate?

    val pathstr = winPath
    if (!isWin) {
      pathstr
    } else {
      val pstr = pathstr.replace('\\', '/') match {
      case "/" => "/"
      case s => s.stripSuffix("/") // no trailing slash
      }

      // First check explicit mounts
      val w2pm = config.win2posix
      val maybeMount = w2pm.keys
        .filter(pstr.startsWithIgnoreCase)
        .toList
        .sortBy(-_.length)
        .headOption

      maybeMount match {
        case Some(winRoot) =>
          // Replace with mapped POSIX mount
          val posixRoots = w2pm(winRoot)
          val post = pstr.drop(winRoot.length)
          posixRoots.head match {
            case "/" =>
              post
            case root =>
              s"$root$post"
          }

        case None =>
          if (pstr.length >= 2 && pstr(1) == ':') {
            // Drive letter path
            val drive = pstr(0).toLower
            val post = pstr.drop(2)
            s"${config.cygdrive}$drive$post"
          } else if (pstr.startsWith("//")) {
            // UNC path
            val unc = pstr.drop(2)
            s"${config.cygdrive}unc/$unc"
          } else {
            // Relative path
            pstr
          }
      }
    }
  }

  def asPosixDrive(dl: String, path: String): String = {
    val root = config.cygdrive
    val cygified = s"$root${dl.take(1).toLowerCase(Locale.ROOT)}$path"
    cygified
  }
  lazy val driveRoot: String = JPaths.get("").toAbsolutePath.getRoot.toString.take(2)

  def _osName: String = sys.props("os.name")

  lazy val _osType: String = _osName.toLowerCase(Locale.ROOT) match {
  case s if s.contains("windows")  => "windows"
  case s if s.contains("linux")    => "linux"
  case s if s.contains("mac os x") => "darwin"
  case other =>
    sys.error(s"osType is [$other]")
  }
 
  //def shellRoot: String = if isWin then call("cygpath.exe", "-m", "/").getOrElse("") else ""

  lazy val here  = pwd.toAbsolutePath.normalize.toString.toLowerCase(Locale.ROOT).replace('\\', '/')
  lazy val uhere = here.replaceFirst("^[a-zA-Z]:", "")
  def hereDrive: String = {
    if (isWin) new JFile("/").getAbsolutePath.take(2).mkString else ""
  }

  def canExist(p: Path): Boolean = {
    val root = p.getRoot
    if (root == null) {
      true
    } else {
      val rootDrive = root.toFile.toString.toUpperCase.take(2)
      rootDrives.contains(rootDrive)
    }
  }

  private[uni] def rootDrives: Seq[String] = 
    Option(java.io.File.listRoots())
      .fold(Seq.empty[String])(_.map(_.getAbsolutePath.take(2)).toSeq)

  def safeAbsolutePath(p: Path): Path =
    if !isWin then
      p.toAbsolutePath
    else
      val s = p.toString

      // Detect drive-only path like "X:"
      val isDriveOnly =
        s.length == 2 &&
        s(1) == ':' &&
        s(0).isLetter

      if isDriveOnly then
        val drive = s(0)
        val root = new java.io.File(s"$drive:/")
        if root.exists() then
          p.toAbsolutePath
        else
          Paths.get(s"$drive:/")   // canonical absolute root
      else
        p.toAbsolutePath

  // maps lookup is by lowercase
  extension [V](m: SortedMap[String, V]) {
    def getLower(key: String): Option[V] =
      m.get(key.toLowerCase(Locale.ROOT))

    def get(key: String): Option[V] =
      m.get(key.toLowerCase(Locale.ROOT))

    def getLowerOrElse(key: String, default: => V): V =
      m.getOrElse(key.toLowerCase(Locale.ROOT), default)

    def getOrElse(key: String, default: => V): V =
      m.getOrElse(key.toLowerCase(Locale.ROOT), default)
  }
}

private val driveLetterPattern =
  java.util.regex.Pattern.compile("^([A-Za-z]):")

private def noTrailingSlash(p: String): String =
  if p == "/" then
    "/"
  else if p.length >= 3 && p(1) == ':' && p(2) == '/' then
    if p.length > 3 then
      p.stripSuffix("/")
    else
      p
  else
    p.stripSuffix("/")

private def normalizePosix(p: Path): String =
  normalizePosix(p.toString)

private def normalizePosix(p: String): String =
  if p.trim.matches("C:[\\/]") then
    hook += 1
  val str = p.replace('\\', '/')
  if str == "/" then "/"
  else noTrailingSlash(str)

/** joined string normalized to never have trailing slash unless == "/" */
private def joinPosix(prefix: String, suffix: String): String =
  val pre  = prefix.stripSuffix("/")
  val post = s"/${suffix.stripPrefix("/")}"
  noTrailingSlash(s"$pre$post")


def stringAbs(raw: String): String = {
  Resolver.resolvePathstr(raw)
}

def applyTildeAndDots(raw: String): String = {
  require(!raw.contains('\\'))
  if raw.isEmpty || raw == "." then
    config.userdir

  else if raw == ".." then
    config.userdirParent

  else
    raw(0) match
      case '~' =>
        // user home
        if raw.length == 1 then
          config.userhome
        else
          config.userhome + raw.substring(1)

      case '.' =>
        // handle ./foo and ../foo
        if raw.startsWith("./") then
          config.userdir + raw.substring(1)

        else if raw.startsWith("../") then
          val parent = config.userdirParent.stripSuffix("/")
          val suffix = raw.substring(2).stripPrefix("/")
          s"$parent/$suffix"

        else
          // ".foo" → userdir + "foo"
          config.userdir + raw.substring(1)

      case _ =>
        // treat only true bare filenames as relative
        if raw.length == 2 && raw(1) == ':' then
          s"${config.driveCwd(raw(0))}"
        else if !raw.contains('/') then
          s"${config.userdir}/$raw"
        else
          raw
}

def quikResolve(raw: String): Path = {
  val s = applyTildeAndDots(raw)
  JPaths.get(s).toAbsolutePath.normalize
}

inline private def parentDirOf(s: String): String =
  val i = s.lastIndexOf('/')
  if i <= 0 then "/" else s.substring(0, i)


/*
 * This method only converts if `isWin`, otherwise it's almost a pass-through.
 * Output is a POSIX-style String.
 * In Windows:
 *   convert rawstr path to `cygpath -u` format
 *   in some cases java sees a different path than cygpath; defer to java.
 */
def posixAbs(raw: String): String = {
  if !isWin then
    Resolver.resolvePathstr(raw) match {
    case "/" => "/"
    case s   => s.stripSuffix("/")
    }
    
  else if raw.startsWith("/") then
    noTrailingSlash(raw)
  else {
    if raw == "file.txt" then
      hook += 1
    val cygMixed = Resolver.resolvePathstr(raw)
    val absPosix =
      if cygMixed.startsWithIgnoreCase(config.cygRoot) then
        cygMixed.drop(config.cygRoot.length)
      else {
//        val win2posx = config.win2posix.toSeq // TODO: remove IDE helper vals
//        val posx2win = config.posix2win.toSeq
        Resolver.findPrefix(cygMixed, config.win2posixKeys) match
          case Some(winPrefix) =>
            val suffix = cygMixed.drop(winPrefix.length).stripSuffix("/")
            config.win2posix.get(winPrefix) match
              case Some(posixSeq) =>
                joinPosix(posixSeq.head, suffix)
              case None =>
                winAbsToPosixAbs(cygMixed)

          case None =>
            // No matching Windows prefix at all → cygdrive fallback
            winAbsToPosixAbs(cygMixed)
      }
    absPosix
  }
}

// leverage posixAbs to deal with ~, trailing slash, etc.
def posixRel(raw: String): String =
  val cwd = posixAbs(config.userdir)
  val abs = posixAbs(raw)

  if abs.equalsIgnoreCase(cwd) then
    "."
  else if abs.startsWithIgnoreCase(cwd + "/") then
    abs.substring(cwd.length + 1)   // skip the slash
  else
    abs

def winAbsToPosixAbs(cygMixed: String): String =
  require(cygMixed.length > 1 && cygMixed(1) == ':', s"not a Windows abs path [$cygMixed]")
  val drive = cygMixed.take(1).toLowerCase
  val path  = cygMixed.drop(2)  // drop "C:"
  s"/$drive$path"

private inline def isDriveLetterPath(s: String): Boolean = {
  s.length >= 2 && s(1) == ':' && {
    val c = s(0)
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
  }
}

