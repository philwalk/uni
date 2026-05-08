package uni

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.file.Path
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger, stringSeqToProcess}
import scala.util.boundary, boundary.break
import scala.util.Properties.isWin

object Proc {

  private[uni] def execLines(cmd: String*): LazyList[String] =
    Process(cmd.toSeq).lazyLines_!

  private[uni] def lazyLines(cmd: String): LazyList[String] =
    val pb = new java.lang.ProcessBuilder(cmd)
    pb.redirectErrorStream(false)
    val p      = pb.start()
    val reader = new BufferedReader(new InputStreamReader(p.getInputStream))
    def loop(): LazyList[String] =
      val line = reader.readLine()
      if line == null then LazyList.empty else line #:: loop()
    loop()

  // Low-level two-arg call used internally by bashExe, uname; never interpolates a shell string.
  private def callQuiet(cmd: String, arg: String): Option[String] = {
    val buf = ListBuffer.empty[String]
    val status = try
      Seq(cmd, arg).!(ProcessLogger(buf += _, _ => ()))
    catch
      case e: java.io.IOException =>
        System.err.printf("%s\n", e.getMessage)
        -1
    if status == 0 && buf.nonEmpty then Some(buf.head) else None
  }

  private lazy val exe: String = if isWin then ".exe" else ""

  case class ProcResult(status: Int, stdout: Seq[String], stderr: Seq[String], cmd: Seq[String])
      extends IndexedSeq[String]:
    def apply(i: Int): String        = stdout(i)
    def length: Int                  = stdout.length
    def text: String                 = stdout.mkString("\n")
    def lines: Seq[String]           = stdout
    def ok: Boolean                  = status == 0
    def toOption: Option[String]     = if ok && stdout.nonEmpty then Some(text) else None
    def orElse(default: => String): String = if ok && stdout.nonEmpty then text else default
    def headOnly: String =
      val h = stdout.head
      val t = Thread(() => stdout.foreach(_ => ()))
      t.setDaemon(true)
      t.start()
      h
    def takeOnly(n: Int): Seq[String] =
      val result = stdout.take(n).toList
      val t = Thread(() => stdout.drop(n).foreach(_ => ()))
      t.setDaemon(true)
      t.start()
      result

  extension (r: ProcResult)
    def !!(msg: String): ProcResult =
      if !r.ok then eprintln(s"$msg [${r.status}]: ${r.cmd.mkString(" ")}")
      r
    infix def orFail(msg: String)(using label: boundary.Label[Int]): ProcResult =
      if !r.ok then
        eprintln(s"$msg [${r.status}]: ${r.cmd.mkString(" ")}")
        break(r.status)
      r

  // Route by file extension.
  // On Linux/macOS the kernel handles shebangs directly, so .py and .sc pass through unchanged;
  // on Windows no shebang support exists, so we prepend the interpreter explicitly.
  // .sh always gets bashExe prepended (handles non-executable scripts on all platforms).
  private def routeCmd(cmd: Seq[String]): Seq[String] =
    cmd.headOption match
      case Some(h) if h.endsWith(".sh")                                   => bashExe +: cmd
      case Some(h) if h.endsWith(".py") && isWin                          => pythonExe +: cmd
      case Some(h) if h.endsWith(".sc") && isWin                          => Seq("scala-cli", "shebang") ++ cmd
      case Some(h) if isWin && (h.endsWith(".bat") || h.endsWith(".cmd")) => Seq("cmd.exe", "/c") ++ cmd
      case Some(h) if isWin && h.endsWith(".ps1")                         => Seq("powershell.exe", "-File") ++ cmd
      case Some(h) if isWin                                                => (h.stripSuffix(".exe") + ".exe") +: cmd.tail
      case _                                                               => cmd

  private def startJavaProcess(
    routed: Seq[String],
    cwd:    Option[java.io.File],
    env:    Map[String, String],
  ): java.lang.Process =
    val pb = new java.lang.ProcessBuilder(routed.asJava)
    cwd.foreach(pb.directory)
    if env.nonEmpty then pb.environment().putAll(env.asJava)
    pb.start()

  private def startReader(is: InputStream, q: LinkedBlockingQueue[Option[String]]): Thread =
    val t = Thread(() =>
      val br = new BufferedReader(new InputStreamReader(is))
      try
        var line = br.readLine()
        while line != null do
          q.put(Some(line))
          line = br.readLine()
      catch case _: (java.io.IOException | InterruptedException) => ()
      finally q.put(None)
    )
    t.setDaemon(true)
    t.start()
    t

  // Drains q into buf; terminates on None sentinel. Runs concurrently with process.waitFor()
  // so that the bounded queue never fills and blocks the reader threads.
  private def drainingThread(q: LinkedBlockingQueue[Option[String]], buf: ListBuffer[String]): Thread =
    val t = Thread(() =>
      var done = false
      while !done do
        q.take() match
          case None    => done = true
          case Some(s) => buf += s
    )
    t.setDaemon(true)
    t.start()
    t

  final class ProcBuilder private[Proc](cmd: Seq[String]):
    private var _cwd:     Option[java.io.File]  = None
    private var _env:     Map[String, String]    = Map.empty
    private var _stdin:   Option[String]         = None
    private var _timeout: Option[Long]           = None

    def cwd(p: Path):                ProcBuilder = { _cwd = Some(p.toFile); this }
    def cwd(s: String):              ProcBuilder = { _cwd = Some(new java.io.File(s)); this }
    def env(m: Map[String, String]): ProcBuilder = { _env = m; this }
    def stdin(s: String):            ProcBuilder = { _stdin = Some(s); this }
    def timeout(ms: Long):           ProcBuilder = { _timeout = Some(ms); this }

    private def pipeStdin(process: java.lang.Process): Unit =
      _stdin.foreach { s =>
        val t = Thread(() =>
          try
            process.getOutputStream.write(s.getBytes("UTF-8"))
            process.getOutputStream.close()
          catch case _: java.io.IOException => ()
        )
        t.setDaemon(true)
        t.start()
      }

    private def awaitProcess(process: java.lang.Process): Int =
      _timeout match
        case Some(ms) =>
          if !process.waitFor(ms, TimeUnit.MILLISECONDS) then
            process.destroyForcibly()
            -1
          else process.exitValue()
        case None => process.waitFor()

    def run(): ProcResult =
      val routed  = routeCmd(cmd)
      val outQ    = new LinkedBlockingQueue[Option[String]](64)
      val errQ    = new LinkedBlockingQueue[Option[String]](64)
      val outBuf  = ListBuffer.empty[String]
      val errBuf  = ListBuffer.empty[String]
      try
        val process = startJavaProcess(routed, _cwd, _env)
        pipeStdin(process)
        startReader(process.getInputStream, outQ)
        startReader(process.getErrorStream, errQ)
        val outD    = drainingThread(outQ, outBuf)
        val errD    = drainingThread(errQ, errBuf)
        val status  = awaitProcess(process)
        outD.join()
        errD.join()
        ProcResult(status, outBuf.toSeq, errBuf.toSeq, routed)
      catch
        case e: java.io.IOException =>
          outQ.put(None); errQ.put(None)
          ProcResult(-1, Seq.empty, Seq(e.getMessage), routed)

    def stream(out: String => Unit, err: String => Unit = eprintln): Int =
      val routed = routeCmd(cmd)
      try
        val process = startJavaProcess(routed, _cwd, _env)
        pipeStdin(process)
        def streamReader(is: InputStream, cb: String => Unit): Thread =
          val t = Thread(() =>
            val br = new BufferedReader(new InputStreamReader(is))
            try
              var line = br.readLine()
              while line != null do
                cb(line)
                line = br.readLine()
            catch case _: (java.io.IOException | InterruptedException) => ()
          )
          t.setDaemon(true)
          t.start()
          t
        val outT   = streamReader(process.getInputStream, out)
        val errT   = streamReader(process.getErrorStream, err)
        val status = awaitProcess(process)
        outT.join()
        errT.join()
        status
      catch case e: java.io.IOException => err(e.getMessage); -1

  def proc(cmd: String*): ProcBuilder = new ProcBuilder(cmd.toSeq)

  /** Buffered: captures stdout and stderr, returns ProcResult with cmd.
   *  If the program is not found, returns a failed ProcResult (status=-1) rather than throwing.
   */
  def run(cmd: String*): ProcResult =
    val routed  = routeCmd(cmd)
    val outQ    = new LinkedBlockingQueue[Option[String]](64)
    val errQ    = new LinkedBlockingQueue[Option[String]](64)
    val outBuf  = ListBuffer.empty[String]
    val errBuf  = ListBuffer.empty[String]
    try
      val process = startJavaProcess(routed, None, Map.empty)
      startReader(process.getInputStream, outQ)
      startReader(process.getErrorStream, errQ)
      val outD    = drainingThread(outQ, outBuf)
      val errD    = drainingThread(errQ, errBuf)
      val status  = process.waitFor()
      outD.join()
      errD.join()
      ProcResult(status, outBuf.toSeq, errBuf.toSeq, routed)
    catch
      case e: java.io.IOException =>
        outQ.put(None); errQ.put(None)
        ProcResult(-1, Seq.empty, Seq(e.getMessage), routed)

  /** Streaming: calls out per stdout line, err per stderr line, returns exit status.
   *  Uses Thread-based readers; no ExecutionContext required.
   *  If the program is not found, calls err with the IOException message and returns -1.
   */
  def run(cmd: String*)(out: String => Unit, err: String => Unit = eprintln): Int =
    val routed = routeCmd(cmd)
    def readerThread(is: InputStream, cb: String => Unit): Thread =
      val t = Thread(() =>
        val br = new BufferedReader(new InputStreamReader(is))
        try
          var line = br.readLine()
          while line != null do
            cb(line)
            line = br.readLine()
        catch case _: java.io.IOException => ()
      )
      t.setDaemon(true)
      t.start()
      t
    try
      val process = startJavaProcess(routed, None, Map.empty)
      val outT    = readerThread(process.getInputStream, out)
      val errT    = readerThread(process.getErrorStream, err)
      val status  = process.waitFor()
      outT.join()
      errT.join()
      status
    catch
      case e: java.io.IOException => err(e.getMessage); -1

  def where(prog: String): String =
    if isWin then
      run("where.exe", prog.stripSuffix(".exe") + ".exe").toOption.getOrElse(prog)
    else
      run("which", prog).toOption.getOrElse(prog)

  def whereInPath(prog: String): Option[String] =
    val name  = if isWin && !prog.endsWith(".exe") then prog + ".exe" else prog
    val sep   = java.io.File.pathSeparator
    val paths = sys.env.get("PATH").iterator.flatMap(_.split(sep))
    paths
      .map(p => java.nio.file.Paths.get(p, name))
      .find(java.nio.file.Files.isExecutable(_))
      .map(_.toString)

  // Uses callQuiet (not run) to avoid depending on bashExe before it is initialized.
  lazy val bashExe: String = if isWin then
    callQuiet("where.exe", "bash.exe").getOrElse("bash.exe")
  else
    "/bin/bash"

  // pythonExe: like bashExe, uses callQuiet to avoid circular init with routeCmd.
  lazy val pythonExe: String =
    if isWin then
      callQuiet("where.exe", "python3.exe")
        .orElse(callQuiet("where.exe", "python.exe"))
        .getOrElse("python3.exe")
    else
      callQuiet("which", "python3")
        .orElse(callQuiet("which", "python"))
        .getOrElse("python3")

  lazy val unameExe: String = where("uname")

  def uname(arg: String = "-a"): String =
    run(s"uname$exe", arg).toOption.getOrElse("")

  def isWsl: Boolean = uname("-r").contains("WSL")

  lazy val osType: String = sys.props("os.name").toLowerCase match
    case s if s.contains("windows")  => "windows"
    case s if s.contains("linux")    => "linux"
    case s if s.contains("mac os x") => "darwin"
    case other                       => sys.error(s"osType is [$other]")

  def hostname: String = java.net.InetAddress.getLocalHost.getHostName
}

extension (status: Int)
  /** Log msg to stderr if status != 0; return status. Chainable. */
  def !!(msg: String): Int =
    if status != 0 then eprintln(s"$msg [$status]")
    status
  /** Invoke f with error description if status != 0; return status. */
  infix def orElse(f: String => Unit): Int =
    if status != 0 then f(s"exit status: $status")
    status
  /** Within failFast { }, break out of the block on non-zero status. */
  infix def orFail(msg: String)(using label: boundary.Label[Int]): Int =
    if status != 0 then
      eprintln(s"$msg [$status]")
      break(status)
    status

/** Run body; any .orFail call inside short-circuits the block on failure. */
def failFast(body: boundary.Label[Int] ?=> Int): Int = boundary(body)
